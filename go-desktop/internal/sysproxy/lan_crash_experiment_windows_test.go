//go:build windows && proxyexperiment

package sysproxy

import (
	"bufio"
	"fmt"
	"io"
	"os"
	"os/exec"
	"testing"
	"time"

	"golang.org/x/sys/windows/registry"
)

func requireDisposableLAN(t *testing.T) {
	t.Helper()
	if os.Getenv("GOZAR_DISPOSABLE_WININET_TEST") != "1" || os.Getenv("GITHUB_ACTIONS") != "true" || os.Getenv("RUNNER_ENVIRONMENT") != "github-hosted" {
		t.Fatal("refusing to change WinINet outside the opted-in disposable GitHub-hosted runner")
	}
}

// The parent kills this process at the requested checkpoint. The persisted
// journal is then reopened by the parent, not shared through Go memory.
func TestLANExperimentCrashChild(t *testing.T) {
	phase := os.Getenv("GOZAR_LAN_CHILD_PHASE")
	if phase == "" {
		t.Skip("child helper only")
	}
	requireDisposableLAN(t)
	key, err := registry.OpenKey(registry.CURRENT_USER, os.Getenv("GOZAR_LAN_CHILD_KEY"), registry.QUERY_VALUE|registry.SET_VALUE)
	if err != nil {
		t.Fatal(err)
	}
	defer key.Close()
	j := lanRegistryJournal{registryJournal{key: key}}
	store := wininetLANStore{}
	previous, err := store.ReadLAN()
	if err != nil {
		t.Fatal(err)
	}
	s := lanSnapshot{Version: 1, Port: 23456, Previous: previous, Applied: desiredLAN(23456)}
	switch phase {
	case "journal":
		if err := j.SaveLAN(s); err != nil {
			t.Fatal(err)
		}
	case "applied":
		if err := activateLAN(store, j, 23456); err != nil {
			t.Fatal(err)
		}
	case "restored":
		if err := activateLAN(store, j, 23456); err != nil {
			t.Fatal(err)
		}
		if err := store.WriteLAN(previous); err != nil {
			t.Fatal(err)
		}
	default:
		t.Fatal("invalid child checkpoint")
	}
	fmt.Println("READY")
	_, _ = io.Copy(io.Discard, os.Stdin)
}

func TestLANExperimentRealCrash(t *testing.T) {
	requireDisposableLAN(t)
	store := wininetLANStore{}
	original, err := store.ReadLAN()
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() {
		if err := store.WriteLAN(original); err != nil {
			t.Errorf("restore runner setting: %v", err)
			return
		}
		got, err := store.ReadLAN()
		if err != nil || got != original {
			t.Errorf("verify runner setting: %#v, %v", got, err)
		}
	})
	for _, phase := range []string{"journal", "applied", "restored", "conflict"} {
		t.Run(phase, func(t *testing.T) {
			before := LANProxySettings{Flags: 13, Server: "prior.example:8080", Bypass: "*.example", PAC: "https://example.invalid/proxy.pac"}
			if err := store.WriteLAN(before); err != nil {
				t.Fatal(err)
			}
			path := fmt.Sprintf(`Software\Gozar-LAN-Crash-Test-%d-%d`, os.Getpid(), time.Now().UnixNano())
			key, existed, err := registry.CreateKey(registry.CURRENT_USER, path, registry.ALL_ACCESS)
			if err != nil || existed {
				t.Fatalf("create isolated journal: existed=%v err=%v", existed, err)
			}
			t.Cleanup(func() {
				key.Close()
				if err := registry.DeleteKey(registry.CURRENT_USER, path); err != nil {
					t.Errorf("delete test key: %v", err)
				}
			})
			executable, err := os.Executable()
			if err != nil {
				t.Fatal(err)
			}
			childPhase := phase
			if phase == "conflict" {
				childPhase = "applied"
			}
			cmd := exec.Command(executable, "-test.run=^TestLANExperimentCrashChild$")
			cmd.Env = append(os.Environ(), "GOZAR_LAN_CHILD_KEY="+path, "GOZAR_LAN_CHILD_PHASE="+childPhase)
			stdin, err := cmd.StdinPipe()
			if err != nil {
				t.Fatal(err)
			}
			out, err := cmd.StdoutPipe()
			if err != nil {
				t.Fatal(err)
			}
			cmd.Stderr = os.Stderr
			if err := cmd.Start(); err != nil {
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
			go func() { line, _ := bufio.NewReader(out).ReadString('\n'); ready <- line }()
			select {
			case line := <-ready:
				if line != "READY\n" && line != "READY\r\n" {
					t.Fatalf("child did not reach checkpoint: %q", line)
				}
			case <-time.After(30 * time.Second):
				t.Fatal("child checkpoint timed out")
			}
			if err := cmd.Process.Kill(); err != nil {
				t.Fatal(err)
			}
			_ = cmd.Wait()
			finished = true
			_ = stdin.Close()
			// Use a new handle after the child's death, as a later process would.
			reopened, err := registry.OpenKey(registry.CURRENT_USER, path, registry.QUERY_VALUE|registry.SET_VALUE)
			if err != nil {
				t.Fatal(err)
			}
			defer reopened.Close()
			j := lanRegistryJournal{registryJournal{key: reopened}}
			if s, err := j.LoadLAN(); err != nil || s == nil || s.Previous != before {
				t.Fatalf("journal not durable: %#v, %v", s, err)
			}
			if phase == "conflict" {
				other := LANProxySettings{Flags: 3, Server: "other.invalid:5555"}
				if err := store.WriteLAN(other); err != nil {
					t.Fatal(err)
				}
				if err := recoverLAN(store, j); err == nil {
					t.Fatal("post-crash conflict was not detected")
				}
				got, err := store.ReadLAN()
				if err != nil || got != other {
					t.Fatalf("conflict was overwritten: %#v, %v", got, err)
				}
				if s, err := j.LoadLAN(); err != nil || s == nil {
					t.Fatal("conflict lost journal", err)
				}
				if err := store.WriteLAN(desiredLAN(23456)); err != nil {
					t.Fatal(err)
				}
			}
			if err := recoverLAN(store, j); err != nil {
				t.Fatal(err)
			}
			got, err := store.ReadLAN()
			if err != nil || got != before {
				t.Fatalf("crash restore: %#v, %v", got, err)
			}
			if s, err := j.LoadLAN(); err != nil || s != nil {
				t.Fatal("recovery did not clear journal", err)
			}
		})
	}
}
