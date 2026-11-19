//go:build windows

package engine

import (
	"bytes"
	"errors"
	"fmt"
	"os"
	"path/filepath"
	"runtime"
	"syscall"
	"unsafe"

	"golang.org/x/sys/windows"
	"golang.org/x/sys/windows/registry"
)

const (
	machineDNSWindowsFolder = "GozarFox-FakeDNS-Machine-v1"
	machineDNSWindowsKey    = `SOFTWARE\GozarFox\FakeDNSMachineV1`
	machineDNSWindowsLock   = `Global\GozarFox-FakeDNS-Provision-v1`
	machineDNSWindowsValue  = "Initialized"
)

var windowsMachineProvisionMarker = []byte("GozarFox machine FakeDNS provisioned v1\n")
var dnsRegCreateKeyEx = windows.NewLazySystemDLL("advapi32.dll").NewProc("RegCreateKeyExW")
var dnsRegFlushKey = windows.NewLazySystemDLL("advapi32.dll").NewProc("RegFlushKey")

func dnsWindowsMachineAttributes(rights string) (string, *windows.SecurityAttributes, error) {
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
	sd, err := windows.SecurityDescriptorFromString("O:BAD:P(A;;" + rights + ";;;BA)(A;;" + rights + ";;;SY)")
	if err != nil {
		return "", nil, err
	}
	return admin.String(), &windows.SecurityAttributes{Length: uint32(unsafe.Sizeof(windows.SecurityAttributes{})), SecurityDescriptor: sd}, nil
}

func dnsOpenMachineKey(hive registry.Key, path, sid string, attrs *windows.SecurityAttributes) (registry.Key, bool, error) {
	if err := dnsRegCreateKeyEx.Find(); err != nil {
		return 0, false, err
	}
	p, err := windows.UTF16PtrFromString(path)
	if err != nil {
		return 0, false, err
	}
	var handle windows.Handle
	var disposition uint32
	status, _, _ := dnsRegCreateKeyEx.Call(uintptr(hive), uintptr(unsafe.Pointer(p)), 0, 0, 0, uintptr(registry.ALL_ACCESS|registry.WOW64_64KEY), uintptr(unsafe.Pointer(attrs)), uintptr(unsafe.Pointer(&handle)), uintptr(unsafe.Pointer(&disposition)))
	runtime.KeepAlive(attrs)
	if status != 0 {
		return 0, false, syscall.Errno(status)
	}
	key := registry.Key(handle)
	if err := dnsWindowsVerifySecurity(handle, windows.SE_REGISTRY_KEY, sid, attrs); err != nil {
		_ = key.Close()
		return 0, false, fmt.Errorf("unsafe machine FakeDNS registry marker: %w", err)
	}
	if disposition != 1 && disposition != 2 {
		_ = key.Close()
		return 0, false, errors.New("unknown machine FakeDNS registry disposition")
	}
	return key, disposition == 1, nil
}

func dnsFlushMachineKey(key registry.Key) error {
	if err := dnsRegFlushKey.Find(); err != nil {
		return err
	}
	status, _, _ := dnsRegFlushKey.Call(uintptr(key))
	if status != 0 {
		return syscall.Errno(status)
	}
	return nil
}

// The provision mutex is owned by its OS thread; the caller must release it
// before unlocking the thread. The subsequent long-lived lock is a file handle.
func withDNSWindowsProvisionLock(name string, fn func() error) (result error) {
	if name == "" || fn == nil {
		return errors.New("invalid machine FakeDNS provisioning lock")
	}
	sid, attrs, err := dnsWindowsMachineAttributes("0x001f0001")
	if err != nil {
		return err
	}
	p, err := windows.UTF16PtrFromString(name)
	if err != nil {
		return err
	}
	runtime.LockOSThread()
	defer runtime.UnlockOSThread()
	h, err := windows.CreateMutex(attrs, false, p)
	runtime.KeepAlive(attrs)
	if err != nil && !errors.Is(err, windows.ERROR_ALREADY_EXISTS) {
		return err
	}
	if h == 0 {
		return errors.New("invalid machine FakeDNS mutex")
	}
	defer func() { result = errors.Join(result, windows.CloseHandle(h)) }()
	if err = dnsWindowsVerifySecurity(h, windows.SE_KERNEL_OBJECT, sid, attrs); err != nil {
		return fmt.Errorf("unsafe machine FakeDNS mutex: %w", err)
	}
	state, err := windows.WaitForSingleObject(h, 0)
	if err != nil {
		return err
	}
	if state != windows.WAIT_OBJECT_0 && state != windows.WAIT_ABANDONED {
		return errors.New("machine FakeDNS provisioning in use")
	}
	defer func() { result = errors.Join(result, windows.ReleaseMutex(h)) }()
	return fn()
}

// The protected registry marker is flushed BEFORE creating the machine folder.
// It prevents a removed folder from being mistaken for first use. Deleting both
// the marker key and folder as an administrator remains out of scope. Test
// callers use an isolated HKCU key and folder; production must use fixed HKLM.
func provisionWindowsMachineDNS(base string, hive registry.Key, keyPath, mutexName string, direct func(string) bool) (*fakeDNS, *windowsMachineDNSStorage, error) {
	if !filepath.IsAbs(base) || keyPath == "" || mutexName == "" {
		return nil, nil, errors.New("invalid machine FakeDNS provisioning parameters")
	}
	var fake *fakeDNS
	var storage *windowsMachineDNSStorage
	err := withDNSWindowsProvisionLock(mutexName, func() error {
		sid, keyAttrs, err := dnsWindowsMachineAttributes("KA")
		if err != nil {
			return err
		}
		key, created, err := dnsOpenMachineKey(hive, keyPath, sid, keyAttrs)
		if err != nil {
			return err
		}
		defer key.Close()
		root := filepath.Join(base, machineDNSWindowsFolder)
		if created {
			_, statErr := os.Lstat(root)
			if statErr == nil || !errors.Is(statErr, os.ErrNotExist) {
				return errors.New("machine FakeDNS registry marker missing for existing or inaccessible folder")
			}
			if err = key.SetBinaryValue(machineDNSWindowsValue, windowsMachineProvisionMarker); err != nil {
				return err
			}
			if err = dnsFlushMachineKey(key); err != nil {
				return err
			}
			_, fileAttrs, err := dnsWindowsMachineSecurity()
			if err != nil {
				return err
			}
			p, err := windows.UTF16PtrFromString(root)
			if err != nil {
				return err
			}
			err = windows.CreateDirectory(p, fileAttrs)
			runtime.KeepAlive(fileAttrs)
			if err != nil {
				return err
			}
		} else {
			n, kind, err := key.GetValue(machineDNSWindowsValue, nil)
			if err != nil || kind != registry.BINARY || n != len(windowsMachineProvisionMarker) {
				return errors.New("missing or invalid machine FakeDNS registry marker")
			}
			data := make([]byte, n)
			n, kind, err = key.GetValue(machineDNSWindowsValue, data)
			if err != nil || kind != registry.BINARY || n != len(data) || !bytes.Equal(data, windowsMachineProvisionMarker) {
				return errors.New("damaged machine FakeDNS registry marker")
			}
		}
		fake, storage, err = openWindowsMachineDNSStorage(root, direct)
		return err
	})
	if err != nil {
		if storage != nil {
			_ = storage.Close()
		}
		return nil, nil, err
	}
	return fake, storage, nil
}

func openProductionWindowsMachineDNS(direct func(string) bool) (*fakeDNS, *windowsMachineDNSStorage, error) {
	base, err := windows.KnownFolderPath(windows.FOLDERID_ProgramData, windows.KF_FLAG_DEFAULT)
	if err != nil {
		return nil, nil, err
	}
	return provisionWindowsMachineDNS(base, registry.LOCAL_MACHINE, machineDNSWindowsKey, machineDNSWindowsLock, direct)
}
