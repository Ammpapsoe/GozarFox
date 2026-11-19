//go:build linux

package engine

import (
	"errors"
	"os"
	"path/filepath"
	"testing"
)

// Requires CI to bind a fresh private directory over /var/lib inside a mount
// namespace. Never run against a real machine's production store.
func TestFakeDNSProductionLinuxStorage(t *testing.T) {
	if os.Getenv("GITHUB_ACTIONS") != "true" || os.Getenv("GOZAR_FAKEDNS_PRODUCTION_STORAGE_TEST") != "1" {
		t.Skip("requires disposable mount namespace")
	}
	for _, name := range []string{machineDNSMarkerName, machineDNSRootName, machineDNSLockName} {
		if _, err := os.Lstat(filepath.Join("/var/lib", name)); err == nil || !errors.Is(err, os.ErrNotExist) {
			t.Fatalf("test namespace is not empty: %s", name)
		}
	}
	fake, owner, err := openProductionMachineFakeDNS(nil)
	if err != nil {
		t.Fatal(err)
	}
	defer owner.Close()
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
