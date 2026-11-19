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

const (
	machineDNSLockName   = ".gozarfox-fakedns-machine-lock-v1"
	machineDNSMarkerName = ".gozarfox-fakedns-machine-marker-v1"
	machineDNSRootName   = "gozarfox-fakedns-machine-v1"
)

var machineDNSMarker = []byte("GozarFox machine FakeDNS initialized v1\n")

// Machine storage is shared by elevated administrators and LocalSystem. The
// caller must supply the SAME protected machine parent for all sessions, with
// safe ancestors. Administrators can read the recorded domain names. It is a
// prototype, not yet attached to production or provisioned in ProgramData.
// As with the Linux version, coordinated deletion/rollback of marker and root
// or another VPN using the same synthetic range are outside this guarantee.
type windowsMachineDNSStorage struct {
	parent, lock *os.File
	storage      *windowsDNSStorage
}

func (s *windowsMachineDNSStorage) Close() error {
	if s == nil {
		return nil
	}
	var err error
	if s.storage != nil {
		err = errors.Join(err, s.storage.Close())
		s.storage = nil
	}
	if s.lock != nil {
		err = errors.Join(err, s.lock.Close())
		s.lock = nil
	}
	if s.parent != nil {
		err = errors.Join(err, s.parent.Close())
		s.parent = nil
	}
	return err
}

func dnsWindowsMachineSecurity() (string, *windows.SecurityAttributes, error) {
	admin, err := windows.CreateWellKnownSid(windows.WinBuiltinAdministratorsSid)
	if err != nil {
		return "", nil, err
	}
	member, err := windows.GetCurrentProcessToken().IsMember(admin)
	if err != nil {
		return "", nil, err
	}
	if !member {
		return "", nil, errors.New("machine FakeDNS requires elevated administrator")
	}
	sd, err := windows.SecurityDescriptorFromString("O:BAD:P(A;;FA;;;BA)(A;;FA;;;SY)")
	if err != nil {
		return "", nil, err
	}
	return admin.String(), &windows.SecurityAttributes{Length: uint32(unsafe.Sizeof(windows.SecurityAttributes{})), SecurityDescriptor: sd}, nil
}

// A shared, protected root plus an independently checked marker gives all
// elevated cooperating accounts a single ordered mapping. The lock file is
// opened with no sharing until Close; unlike a Windows mutex it is not tied to
// a particular OS thread. A crash closes the handle, but does not reset data.
func openWindowsMachineDNSStorage(parent string, direct func(string) bool) (*fakeDNS, *windowsMachineDNSStorage, error) {
	if !filepath.IsAbs(parent) {
		return nil, nil, errors.New("machine FakeDNS parent must be absolute")
	}
	sid, attrs, err := dnsWindowsMachineSecurity()
	if err != nil {
		return nil, nil, err
	}
	s := &windowsMachineDNSStorage{}
	fail := func(err error) (*fakeDNS, *windowsMachineDNSStorage, error) { _ = s.Close(); return nil, nil, err }
	s.parent, err = dnsWindowsOpen(parent, windows.OPEN_EXISTING, true, sid, attrs)
	if err != nil {
		return fail(fmt.Errorf("unsafe machine FakeDNS parent: %w", err))
	}
	s.lock, err = dnsWindowsOpen(filepath.Join(parent, machineDNSLockName), windows.OPEN_ALWAYS, false, sid, attrs)
	if err != nil {
		return fail(fmt.Errorf("machine FakeDNS lock: %w", err))
	}
	markerPath := filepath.Join(parent, machineDNSMarkerName)
	root := filepath.Join(parent, machineDNSRootName)
	marker, markerErr := dnsWindowsOpen(markerPath, windows.OPEN_EXISTING, false, sid, attrs)
	first := errors.Is(markerErr, windows.ERROR_FILE_NOT_FOUND)
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
		_, statErr := os.Lstat(root)
		if statErr == nil || !errors.Is(statErr, os.ErrNotExist) {
			return fail(errors.New("machine FakeDNS marker missing for existing or inaccessible root"))
		}
		marker, err = dnsWindowsOpen(markerPath, windows.CREATE_NEW, false, sid, attrs)
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
		p, err := windows.UTF16PtrFromString(root)
		if err != nil {
			return fail(err)
		}
		err = windows.CreateDirectory(p, attrs)
		runtime.KeepAlive(attrs)
		if err != nil {
			return fail(err)
		}
	}
	fake, storage, err := openWindowsDNSStorageWithSecurity(root, direct, sid, attrs)
	if err != nil {
		return fail(err)
	}
	s.storage = storage
	return fake, s, nil
}
