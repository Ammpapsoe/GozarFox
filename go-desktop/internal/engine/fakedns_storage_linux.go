//go:build linux

package engine

import (
	"bytes"
	"errors"
	"fmt"
	"io"
	"os"

	"golang.org/x/sys/unix"
)

const (
	dnsLockName       = ".fakedns-owner-v1"
	dnsMarkerName     = ".fakedns-initialized-v1"
	dnsDirectoryName  = "fakedns-v1"
	dnsHistoryName    = "history"
	dnsCheckpointName = "checkpoint"
)

var dnsMarker = []byte("GozarFox FakeDNS initialized v1\n")

// linuxDNSStorage holds the advisory lock and open journal files until Close.
// It is currently a building block, not attached to a production TUN session.
// Permissions prevent other users reading browsing history; they do not defend
// against a malicious process or backup rollback under the same user account.
type linuxDNSStorage struct {
	lock       *os.File
	history    *os.File
	checkpoint *os.File
	journal    *fileDNSJournal
}

func (s *linuxDNSStorage) Close() error {
	if s == nil {
		return nil
	}
	var result error
	for _, f := range []*os.File{s.history, s.checkpoint} {
		if f != nil {
			result = errors.Join(result, f.Close())
		}
	}
	if s.lock != nil {
		result = errors.Join(result, unix.Flock(int(s.lock.Fd()), unix.LOCK_UN), s.lock.Close())
	}
	s.history = nil
	s.checkpoint = nil
	s.lock = nil
	return result
}

// checkedDNSFD ensures that an already-open descriptor is private, owned by
// the current Unix account and not an unexpected file type or hardlink.
func checkedDNSFD(fd int, directory bool) error {
	var st unix.Stat_t
	if err := unix.Fstat(fd, &st); err != nil {
		return err
	}
	if st.Uid != uint32(os.Geteuid()) || st.Mode&0077 != 0 {
		return errors.New("unsafe FakeDNS storage owner or permissions")
	}
	if directory {
		if st.Mode&unix.S_IFMT != unix.S_IFDIR {
			return errors.New("FakeDNS storage is not a directory")
		}
	} else if st.Mode&unix.S_IFMT != unix.S_IFREG || st.Nlink != 1 {
		return errors.New("FakeDNS storage is not a private regular file")
	}
	return nil
}

func dnsOpenAt(dir int, name string, flags int, directory bool) (*os.File, error) {
	fd, err := unix.Openat(dir, name, flags|unix.O_CLOEXEC|unix.O_NOFOLLOW, 0600)
	if err != nil {
		return nil, err
	}
	if err := checkedDNSFD(fd, directory); err != nil {
		_ = unix.Close(fd)
		return nil, err
	}
	return os.NewFile(uintptr(fd), name), nil
}

// openLinuxDNSStorage requires root to be an existing private account-owned
// directory. An independent marker in root is synced before any first-use
// journal creation. On subsequent opens, missing or empty history is an error,
// never an instruction to issue a new pool of synthetic addresses.
func openLinuxDNSStorage(root string, direct func(string) bool) (*fakeDNS, *linuxDNSStorage, error) {
	rootFD, err := unix.Open(root, unix.O_RDONLY|unix.O_DIRECTORY|unix.O_CLOEXEC|unix.O_NOFOLLOW, 0)
	if err != nil {
		return nil, nil, err
	}
	defer unix.Close(rootFD)
	if err := checkedDNSFD(rootFD, true); err != nil {
		return nil, nil, err
	}
	s := &linuxDNSStorage{}
	fail := func(err error) (*fakeDNS, *linuxDNSStorage, error) { _ = s.Close(); return nil, nil, err }
	lock, err := dnsOpenAt(rootFD, dnsLockName, unix.O_RDWR|unix.O_CREAT, false)
	if err != nil {
		return fail(err)
	}
	s.lock = lock
	if err := unix.Flock(int(lock.Fd()), unix.LOCK_EX|unix.LOCK_NB); err != nil {
		return fail(fmt.Errorf("FakeDNS storage is in use: %w", err))
	}

	marker, markerErr := dnsOpenAt(rootFD, dnsMarkerName, unix.O_RDONLY, false)
	first := errors.Is(markerErr, unix.ENOENT)
	if markerErr != nil && !first {
		return fail(markerErr)
	}
	if !first {
		value, readErr := io.ReadAll(io.LimitReader(marker, int64(len(dnsMarker)+1)))
		closeErr := marker.Close()
		if readErr != nil || closeErr != nil || !bytes.Equal(value, dnsMarker) {
			return fail(errors.New("invalid FakeDNS first-use marker"))
		}
	} else {
		// An existing directory with a missing marker is ambiguous: it may
		// contain old cached identities. Never reset it automatically.
		var st unix.Stat_t
		statErr := unix.Fstatat(rootFD, dnsDirectoryName, &st, unix.AT_SYMLINK_NOFOLLOW)
		if statErr == nil || !errors.Is(statErr, unix.ENOENT) {
			return fail(errors.New("FakeDNS marker missing for existing or inaccessible storage"))
		}
		marker, err = dnsOpenAt(rootFD, dnsMarkerName, unix.O_WRONLY|unix.O_CREAT|unix.O_EXCL, false)
		if err != nil {
			return fail(err)
		}
		n, writeErr := marker.Write(dnsMarker)
		if writeErr == nil && n != len(dnsMarker) {
			writeErr = io.ErrShortWrite
		}
		if writeErr == nil {
			writeErr = marker.Sync()
		}
		closeErr := marker.Close()
		if writeErr != nil || closeErr != nil {
			return fail(errors.Join(writeErr, closeErr))
		}
		if err := unix.Fsync(rootFD); err != nil {
			return fail(err)
		}
		if err := unix.Mkdirat(rootFD, dnsDirectoryName, 0700); err != nil {
			return fail(err)
		}
		if err := unix.Fsync(rootFD); err != nil {
			return fail(err)
		}
	}
	dir, err := dnsOpenAt(rootFD, dnsDirectoryName, unix.O_RDONLY|unix.O_DIRECTORY, true)
	if err != nil {
		return fail(err)
	}
	defer dir.Close()
	flags := unix.O_RDWR
	if first {
		flags |= unix.O_CREAT | unix.O_EXCL
	}
	s.history, err = dnsOpenAt(int(dir.Fd()), dnsHistoryName, flags, false)
	if err != nil {
		return fail(err)
	}
	s.checkpoint, err = dnsOpenAt(int(dir.Fd()), dnsCheckpointName, flags, false)
	if err != nil {
		return fail(err)
	}
	if first {
		if err := unix.Fsync(int(dir.Fd())); err != nil {
			return fail(err)
		}
	}
	s.journal = &fileDNSJournal{history: s.history, anchor: s.checkpoint}
	if !first {
		_, _, exists, err := s.journal.Load()
		if err != nil {
			return fail(err)
		}
		if !exists {
			return fail(errors.New("FakeDNS history disappeared; refusing to reset"))
		}
	}
	fake, err := newJournalFakeDNS(s.journal, direct)
	if err != nil {
		return fail(err)
	}
	return fake, s, nil
}
