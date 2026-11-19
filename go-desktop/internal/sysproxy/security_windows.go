//go:build windows

package sysproxy

import (
	"bytes"
	"errors"
	"fmt"
	"runtime"
	"syscall"
	"unsafe"

	"golang.org/x/sys/windows"
	"golang.org/x/sys/windows/registry"
)

var regCreateKeyExSecure = windows.NewLazySystemDLL("advapi32.dll").NewProc("RegCreateKeyExW")

// userSecurity grants only this Windows account and LocalSystem full access,
// with inheritance disabled. This protects the backup against other users; it
// does not protect against a malicious process running as the same user.
func userSecurity(rights string) (string, *windows.SecurityAttributes, error) {
	user, err := windows.GetCurrentProcessToken().GetTokenUser()
	if err != nil {
		return "", nil, err
	}
	sid := user.User.Sid.String()
	sd, err := windows.SecurityDescriptorFromString("O:" + sid + "D:P(A;;" + rights + ";;;" + sid + ")(A;;" + rights + ";;;SY)")
	if err != nil {
		return "", nil, err
	}
	attrs := &windows.SecurityAttributes{Length: uint32(unsafe.Sizeof(windows.SecurityAttributes{})), SecurityDescriptor: sd}
	return sid, attrs, nil
}

func aclBytes(acl *windows.ACL) ([]byte, error) {
	if acl == nil {
		return nil, errors.New("null DACL")
	}
	p := unsafe.Pointer(acl)
	size := *(*uint16)(unsafe.Add(p, 2))
	if size < 8 || size > 4096 {
		return nil, errors.New("invalid DACL length")
	}
	return unsafe.Slice((*byte)(p), int(size)), nil
}

func verifyUserSecurity(handle windows.Handle, objectType windows.SE_OBJECT_TYPE, sid string, expected *windows.SecurityAttributes) error {
	actual, err := windows.GetSecurityInfo(handle, objectType, windows.OWNER_SECURITY_INFORMATION|windows.DACL_SECURITY_INFORMATION)
	if err != nil {
		return err
	}
	owner, _, err := actual.Owner()
	if err != nil || owner == nil || owner.String() != sid {
		return errors.New("object owner is not current user")
	}
	control, _, err := actual.Control()
	if err != nil || control&windows.SE_DACL_PROTECTED == 0 {
		return errors.New("object DACL is not protected")
	}
	actualACL, _, err := actual.DACL()
	if err != nil {
		return err
	}
	expectedACL, _, err := expected.SecurityDescriptor.DACL()
	if err != nil {
		return err
	}
	a, err := aclBytes(actualACL)
	if err != nil {
		return err
	}
	b, err := aclBytes(expectedACL)
	if err != nil || !bytes.Equal(a, b) {
		return errors.New("object DACL does not match expected per-user policy")
	}
	runtime.KeepAlive(actual)
	runtime.KeepAlive(expected)
	return nil
}

// openSecureJournalKey accepts only a protected per-user key, including when
// the key pre-exists. The caller owns the returned handle.
func openSecureJournalKey(path string, sid string, attrs *windows.SecurityAttributes) (registry.Key, error) {
	if path == "" || sid == "" || attrs == nil || attrs.SecurityDescriptor == nil {
		return 0, errors.New("invalid journal key security arguments")
	}
	if err := regCreateKeyExSecure.Find(); err != nil {
		return 0, err
	}
	encoded, err := windows.UTF16PtrFromString(path)
	if err != nil {
		return 0, err
	}
	var handle windows.Handle
	var disposition uint32
	status, _, _ := regCreateKeyExSecure.Call(uintptr(registry.CURRENT_USER), uintptr(unsafe.Pointer(encoded)), 0, 0, 0,
		uintptr(registry.ALL_ACCESS), uintptr(unsafe.Pointer(attrs)), uintptr(unsafe.Pointer(&handle)), uintptr(unsafe.Pointer(&disposition)))
	runtime.KeepAlive(attrs)
	if status != 0 {
		return 0, syscall.Errno(status)
	}
	if err := verifyUserSecurity(handle, windows.SE_REGISTRY_KEY, sid, attrs); err != nil {
		_ = registry.Key(handle).Close()
		return 0, fmt.Errorf("unsafe proxy journal: %w", err)
	}
	return registry.Key(handle), nil
}
