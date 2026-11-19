//go:build linux

package engine

import (
	"bytes"
	"errors"
	"fmt"
	"io"
	"os"
	"path/filepath"

	"golang.org/x/sys/unix"
)

const (
	machineDNSLockName   = ".gozarfox-fakedns-machine-lock-v1"
	machineDNSMarkerName = ".gozarfox-fakedns-machine-marker-v1"
	machineDNSRootName   = "gozarfox-fakedns-machine-v1"
)

var machineDNSMarker = []byte("GozarFox machine FakeDNS initialized v1\n")

// linuxMachineDNSStorage serializes all cooperating TUN sessions on one
// machine, irrespective of the invoking desktop account, provided all sessions
// use the same root-owned fixed parent (normally /var/lib). It does not defend
// against an administrator removing both marker and directory or rolling them
// back together, or against other VPN products allocating the same range.
// This opener is not yet connected to the engine's production lifecycle.
type linuxMachineDNSStorage struct {
	parent, lock *os.File
	locked       bool
	storage      *linuxDNSStorage
}

func (s *linuxMachineDNSStorage) Close() error {
	if s == nil {
		return nil
	}
	var err error
	if s.storage != nil {
		err = errors.Join(err, s.storage.Close())
		s.storage = nil
	}
	if s.lock != nil {
		if s.locked {
			err = errors.Join(err, unix.Flock(int(s.lock.Fd()), unix.LOCK_UN))
			s.locked = false
		}
		err = errors.Join(err, s.lock.Close())
		s.lock = nil
	}
	if s.parent != nil {
		err = errors.Join(err, s.parent.Close())
		s.parent = nil
	}
	return err
}

// The parent is trusted only if owned by the current effective user and not
// writable by group/others. Production requires euid 0 and a fixed path; the
// caller-supplied parent exists solely for isolated tests and provisioning.
func openLinuxMachineDNSStorage(parentPath string, direct func(string) bool) (*fakeDNS, *linuxMachineDNSStorage, error) {
	if !filepath.IsAbs(parentPath) {
		return nil, nil, errors.New("machine FakeDNS parent must be absolute")
	}
	fd, err := unix.Open(parentPath, unix.O_RDONLY|unix.O_DIRECTORY|unix.O_CLOEXEC|unix.O_NOFOLLOW, 0)
	if err != nil {
		return nil, nil, err
	}
	s := &linuxMachineDNSStorage{parent: os.NewFile(uintptr(fd), parentPath)}
	fail := func(err error) (*fakeDNS, *linuxMachineDNSStorage, error) { _ = s.Close(); return nil, nil, err }
	var st unix.Stat_t
	if err = unix.Fstat(fd, &st); err != nil {
		return fail(err)
	}
	if st.Uid != uint32(os.Geteuid()) || st.Mode&unix.S_IFMT != unix.S_IFDIR || st.Mode&0022 != 0 {
		return fail(errors.New("unsafe machine FakeDNS parent"))
	}
	s.lock, err = dnsOpenAt(fd, machineDNSLockName, unix.O_RDWR|unix.O_CREAT, false)
	if err != nil {
		return fail(err)
	}
	if err = unix.Flock(int(s.lock.Fd()), unix.LOCK_EX|unix.LOCK_NB); err != nil {
		return fail(fmt.Errorf("machine FakeDNS already in use: %w", err))
	}
	s.locked = true
	marker, markerErr := dnsOpenAt(fd, machineDNSMarkerName, unix.O_RDONLY, false)
	first := errors.Is(markerErr, unix.ENOENT)
	if markerErr != nil && !first {
		return fail(markerErr)
	}
	if !first {
		data, readErr := io.ReadAll(io.LimitReader(marker, int64(len(machineDNSMarker)+1)))
		closeErr := marker.Close()
		if readErr != nil || closeErr != nil || !bytes.Equal(data, machineDNSMarker) {
			return fail(errors.New("invalid machine FakeDNS marker"))
		}
	} else {
		var st unix.Stat_t
		statErr := unix.Fstatat(fd, machineDNSRootName, &st, unix.AT_SYMLINK_NOFOLLOW)
		if statErr == nil || !errors.Is(statErr, unix.ENOENT) {
			return fail(errors.New("machine FakeDNS marker missing for existing or inaccessible root"))
		}
		marker, err = dnsOpenAt(fd, machineDNSMarkerName, unix.O_WRONLY|unix.O_CREAT|unix.O_EXCL, false)
		if err != nil {
			return fail(err)
		}
		n, writeErr := marker.Write(machineDNSMarker)
		if writeErr == nil && n != len(machineDNSMarker) {
			writeErr = io.ErrShortWrite
		}
		if writeErr == nil {
			writeErr = marker.Sync()
		}
		closeErr := marker.Close()
		if writeErr != nil || closeErr != nil {
			return fail(errors.Join(writeErr, closeErr))
		}
		if err = unix.Fsync(fd); err != nil {
			return fail(err)
		}
		if err = unix.Mkdirat(fd, machineDNSRootName, 0700); err != nil {
			return fail(err)
		}
		if err = unix.Fsync(fd); err != nil {
			return fail(err)
		}
	}
	fake, storage, err := openLinuxDNSStorage(filepath.Join(parentPath, machineDNSRootName), direct)
	if err != nil {
		return fail(err)
	}
	s.storage = storage
	return fake, s, nil
}
