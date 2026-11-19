package sysproxy

import (
	"context"
	"errors"
	"reflect"
	"strings"
	"testing"
)

func testOwnership(t *testing.T, held *bool) proxyOwnership {
	t.Helper()
	return func(ctx context.Context, fn func(bool) error) error {
		if *held {
			t.Fatal("ownership acquired twice")
		}
		*held = true
		defer func() { *held = false }()
		return fn(false)
	}
}

type ownedStore struct {
	*memoryProxyStore
	held *bool
}

func (s ownedStore) Read(name string) (ProxyValue, error) {
	if !*s.held {
		panic("read without ownership")
	}
	return s.memoryProxyStore.Read(name)
}
func (s ownedStore) Write(name string, value ProxyValue) error {
	if !*s.held {
		panic("write without ownership")
	}
	return s.memoryProxyStore.Write(name, value)
}

type ownedJournal struct {
	*memoryJournal
	held *bool
}

func (j ownedJournal) Load() (*ProxySnapshot, error) {
	if !*j.held {
		panic("journal load without ownership")
	}
	return j.memoryJournal.Load()
}
func (j ownedJournal) Save(snapshot ProxySnapshot) error {
	if !*j.held {
		panic("journal save without ownership")
	}
	return j.memoryJournal.Save(snapshot)
}
func (j ownedJournal) Clear() error {
	if !*j.held {
		panic("journal clear without ownership")
	}
	return j.memoryJournal.Clear()
}

func TestOwnedProxySessionRestoresOnReturnAndRunError(t *testing.T) {
	for _, runError := range []error{nil, errors.New("session stopped")} {
		held := false
		memory := newMemoryProxy()
		before := copyValues(memory.values)
		store := ownedStore{memory, &held}
		journal := ownedJournal{&memoryJournal{}, &held}
		err := runOwnedProxySession(context.Background(), testOwnership(t, &held), store, journal, 10810, func(context.Context) error {
			if !held || memory.values["ProxyServer"] != desired(10810)["ProxyServer"] || journal.snapshot == nil {
				t.Fatal("session began without lock, desired state, or backup")
			}
			return runError
		})
		if !errors.Is(err, runError) || held || journal.snapshot != nil || !reflect.DeepEqual(before, memory.values) {
			t.Fatalf("session not restored: error=%v held=%t backup=%#v values=%#v", err, held, journal.snapshot, memory.values)
		}
	}
}

func TestOwnedProxySessionConflictKeepsBackup(t *testing.T) {
	held := false
	memory := newMemoryProxy()
	journal := ownedJournal{&memoryJournal{}, &held}
	err := runOwnedProxySession(context.Background(), testOwnership(t, &held), ownedStore{memory, &held}, journal, 10810, func(context.Context) error {
		memory.values["ProxyServer"] = text("external.invalid:1234")
		return nil
	})
	if err == nil || !strings.Contains(err.Error(), "changed externally") || journal.snapshot == nil || held {
		t.Fatalf("conflict not preserved: err=%v backup=%#v held=%t", err, journal.snapshot, held)
	}
	if memory.values["ProxyServer"] != text("external.invalid:1234") {
		t.Fatal("external setting was overwritten")
	}
}

func TestOwnedProxySessionAttemptsRestoreDuringPanic(t *testing.T) {
	held := false
	memory := newMemoryProxy()
	before := copyValues(memory.values)
	journal := ownedJournal{&memoryJournal{}, &held}
	func() {
		defer func() {
			if recover() != "session panic" {
				t.Error("panic not propagated")
			}
		}()
		_ = runOwnedProxySession(context.Background(), testOwnership(t, &held), ownedStore{memory, &held}, journal, 10810, func(context.Context) error {
			panic("session panic")
		})
	}()
	if held || journal.snapshot != nil || !reflect.DeepEqual(before, memory.values) {
		t.Fatal("proxy was not restored during panic")
	}
}

func TestOwnedProxySessionHonorsCancelledContext(t *testing.T) {
	held := false
	memory := newMemoryProxy()
	before := copyValues(memory.values)
	journal := ownedJournal{&memoryJournal{}, &held}
	ctx, cancel := context.WithCancel(context.Background())
	cancel()
	called := false
	err := runOwnedProxySession(ctx, testOwnership(t, &held), ownedStore{memory, &held}, journal, 10810, func(context.Context) error { called = true; return nil })
	if !errors.Is(err, context.Canceled) || called || held || journal.snapshot != nil || !reflect.DeepEqual(before, memory.values) {
		t.Fatalf("cancelled session changed state: %v", err)
	}
}
