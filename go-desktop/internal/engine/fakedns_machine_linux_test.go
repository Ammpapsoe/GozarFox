//go:build linux

package engine

import (
	"bufio"
	"fmt"
	"io"
	"os"
	"os/exec"
	"path/filepath"
	"strings"
	"testing"
	"time"
)

func TestFakeDNSMachineLinuxStableIdentityAndLock(t *testing.T) {
	parent := t.TempDir()
	t.Setenv("HOME", filepath.Join(parent, "user-one"))
	first, owner, err := openLinuxMachineDNSStorage(parent, nil)
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() { _ = owner.Close() })
	one, err := first.address("first.example")
	if err != nil {
		t.Fatal(err)
	}
	if _, other, err := openLinuxMachineDNSStorage(parent, nil); err == nil {
		_ = other.Close()
		t.Fatal("parallel session got machine lock")
	}
	if err = owner.Close(); err != nil {
		t.Fatal(err)
	}
	t.Setenv("HOME", filepath.Join(parent, "user-two"))
	second, next, err := openLinuxMachineDNSStorage(parent, nil)
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

func TestFakeDNSMachineLinuxRefusesAmbiguousStorage(t *testing.T) {
	for _, scenario := range []string{"missing root", "missing marker", "corrupt marker", "marker symlink", "marker hardlink", "unsafe parent", "unsafe root"} {
		t.Run(scenario, func(t *testing.T) {
			parent := t.TempDir()
			_, owner, err := openLinuxMachineDNSStorage(parent, nil)
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
			case "marker symlink":
				err = os.Remove(marker)
				if err == nil {
					err = os.Symlink(filepath.Join(root, dnsMarkerName), marker)
				}
			case "marker hardlink":
				err = os.Link(marker, filepath.Join(parent, "marker-link"))
			case "unsafe parent":
				err = os.Chmod(parent, 0777)
			case "unsafe root":
				err = os.Chmod(root, 0755)
			}
			if err != nil {
				t.Fatal(err)
			}
			if _, next, err := openLinuxMachineDNSStorage(parent, nil); err == nil {
				_ = next.Close()
				t.Fatal("accepted ambiguous or unsafe machine storage")
			}
		})
	}
}

func TestFakeDNSMachineLinuxCrashChild(t *testing.T) {
	parent := os.Getenv("GOZAR_FAKE_DNS_MACHINE_CHILD")
	if parent == "" {
		t.Skip("child helper only")
	}
	fake, owner, err := openLinuxMachineDNSStorage(parent, nil)
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

func TestFakeDNSMachineLinuxOwnerKilled(t *testing.T) {
	parent := t.TempDir()
	exe, err := os.Executable()
	if err != nil {
		t.Fatal(err)
	}
	cmd := exec.Command(exe, "-test.run=^TestFakeDNSMachineLinuxCrashChild$")
	cmd.Env = append(os.Environ(), "GOZAR_FAKE_DNS_MACHINE_CHILD="+parent)
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
	if _, other, err := openLinuxMachineDNSStorage(parent, nil); err == nil {
		_ = other.Close()
		t.Fatal("child lock not held")
	}
	if err = cmd.Process.Kill(); err != nil {
		t.Fatal(err)
	}
	_ = cmd.Wait()
	finished = true
	_ = stdin.Close()
	fake, owner, err := openLinuxMachineDNSStorage(parent, nil)
	if err != nil {
		t.Fatal(err)
	}
	defer owner.Close()
	got, err := fake.address("machine-crash.example")
	if err != nil || got.String() != expected {
		t.Fatalf("identity changed after kill: %s %v", got, err)
	}
}
