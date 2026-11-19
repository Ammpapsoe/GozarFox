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

func windowsPrivateTestRoot(t *testing.T) string {
	t.Helper()
	root := filepath.Join(t.TempDir(), "private")
	_, attrs, err := dnsWindowsSecurity()
	if err != nil {
		t.Fatal(err)
	}
	p, err := windows.UTF16PtrFromString(root)
	if err != nil {
		t.Fatal(err)
	}
	err = windows.CreateDirectory(p, attrs)
	runtime.KeepAlive(attrs)
	if err != nil {
		t.Fatal(err)
	}
	return root
}

func platformFakeDNSStorage(t *testing.T) func() (*fakeDNS, io.Closer, error) {
	t.Helper()
	root := windowsPrivateTestRoot(t)
	return func() (*fakeDNS, io.Closer, error) {
		fake, owner, err := openWindowsDNSStorage(root, nil)
		return fake, owner, err
	}
}

func TestFakeDNSStorageWindowsIdentityAndLock(t *testing.T) {
	root := windowsPrivateTestRoot(t)
	f, session, err := openWindowsDNSStorage(root, nil)
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() { _ = session.Close() })
	address, err := f.address("stable.example")
	if err != nil {
		t.Fatal(err)
	}
	if _, other, err := openWindowsDNSStorage(root, nil); err == nil {
		_ = other.Close()
		t.Fatal("second owner acquired lock")
	}
	if err := session.Close(); err != nil {
		t.Fatal(err)
	}
	reopened, next, err := openWindowsDNSStorage(root, nil)
	if err != nil {
		t.Fatal(err)
	}
	defer next.Close()
	got, err := reopened.address("stable.example")
	if err != nil || got != address {
		t.Fatalf("identity changed: %s %v", got, err)
	}
}

func TestFakeDNSStorageWindowsRefusesUnsafeHistory(t *testing.T) {
	for _, scenario := range []string{"missing history", "missing checkpoint", "empty history", "missing directory", "missing marker", "truncated marker", "hardlink", "symlink", "unsafe file"} {
		t.Run(scenario, func(t *testing.T) {
			root := windowsPrivateTestRoot(t)
			_, session, err := openWindowsDNSStorage(root, nil)
			if err != nil {
				t.Fatal(err)
			}
			if err := session.Close(); err != nil {
				t.Fatal(err)
			}
			storage := filepath.Join(root, dnsDirectoryName)
			history := filepath.Join(storage, dnsHistoryName)
			switch scenario {
			case "missing history":
				err = os.Remove(history)
			case "missing checkpoint":
				err = os.Remove(filepath.Join(storage, dnsCheckpointName))
			case "empty history":
				err = os.Truncate(history, 0)
				if err == nil {
					err = os.Truncate(filepath.Join(storage, dnsCheckpointName), 0)
				}
			case "missing directory":
				err = os.RemoveAll(storage)
			case "missing marker":
				err = os.Remove(filepath.Join(root, dnsMarkerName))
			case "truncated marker":
				err = os.Truncate(filepath.Join(root, dnsMarkerName), 4)
			case "hardlink":
				err = os.Link(history, filepath.Join(root, "history-link"))
			case "symlink":
				err = os.Remove(history)
				if err == nil {
					err = os.Symlink(filepath.Join(root, dnsMarkerName), history)
				}
				if err != nil {
					t.Skipf("symlink creation unavailable: %v", err)
				}
			case "unsafe file":
				var sd *windows.SECURITY_DESCRIPTOR
				sd, err = windows.SecurityDescriptorFromString("D:P(A;;FA;;;WD)")
				if err == nil {
					acl, _, e := sd.DACL()
					err = e
					if err == nil {
						err = windows.SetNamedSecurityInfo(history, windows.SE_FILE_OBJECT, windows.DACL_SECURITY_INFORMATION|windows.PROTECTED_DACL_SECURITY_INFORMATION, nil, nil, acl, nil)
					}
					runtime.KeepAlive(sd)
				}
			}
			if err != nil {
				t.Fatal(err)
			}
			if _, next, err := openWindowsDNSStorage(root, nil); err == nil {
				_ = next.Close()
				t.Fatal("accepted unsafe or missing history")
			}
		})
	}
}

func TestFakeDNSStorageWindowsRejectsUnsafeRoot(t *testing.T) {
	root := t.TempDir()
	if _, next, err := openWindowsDNSStorage(root, nil); err == nil {
		_ = next.Close()
		t.Fatal("accepted inherited DACL")
	}
}

func TestFakeDNSStorageWindowsCrashChild(t *testing.T) {
	root := os.Getenv("GOZAR_FAKE_DNS_WIN_CHILD")
	if root == "" {
		t.Skip("child helper only")
	}
	f, session, err := openWindowsDNSStorage(root, nil)
	if err != nil {
		t.Fatal(err)
	}
	defer session.Close()
	address, err := f.address("crash.example")
	if err != nil {
		t.Fatal(err)
	}
	fmt.Println("READY " + address.String())
	_, _ = io.Copy(io.Discard, os.Stdin)
}

func TestFakeDNSStorageWindowsReopensAfterOwnerKilled(t *testing.T) {
	root := windowsPrivateTestRoot(t)
	exe, err := os.Executable()
	if err != nil {
		t.Fatal(err)
	}
	cmd := exec.Command(exe, "-test.run=^TestFakeDNSStorageWindowsCrashChild$")
	cmd.Env = append(os.Environ(), "GOZAR_FAKE_DNS_WIN_CHILD="+root)
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
		scan := bufio.NewScanner(stdout)
		if scan.Scan() {
			ready <- scan.Text()
		} else {
			ready <- ""
		}
	}()
	var expected string
	select {
	case line := <-ready:
		if !strings.HasPrefix(line, "READY ") {
			t.Fatalf("child did not start: %q", line)
		}
		expected = strings.TrimPrefix(line, "READY ")
	case <-time.After(20 * time.Second):
		t.Fatal("child timed out")
	}
	if _, other, err := openWindowsDNSStorage(root, nil); err == nil {
		_ = other.Close()
		t.Fatal("active child did not hold lock")
	}
	if err := cmd.Process.Kill(); err != nil {
		t.Fatal(err)
	}
	_ = cmd.Wait()
	finished = true
	_ = stdin.Close()
	f, session, err := openWindowsDNSStorage(root, nil)
	if err != nil {
		t.Fatal(err)
	}
	defer session.Close()
	address, err := f.address("crash.example")
	if err != nil || address.String() != expected {
		t.Fatalf("identity changed after owner death: %s %v", address, err)
	}
}
