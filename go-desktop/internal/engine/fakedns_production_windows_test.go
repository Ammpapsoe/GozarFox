//go:build windows

package engine

import (
	"errors"
	"fmt"
	"os"
	"path/filepath"
	"testing"

	"golang.org/x/sys/windows"
	"golang.org/x/sys/windows/registry"
)

// This helper is invoked sequentially under two different local administrator
// identities on the disposable runner. The workflow compares SID and address.
func TestFakeDNSCrossUserWindowsHelper(t *testing.T) {
	if os.Getenv("GITHUB_ACTIONS") != "true" || os.Getenv("GOZAR_FAKEDNS_CROSS_USER") != "1" {
		t.Skip("requires disposable cross-account CI")
	}
	user, err := windows.GetCurrentProcessToken().GetTokenUser()
	if err != nil {
		t.Fatal(err)
	}
	fake, owner, err := openProductionMachineFakeDNS(nil)
	if err != nil {
		t.Fatal(err)
	}
	defer owner.Close()
	address, err := fake.address("cross-account.example")
	if err != nil {
		t.Fatal(err)
	}
	fmt.Printf("IDENTITY %s %s\n", user.User.Sid.String(), address.String())
}

// The exact machine-wide path and HKLM marker must be exercised only on a
// disposable CI runner. Refuse to run if either already exists; never delete
// an existing user's state. Ordinary unit tests use isolated HKCU fixtures.
func TestFakeDNSProductionWindowsStorage(t *testing.T) {
	if os.Getenv("GITHUB_ACTIONS") != "true" || os.Getenv("GOZAR_FAKEDNS_PRODUCTION_STORAGE_TEST") != "1" {
		t.Skip("requires disposable Windows CI runner")
	}
	base, err := windows.KnownFolderPath(windows.FOLDERID_ProgramData, windows.KF_FLAG_DEFAULT)
	if err != nil {
		t.Fatal(err)
	}
	root := filepath.Join(base, machineDNSWindowsFolder)
	if _, err = os.Lstat(root); err == nil || !errors.Is(err, os.ErrNotExist) {
		t.Fatal("production FakeDNS folder already exists or cannot be inspected")
	}
	key, err := registry.OpenKey(registry.LOCAL_MACHINE, machineDNSWindowsKey, registry.QUERY_VALUE|registry.WOW64_64KEY)
	if err == nil {
		_ = key.Close()
		t.Fatal("production FakeDNS registry key already exists")
	}
	if !errors.Is(err, registry.ErrNotExist) {
		t.Fatal(err)
	}
	t.Cleanup(func() {
		if err := os.RemoveAll(root); err != nil {
			t.Errorf("cleanup test storage: %v", err)
		}
		if err := registry.DeleteKey(registry.LOCAL_MACHINE, machineDNSWindowsKey); err != nil && !errors.Is(err, registry.ErrNotExist) {
			t.Errorf("cleanup test marker: %v", err)
		}
	})
	fake, owner, err := openProductionMachineFakeDNS(nil)
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() { _ = owner.Close() })
	address, err := fake.address("production-check.example")
	if err != nil {
		t.Fatal(err)
	}
	if _, other, err := openProductionMachineFakeDNS(nil); err == nil {
		_ = other.Close()
		t.Fatal("production machine lock not retained")
	}
	if err = owner.Close(); err != nil {
		t.Fatal(err)
	}
	reopened, next, err := openProductionMachineFakeDNS(nil)
	if err != nil {
		t.Fatal(err)
	}
	defer next.Close()
	got, err := reopened.address("production-check.example")
	if err != nil || got != address {
		t.Fatalf("production identity changed: %s %v", got, err)
	}
}
