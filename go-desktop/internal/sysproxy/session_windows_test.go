//go:build windows

package sysproxy

import (
	"context"
	"errors"
	"fmt"
	"os"
	"testing"
	"time"
)

func TestOwnedWindowsSessionExcludesAnotherOwnerUntilRestore(t *testing.T) {
	backup := isolatedRegistry(t)
	settings := isolatedRegistry(t)
	original := map[string]ProxyValue{
		"ProxyEnable": number(1), "ProxyServer": text("original.invalid:8080"),
		"ProxyOverride": text("localhost"), "AutoConfigURL": missing(), "AutoDetect": number(1),
	}
	for name, value := range original {
		if err := settings.Write(name, value); err != nil {
			t.Fatal(err)
		}
	}
	journal := registryJournal{key: backup.key}
	name := fmt.Sprintf("%s-session-%d-%d", testMutexPrefix, os.Getpid(), time.Now().UnixNano())
	lock := func(ctx context.Context, fn func(bool) error) error { return withWindowsProxyLock(ctx, name, fn) }
	started := make(chan struct{})
	stop := make(chan struct{})
	finished := make(chan error, 1)
	sessionCtx, cancelSession := context.WithCancel(context.Background())
	go func() {
		finished <- runOwnedProxySession(sessionCtx, lock, settings, journal, 23456, func(ctx context.Context) error {
			close(started)
			select {
			case <-stop:
			case <-ctx.Done():
			}
			return nil
		})
	}()
	stopped, received := false, false
	defer func() {
		cancelSession()
		if !stopped {
			close(stop)
		}
		if !received {
			<-finished
		}
	}()
	select {
	case <-started:
	case err := <-finished:
		received = true
		t.Fatalf("session failed before activation: %v", err)
	case <-time.After(20 * time.Second):
		t.Fatal("session did not start")
	}
	ctx, cancel := context.WithTimeout(context.Background(), 350*time.Millisecond)
	defer cancel()
	called := false
	err := runOwnedProxySession(ctx, lock, settings, journal, 23457, func(context.Context) error { called = true; return nil })
	if !errors.Is(err, context.DeadlineExceeded) || called {
		t.Fatalf("second owner unexpectedly entered: called=%t err=%v", called, err)
	}
	close(stop)
	stopped = true
	err = <-finished
	received = true
	if err != nil {
		t.Fatal(err)
	}
	if err := lock(context.Background(), func(bool) error {
		for name, want := range original {
			got, err := settings.Read(name)
			if err != nil || got != want {
				return fmt.Errorf("%s not restored: got %#v, error %v", name, got, err)
			}
		}
		if snapshot, err := journal.Load(); err != nil || snapshot != nil {
			return fmt.Errorf("backup remains: %v", err)
		}
		return nil
	}); err != nil {
		t.Fatal(err)
	}
}
