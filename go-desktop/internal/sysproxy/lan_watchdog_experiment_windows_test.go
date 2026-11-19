//go:build windows && proxyexperiment

package sysproxy

import (
	"bufio"
	"context"
	"fmt"
	"io"
	"os"
	"os/exec"
	"testing"
	"time"
)

func TestLANExperimentSessionChild(t *testing.T) {
	if os.Getenv("GOZAR_LAN_SESSION_CHILD") != "1" {
		t.Skip("child helper only")
	}
	requireDisposableLAN(t)
	session, err := StartExperimentalLANSession(context.Background(), 23456)
	if err != nil {
		t.Fatal(err)
	}
	defer session.Close()
	fmt.Println("SESSION_READY")
	_, _ = io.Copy(io.Discard, os.Stdin)
}

func TestLANExperimentRealWatchdog(t *testing.T) {
	requireDisposableLAN(t)
	store := wininetLANStore{}
	original, err := store.ReadLAN()
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() {
		if err := store.WriteLAN(original); err != nil {
			t.Errorf("restore runner LAN: %v", err)
			return
		}
		got, err := store.ReadLAN()
		if err != nil || got != original {
			t.Errorf("verify runner LAN: %#v, %v", got, err)
		}
	})
	before := LANProxySettings{Flags: 13, Server: "prior.example:8080", Bypass: "*.example", PAC: "https://example.invalid/proxy.pac"}
	if err := store.WriteLAN(before); err != nil {
		t.Fatal(err)
	}
	b, err := openWindowsBackend()
	if err != nil {
		t.Fatal(err)
	}
	defer b.Close()
	j := lanRegistryJournal{b.journal}
	if s, err := j.LoadLAN(); err != nil || s != nil {
		t.Fatalf("unexpected existing LAN journal: %v", err)
	}
	// First test an orderly live session with the actual watchdog child.
	session, err := StartExperimentalLANSession(context.Background(), 23456)
	if err != nil {
		t.Fatal(err)
	}
	if got, err := store.ReadLAN(); err != nil || got != desiredLAN(23456) {
		_ = session.Close()
		t.Fatalf("session did not apply: %#v, %v", got, err)
	}
	if err := session.Close(); err != nil {
		t.Fatal(err)
	}
	if got, err := store.ReadLAN(); err != nil || got != before {
		t.Fatalf("session did not restore: %#v, %v", got, err)
	}
	// Start a new owner process that in turn starts its own watchdog, then
	// kill only that owner. Its orphaned watchdog must autonomously recover.
	executable, err := os.Executable()
	if err != nil {
		t.Fatal(err)
	}
	cmd := exec.Command(executable, "-test.run=^TestLANExperimentSessionChild$")
	cmd.Env = append(os.Environ(), "GOZAR_LAN_SESSION_CHILD=1")
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
		if line != "SESSION_READY\n" && line != "SESSION_READY\r\n" {
			t.Fatalf("owner startup failed: %q", line)
		}
	case <-time.After(30 * time.Second):
		t.Fatal("owner startup timed out")
	}
	if err := cmd.Process.Kill(); err != nil {
		t.Fatal(err)
	}
	_ = cmd.Wait()
	finished = true
	_ = stdin.Close()
	deadline := time.NewTimer(30 * time.Second)
	defer deadline.Stop()
	ticker := time.NewTicker(200 * time.Millisecond)
	defer ticker.Stop()
	for {
		select {
		case <-deadline.C:
			t.Fatal("orphaned watchdog did not restore LAN and clear journal")
		case <-ticker.C:
			ctx, cancel := context.WithTimeout(context.Background(), 3*time.Second)
			completed := false
			err := b.lock(ctx, func(bool) error {
				s, err := j.LoadLAN()
				if err != nil {
					return err
				}
				got, err := store.ReadLAN()
				if err != nil {
					return err
				}
				completed = s == nil && got == before
				return nil
			})
			ctxErr := ctx.Err()
			cancel()
			if err != nil && ctxErr == nil {
				t.Fatal(err)
			}
			if completed {
				return
			}
		}
	}
}
