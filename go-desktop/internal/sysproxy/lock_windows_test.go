//go:build windows

package sysproxy

import (
	"bufio"
	"context"
	"errors"
	"fmt"
	"os"
	"os/exec"
	"strings"
	"testing"
	"time"

	"golang.org/x/sys/windows"
)

const testMutexPrefix = `Local\GozarProxyTest-`

func TestWindowsProxyLockChild(t *testing.T) {
	if os.Getenv("GOZAR_LOCK_CHILD") != "1" {
		t.Skip("child process only")
	}
	name := os.Getenv("GOZAR_TEST_MUTEX")
	if !strings.HasPrefix(name, testMutexPrefix) {
		t.Fatal("invalid isolated mutex name")
	}
	if err := withWindowsProxyLock(context.Background(), name, func(abandoned bool) error {
		if abandoned {
			return errors.New("unexpected abandoned test mutex")
		}
		fmt.Println("MUTEX_HELD")
		time.Sleep(time.Minute)
		return errors.New("parent did not terminate child")
	}); err != nil {
		t.Fatal(err)
	}
}

func TestWindowsProxyLockCrossProcessAndAbandonment(t *testing.T) {
	name := fmt.Sprintf("%s%d-%d", testMutexPrefix, os.Getpid(), time.Now().UnixNano())
	encoded, err := windows.UTF16PtrFromString(name)
	if err != nil {
		t.Fatal(err)
	}
	// Retain a non-owning handle so the named object remains alive after the
	// child dies; otherwise Windows may delete the abandoned object outright.
	keeper, err := windows.CreateMutex(nil, false, encoded)
	if err != nil {
		t.Fatal(err)
	}
	defer windows.CloseHandle(keeper)
	cmd := exec.Command(os.Args[0], "-test.run=^TestWindowsProxyLockChild$", "-test.timeout=45s")
	cmd.Env = append(os.Environ(), "GOZAR_LOCK_CHILD=1", "GOZAR_TEST_MUTEX="+name)
	out, err := cmd.StdoutPipe()
	if err != nil {
		t.Fatal(err)
	}
	cmd.Stderr = os.Stderr
	if err := cmd.Start(); err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() { _ = cmd.Process.Kill(); _ = cmd.Wait() })
	lines := make(chan string, 1)
	go func() {
		scanner := bufio.NewScanner(out)
		if scanner.Scan() {
			lines <- scanner.Text()
		} else {
			lines <- ""
		}
	}()
	select {
	case line := <-lines:
		if line != "MUTEX_HELD" {
			t.Fatalf("child did not acquire mutex: %q", line)
		}
	case <-time.After(20 * time.Second):
		t.Fatal("child did not acquire mutex in time")
	}
	ctx, cancel := context.WithTimeout(context.Background(), 350*time.Millisecond)
	defer cancel()
	called := false
	err = withWindowsProxyLock(ctx, name, func(bool) error { called = true; return nil })
	if !errors.Is(err, context.DeadlineExceeded) || called {
		t.Fatalf("contended mutex entered callback or did not time out: called=%t err=%v", called, err)
	}
	if err := cmd.Process.Kill(); err != nil {
		t.Fatal(err)
	}
	_ = cmd.Wait()
	abandoned := false
	if err := withWindowsProxyLock(context.Background(), name, func(wasAbandoned bool) error {
		abandoned = wasAbandoned
		return nil
	}); err != nil || !abandoned {
		t.Fatalf("expected abandoned mutex after crash: abandoned=%t err=%v", abandoned, err)
	}
	if err := withWindowsProxyLock(context.Background(), name, func(wasAbandoned bool) error {
		if wasAbandoned {
			return errors.New("released mutex still abandoned")
		}
		return nil
	}); err != nil {
		t.Fatal(err)
	}
}
