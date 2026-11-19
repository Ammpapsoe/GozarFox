//go:build windows

package sysproxy

import (
	"context"
	"fmt"
	"os"
	"testing"
	"time"

	"golang.org/x/sys/windows"
	"golang.org/x/sys/windows/registry"
)

func TestSecureJournalKeyHasPerUserACLAndCanReopen(t *testing.T) {
	sid, attrs, err := userSecurity("KA")
	if err != nil {
		t.Fatal(err)
	}
	path := fmt.Sprintf(`Software\Gozar-Secure-Proxy-Test-%d-%d`, os.Getpid(), time.Now().UnixNano())
	key, err := openSecureJournalKey(path, sid, attrs)
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() { _ = registry.DeleteKey(registry.CURRENT_USER, path) })
	if err := key.Close(); err != nil {
		t.Fatal(err)
	}
	key, err = openSecureJournalKey(path, sid, attrs)
	if err != nil {
		t.Fatal(err)
	}
	defer key.Close()
	if err := verifyUserSecurity(windows.Handle(key), windows.SE_REGISTRY_KEY, sid, attrs); err != nil {
		t.Fatal(err)
	}
}

func TestSecureJournalRejectsPreexistingUnprotectedKey(t *testing.T) {
	sid, attrs, err := userSecurity("KA")
	if err != nil {
		t.Fatal(err)
	}
	path := fmt.Sprintf(`Software\Gozar-Unprotected-Proxy-Test-%d-%d`, os.Getpid(), time.Now().UnixNano())
	key, _, err := registry.CreateKey(registry.CURRENT_USER, path, registry.ALL_ACCESS)
	if err != nil {
		t.Fatal(err)
	}
	key.Close()
	t.Cleanup(func() { _ = registry.DeleteKey(registry.CURRENT_USER, path) })
	if key, err := openSecureJournalKey(path, sid, attrs); err == nil {
		key.Close()
		t.Fatal("accepted an existing journal key without the required DACL")
	}
}

func TestSecureGlobalMutexPerUser(t *testing.T) {
	sid, attrs, err := userSecurity("0x001f0001")
	if err != nil {
		t.Fatal(err)
	}
	name := fmt.Sprintf(`Global\Gozar-Proxy-Secure-Test-%s-%d-%d`, sid, os.Getpid(), time.Now().UnixNano())
	verify := func(handle windows.Handle) error {
		return verifyUserSecurity(handle, windows.SE_KERNEL_OBJECT, sid, attrs)
	}
	if err := withWindowsProxyLockSecurity(context.Background(), name, attrs, verify, func(bool) error { return nil }); err != nil {
		t.Fatal(err)
	}
}
