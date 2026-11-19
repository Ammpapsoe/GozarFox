//go:build windows

package engine

import (
	"fmt"
	"os"
	"path/filepath"
	"runtime"
	"testing"
	"time"

	"golang.org/x/sys/windows"
	"golang.org/x/sys/windows/registry"
)

type dnsWindowsProvisionFixture struct{ base, keyPath, mutex string }

func newDNSWindowsProvisionFixture(t *testing.T) dnsWindowsProvisionFixture {
	t.Helper()
	id := fmt.Sprintf("%d-%d", os.Getpid(), time.Now().UnixNano())
	f := dnsWindowsProvisionFixture{base: t.TempDir(), keyPath: `Software\GozarFox-FakeDNS-CI\` + id, mutex: `Global\GozarFox-FakeDNS-CI-` + id}
	t.Cleanup(func() { _ = registry.DeleteKey(registry.CURRENT_USER, f.keyPath) })
	return f
}
func (f dnsWindowsProvisionFixture) open() (*fakeDNS, *windowsMachineDNSStorage, error) {
	return provisionWindowsMachineDNS(f.base, registry.CURRENT_USER, f.keyPath, f.mutex, nil)
}

func TestFakeDNSProvisionWindowsStableAndLocked(t *testing.T) {
	f := newDNSWindowsProvisionFixture(t)
	first, owner, err := f.open()
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() { _ = owner.Close() })
	one, err := first.address("provision.example")
	if err != nil {
		t.Fatal(err)
	}
	if _, other, err := f.open(); err == nil {
		_ = other.Close()
		t.Fatal("second owner succeeded")
	}
	if err = owner.Close(); err != nil {
		t.Fatal(err)
	}
	second, next, err := f.open()
	if err != nil {
		t.Fatal(err)
	}
	defer next.Close()
	got, err := second.address("provision.example")
	if err != nil || one != got {
		t.Fatalf("identity changed: %s %v", got, err)
	}
}

func TestFakeDNSProvisionWindowsRefusesMissingMarkerOrRoot(t *testing.T) {
	for _, scenario := range []string{"missing root", "missing marker", "corrupt marker", "missing key", "unsafe registry key"} {
		t.Run(scenario, func(t *testing.T) {
			f := newDNSWindowsProvisionFixture(t)
			_, owner, err := f.open()
			if err != nil {
				t.Fatal(err)
			}
			if err = owner.Close(); err != nil {
				t.Fatal(err)
			}
			root := filepath.Join(f.base, machineDNSWindowsFolder)
			switch scenario {
			case "missing root":
				err = os.RemoveAll(root)
			case "missing key":
				err = registry.DeleteKey(registry.CURRENT_USER, f.keyPath)
			case "missing marker", "corrupt marker":
				key, e := registry.OpenKey(registry.CURRENT_USER, f.keyPath, registry.ALL_ACCESS|registry.WOW64_64KEY)
				if e != nil {
					t.Fatal(e)
				}
				if scenario == "missing marker" {
					err = key.DeleteValue(machineDNSWindowsValue)
				} else {
					err = key.SetBinaryValue(machineDNSWindowsValue, []byte("bad"))
				}
				_ = key.Close()
			case "unsafe registry key":
				key, e := registry.OpenKey(registry.CURRENT_USER, f.keyPath, registry.ALL_ACCESS|registry.WOW64_64KEY)
				if e != nil {
					t.Fatal(e)
				}
				sd, e := windows.SecurityDescriptorFromString("D:P(A;;KA;;;WD)")
				if e != nil {
					t.Fatal(e)
				}
				acl, _, e := sd.DACL()
				if e != nil {
					t.Fatal(e)
				}
				err = windows.SetSecurityInfo(windows.Handle(key), windows.SE_REGISTRY_KEY, windows.DACL_SECURITY_INFORMATION|windows.PROTECTED_DACL_SECURITY_INFORMATION, nil, nil, acl, nil)
				runtime.KeepAlive(sd)
				_ = key.Close()
			}
			if err != nil {
				t.Fatal(err)
			}
			if _, next, err := f.open(); err == nil {
				_ = next.Close()
				t.Fatal("accepted missing or unsafe marker/root")
			}
		})
	}
}

func TestFakeDNSProvisionWindowsExistingFolderIsNotFirstUse(t *testing.T) {
	f := newDNSWindowsProvisionFixture(t)
	if err := os.Mkdir(filepath.Join(f.base, machineDNSWindowsFolder), 0700); err != nil {
		t.Fatal(err)
	}
	if _, owner, err := f.open(); err == nil {
		_ = owner.Close()
		t.Fatal("existing unmarked folder was accepted")
	}
}
