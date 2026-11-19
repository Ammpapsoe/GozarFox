//go:build windows

package engine

import (
	"bufio"
	"fmt"
	"io"
	"os"
	"os/exec"
	"path/filepath"
	"runtime"
	"strings"
	"testing"
	"time"

	"golang.org/x/sys/windows"
)

func windowsMachineTestParent(t *testing.T) string {
	t.Helper()
	path := filepath.Join(t.TempDir(), "machine")
	_, attrs, err := dnsWindowsMachineSecurity()
	if err != nil {
		t.Fatal(err)
	}
	p, err := windows.UTF16PtrFromString(path)
	if err != nil {
		t.Fatal(err)
	}
	err = windows.CreateDirectory(p, attrs)
	runtime.KeepAlive(attrs)
	if err != nil {
		t.Fatal(err)
	}
	return path
}

func TestFakeDNSMachineWindowsStableIdentityAndLock(t *testing.T) {
	parent := windowsMachineTestParent(t)
	t.Setenv("USERPROFILE", filepath.Join(parent, "user-one"))
	first, owner, err := openWindowsMachineDNSStorage(parent, nil)
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() { _ = owner.Close() })
	one, err := first.address("first.example")
	if err != nil {
		t.Fatal(err)
	}
	if _, other, err := openWindowsMachineDNSStorage(parent, nil); err == nil {
		_ = other.Close()
		t.Fatal("parallel session got machine lock")
	}
	if err = owner.Close(); err != nil {
		t.Fatal(err)
	}
	t.Setenv("USERPROFILE", filepath.Join(parent, "user-two"))
	second, next, err := openWindowsMachineDNSStorage(parent, nil)
	if err != nil {
		t.Fatal(err)
	}
	defer next.Close()
	got, err := second.address("first.example")
	if err != nil || got != one {
		t.Fatalf("identity changed with user environment: %s %v", got, err)
	}
	two, err := second.address("second.example")
	if err != nil || one == two {
		t.Fatal("machine address reused", err)
	}
}

func TestFakeDNSMachineWindowsRefusesAmbiguousStorage(t *testing.T) {
	for _, scenario := range []string{"missing root", "missing marker", "corrupt marker", "marker hardlink", "marker symlink", "unsafe parent", "unsafe root"} {
		t.Run(scenario, func(t *testing.T) {
			parent := windowsMachineTestParent(t)
			_, owner, err := openWindowsMachineDNSStorage(parent, nil)
			if err != nil {
				t.Fatal(err)
			}
			if err = owner.Close(); err != nil {
				t.Fatal(err)
			}
			root := filepath.Join(parent, machineDNSRootName)
			marker := filepath.Join(parent, machineDNSMarkerName)
			switch scenario {
			case "missing root":
				err = os.RemoveAll(root)
			case "missing marker":
				err = os.Remove(marker)
			case "corrupt marker":
				err = os.Truncate(marker, 3)
			case "marker hardlink":
				err = os.Link(marker, filepath.Join(parent, "marker-link"))
			case "marker symlink":
				err = os.Remove(marker)
				if err == nil {
					err = os.Symlink(filepath.Join(root, dnsMarkerName), marker)
				}
				if err != nil {
					t.Skipf("symlink creation unavailable: %v", err)
				}
			case "unsafe parent", "unsafe root":
				target := root
				if scenario == "unsafe parent" {
					target = parent
				}
				var sd *windows.SECURITY_DESCRIPTOR
				sd, err = windows.SecurityDescriptorFromString("D:P(A;;FA;;;WD)")
				if err == nil {
					acl, _, e := sd.DACL()
					err = e
					if err == nil {
						err = windows.SetNamedSecurityInfo(target, windows.SE_FILE_OBJECT, windows.DACL_SECURITY_INFORMATION|windows.PROTECTED_DACL_SECURITY_INFORMATION, nil, nil, acl, nil)
					}
					runtime.KeepAlive(sd)
				}
			}
			if err != nil {
				t.Fatal(err)
			}
			if _, next, err := openWindowsMachineDNSStorage(parent, nil); err == nil {
				_ = next.Close()
				t.Fatal("accepted ambiguous or unsafe machine storage")
			}
		})
	}
}

func TestFakeDNSMachineWindowsCrashChild(t *testing.T) {
	parent := os.Getenv("GOZAR_FAKE_DNS_WIN_MACHINE_CHILD")
	if parent == "" {
		t.Skip("child helper only")
	}
	fake, owner, err := openWindowsMachineDNSStorage(parent, nil)
	if err != nil {
		t.Fatal(err)
	}
	defer owner.Close()
	address, err := fake.address("machine-crash.example")
	if err != nil {
		t.Fatal(err)
	}
	fmt.Println("READY " + address.String())
	_, _ = io.Copy(io.Discard, os.Stdin)
}

func TestFakeDNSMachineWindowsOwnerKilled(t *testing.T) {
	parent := windowsMachineTestParent(t)
	exe, err := os.Executable()
	if err != nil {
		t.Fatal(err)
	}
	cmd := exec.Command(exe, "-test.run=^TestFakeDNSMachineWindowsCrashChild$")
	cmd.Env = append(os.Environ(), "GOZAR_FAKE_DNS_WIN_MACHINE_CHILD="+parent)
	stdin, err := cmd.StdinPipe()
	if err != nil {
		t.Fatal(err)
	}
	stdout, err := cmd.StdoutPipe()
	if err != nil {
		t.Fatal(err)
	}
	cmd.Stderr = os.Stderr
	if err = cmd.Start(); err != nil {
		t.Fatal(err)
	}
	finished := false
	t.Cleanup(func() {
		_ = stdin.Close()
		if !finished {
			_ = cmd.Process.Kill()
			_ = cmd.Wait()
		}
	})
	ready := make(chan string, 1)
	go func() {
		scanner := bufio.NewScanner(stdout)
		if scanner.Scan() {
			ready <- scanner.Text()
		} else {
			ready <- ""
		}
	}()
	var expected string
	select {
	case line := <-ready:
		if !strings.HasPrefix(line, "READY ") {
			t.Fatalf("child failed: %q", line)
		}
		expected = strings.TrimPrefix(line, "READY ")
	case <-time.After(20 * time.Second):
		t.Fatal("child timed out")
	}
	if _, other, err := openWindowsMachineDNSStorage(parent, nil); err == nil {
		_ = other.Close()
		t.Fatal("child lock not held")
	}
	if err = cmd.Process.Kill(); err != nil {
		t.Fatal(err)
	}
	_ = cmd.Wait()
	finished = true
	_ = stdin.Close()
	fake, owner, err := openWindowsMachineDNSStorage(parent, nil)
	if err != nil {
		t.Fatal(err)
	}
	defer owner.Close()
	got, err := fake.address("machine-crash.example")
	if err != nil || got.String() != expected {
		t.Fatalf("identity changed after kill: %s %v", got, err)
	}
}
