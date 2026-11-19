//go:build windows

package engine

import (
	"bytes"
	"errors"
	"fmt"
	"io"
	"os"
	"path/filepath"
	"runtime"
	"unsafe"

	"golang.org/x/sys/windows"
)

// windowsDNSStorage is a prototype and is not connected to production TUN.
// The caller must provide an existing private root whose ancestor path cannot
// be replaced by another account. The root and storage directory handles are
// held without delete sharing, preventing rename/removal while in use. The
// security policy protects against other users, not the same user or an admin.
type windowsDNSStorage struct {
	root, lock, dir, history, checkpoint *os.File
}

func (s *windowsDNSStorage) Close() error {
	if s == nil {
		return nil
	}
	var result error
	for _, f := range []*os.File{s.history, s.checkpoint, s.dir, s.lock, s.root} {
		if f != nil {
			result = errors.Join(result, f.Close())
		}
	}
	s.root, s.lock, s.dir, s.history, s.checkpoint = nil, nil, nil, nil, nil
	return result
}

func dnsWindowsSecurity() (string, *windows.SecurityAttributes, error) {
	user, err := windows.GetCurrentProcessToken().GetTokenUser()
	if err != nil {
		return "", nil, err
	}
	sid := user.User.Sid.String()
	// Explicitly protected, no inherited ACEs. Full access is needed to check
	// security, append/sync records, and inspect the object on future opens.
	sd, err := windows.SecurityDescriptorFromString("O:" + sid + "D:P(A;;FA;;;" + sid + ")(A;;FA;;;SY)")
	if err != nil {
		return "", nil, err
	}
	return sid, &windows.SecurityAttributes{Length: uint32(unsafe.Sizeof(windows.SecurityAttributes{})), SecurityDescriptor: sd}, nil
}

func dnsWindowsACLBytes(acl *windows.ACL) ([]byte, error) {
	if acl == nil {
		return nil, errors.New("null FakeDNS DACL")
	}
	p := unsafe.Pointer(acl)
	size := *(*uint16)(unsafe.Add(p, 2))
	if size < 8 || size > 4096 {
		return nil, errors.New("invalid FakeDNS DACL")
	}
	return unsafe.Slice((*byte)(p), int(size)), nil
}

func dnsWindowsVerify(h windows.Handle, sid string, attrs *windows.SecurityAttributes, directory bool) error {
	var info windows.ByHandleFileInformation
	if err := windows.GetFileInformationByHandle(h, &info); err != nil {
		return err
	}
	if info.FileAttributes&windows.FILE_ATTRIBUTE_REPARSE_POINT != 0 {
		return errors.New("FakeDNS reparse point rejected")
	}
	isDirectory := info.FileAttributes&windows.FILE_ATTRIBUTE_DIRECTORY != 0
	if isDirectory != directory || (!directory && info.NumberOfLinks != 1) {
		return errors.New("invalid FakeDNS file type or link count")
	}
	sd, err := windows.GetSecurityInfo(h, windows.SE_FILE_OBJECT, windows.OWNER_SECURITY_INFORMATION|windows.DACL_SECURITY_INFORMATION)
	if err != nil {
		return err
	}
	owner, _, err := sd.Owner()
	if err != nil || owner == nil || owner.String() != sid {
		return errors.New("FakeDNS owner is not current user")
	}
	control, _, err := sd.Control()
	if err != nil || control&windows.SE_DACL_PROTECTED == 0 {
		return errors.New("FakeDNS DACL is not protected")
	}
	actual, _, err := sd.DACL()
	if err != nil {
		return err
	}
	expected, _, err := attrs.SecurityDescriptor.DACL()
	if err != nil {
		return err
	}
	a, err := dnsWindowsACLBytes(actual)
	if err != nil {
		return err
	}
	b, err := dnsWindowsACLBytes(expected)
	if err != nil || !bytes.Equal(a, b) {
		return errors.New("FakeDNS DACL does not match private policy")
	}
	runtime.KeepAlive(sd)
	runtime.KeepAlive(attrs)
	return nil
}

// Use no delete sharing for directories and no sharing for files. The lock's
// exclusive Windows open acts as the cross-process lock; the OS closes it when
// a process dies. OPEN_REPARSE_POINT permits inspection rather than following
// a final-component symlink or junction.
func dnsWindowsOpen(path string, disposition uint32, directory bool, sid string, attrs *windows.SecurityAttributes) (*os.File, error) {
	p, err := windows.UTF16PtrFromString(path)
	if err != nil {
		return nil, err
	}
	access := uint32(windows.GENERIC_READ | windows.READ_CONTROL)
	share := uint32(0)
	flags := uint32(windows.FILE_FLAG_OPEN_REPARSE_POINT)
	if directory {
		share = windows.FILE_SHARE_READ | windows.FILE_SHARE_WRITE
		flags |= windows.FILE_FLAG_BACKUP_SEMANTICS
	} else {
		access |= windows.GENERIC_WRITE
	}
	h, err := windows.CreateFile(p, access, share, attrs, disposition, flags, 0)
	runtime.KeepAlive(attrs)
	if err != nil {
		return nil, err
	}
	if err = dnsWindowsVerify(h, sid, attrs, directory); err != nil {
		_ = windows.CloseHandle(h)
		return nil, err
	}
	return os.NewFile(uintptr(h), path), nil
}

func openWindowsDNSStorage(root string, direct func(string) bool) (*fakeDNS, *windowsDNSStorage, error) {
	if !filepath.IsAbs(root) {
		return nil, nil, errors.New("FakeDNS root must be absolute")
	}
	sid, attrs, err := dnsWindowsSecurity()
	if err != nil {
		return nil, nil, err
	}
	return openWindowsDNSStorageWithSecurity(root, direct, sid, attrs)
}

func openWindowsDNSStorageWithSecurity(root string, direct func(string) bool, sid string, attrs *windows.SecurityAttributes) (*fakeDNS, *windowsDNSStorage, error) {
	if !filepath.IsAbs(root) || sid == "" || attrs == nil || attrs.SecurityDescriptor == nil {
		return nil, nil, errors.New("invalid FakeDNS storage security arguments")
	}
	var err error
	s := &windowsDNSStorage{}
	fail := func(err error) (*fakeDNS, *windowsDNSStorage, error) { _ = s.Close(); return nil, nil, err }
	s.root, err = dnsWindowsOpen(root, windows.OPEN_EXISTING, true, sid, attrs)
	if err != nil {
		return fail(fmt.Errorf("unsafe FakeDNS root: %w", err))
	}
	s.lock, err = dnsWindowsOpen(filepath.Join(root, dnsLockName), windows.OPEN_ALWAYS, false, sid, attrs)
	if err != nil {
		return fail(fmt.Errorf("FakeDNS storage lock: %w", err))
	}
	markerPath := filepath.Join(root, dnsMarkerName)
	dirPath := filepath.Join(root, dnsDirectoryName)
	marker, err := dnsWindowsOpen(markerPath, windows.OPEN_EXISTING, false, sid, attrs)
	first := errors.Is(err, windows.ERROR_FILE_NOT_FOUND)
	if err != nil && !first {
		return fail(err)
	}
	if !first {
		value, readErr := io.ReadAll(io.LimitReader(marker, int64(len(dnsMarker)+1)))
		closeErr := marker.Close()
		if readErr != nil || closeErr != nil || !bytes.Equal(value, dnsMarker) {
			return fail(errors.New("invalid FakeDNS first-use marker"))
		}
	} else {
		// Missing marker plus any existing directory is ambiguous; do not reset.
		_, statErr := os.Lstat(dirPath)
		if statErr == nil || !errors.Is(statErr, os.ErrNotExist) {
			return fail(errors.New("FakeDNS marker missing for existing or inaccessible storage"))
		}
		marker, err = dnsWindowsOpen(markerPath, windows.CREATE_NEW, false, sid, attrs)
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
		p, err := windows.UTF16PtrFromString(dirPath)
		if err != nil {
			return fail(err)
		}
		err = windows.CreateDirectory(p, attrs)
		runtime.KeepAlive(attrs)
		if err != nil {
			return fail(err)
		}
	}
	s.dir, err = dnsWindowsOpen(dirPath, windows.OPEN_EXISTING, true, sid, attrs)
	if err != nil {
		return fail(err)
	}
	disposition := uint32(windows.OPEN_EXISTING)
	if first {
		disposition = windows.CREATE_NEW
	}
	s.history, err = dnsWindowsOpen(filepath.Join(dirPath, dnsHistoryName), disposition, false, sid, attrs)
	if err != nil {
		return fail(err)
	}
	s.checkpoint, err = dnsWindowsOpen(filepath.Join(dirPath, dnsCheckpointName), disposition, false, sid, attrs)
	if err != nil {
		return fail(err)
	}
	journal := &fileDNSJournal{history: s.history, anchor: s.checkpoint}
	if !first {
		_, _, exists, err := journal.Load()
		if err != nil {
			return fail(err)
		}
		if !exists {
			return fail(errors.New("FakeDNS history disappeared; refusing to reset"))
		}
	}
	fake, err := newJournalFakeDNS(journal, direct)
	if err != nil {
		return fail(err)
	}
	return fake, s, nil
}
