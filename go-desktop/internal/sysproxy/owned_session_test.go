package sysproxy

import (
	"context"
	"reflect"
	"strings"
	"testing"
)

func TestOwnedAsyncSessionLifecycle(t *testing.T) {
	held := false
	memory := newMemoryProxy()
	before := copyValues(memory.values)
	journal := ownedJournal{&memoryJournal{}, &held}
	session, err := startOwnedSession(context.Background(), testOwnership(t, &held), ownedStore{memory, &held}, journal, 10810)
	if err != nil {
		t.Fatal(err)
	}
	if !held || memory.values["ProxyServer"] != desired(10810)["ProxyServer"] || journal.snapshot == nil {
		t.Fatal("startup returned before activation or lock ownership")
	}
	if err := session.Close(); err != nil {
		t.Fatal(err)
	}
	if err := session.Close(); err != nil || held || journal.snapshot != nil || !reflect.DeepEqual(memory.values, before) {
		t.Fatalf("shutdown did not restore and release exactly once: %v", err)
	}
}

func TestOwnedAsyncSessionReportsConflict(t *testing.T) {
	held := false
	memory := newMemoryProxy()
	journal := ownedJournal{&memoryJournal{}, &held}
	session, err := startOwnedSession(context.Background(), testOwnership(t, &held), ownedStore{memory, &held}, journal, 10810)
	if err != nil {
		t.Fatal(err)
	}
	memory.values["ProxyServer"] = text("changed-by-another-application:8080")
	if err := session.Close(); err == nil || !strings.Contains(err.Error(), "changed externally") {
		t.Fatalf("conflict not returned to caller: %v", err)
	}
	if journal.snapshot == nil || held || memory.values["ProxyServer"] != text("changed-by-another-application:8080") {
		t.Fatal("conflict lost backup, lock, or external setting")
	}
}

func TestOwnedAsyncSessionCancelledBeforeStartup(t *testing.T) {
	held := false
	memory := newMemoryProxy()
	original := copyValues(memory.values)
	journal := ownedJournal{&memoryJournal{}, &held}
	ctx, cancel := context.WithCancel(context.Background())
	cancel()
	if session, err := startOwnedSession(ctx, testOwnership(t, &held), ownedStore{memory, &held}, journal, 10810); err == nil || session != nil {
		t.Fatalf("cancelled startup returned session: %v", err)
	}
	if held || journal.snapshot != nil || !reflect.DeepEqual(memory.values, original) {
		t.Fatal("cancelled startup changed settings")
	}
}
