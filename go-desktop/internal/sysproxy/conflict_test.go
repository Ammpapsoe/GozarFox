package sysproxy

import (
	"reflect"
	"strings"
	"testing"
)

type changeDuringApply struct {
	*memoryProxyStore
	serverReads int
}

func (s *changeDuringApply) Read(name string) (ProxyValue, error) {
	if name == "ProxyServer" {
		s.serverReads++
		// Capture and preflight both saw the old value. Simulate an unrelated
		// program changing it immediately before the proxy writes its target.
		if s.serverReads == 3 {
			s.values[name] = text("external.invalid:9999")
		}
	}
	return s.memoryProxyStore.Read(name)
}

// Simulate an external writer immediately after the first restore mutation.
// The subsequent pre-write scan must detect it and preserve the journal.
type changeDuringRestore struct {
	*memoryProxyStore
	armed bool
}

func (s *changeDuringRestore) Write(name string, value ProxyValue) error {
	if err := s.memoryProxyStore.Write(name, value); err != nil {
		return err
	}
	if s.armed && name == "ProxyEnable" && value == number(0) {
		s.armed = false
		s.values["ProxyServer"] = text("external-restore.example:8123")
	}
	return nil
}

func TestRecoveryDetectsExternalChangeDuringRestore(t *testing.T) {
	store := &changeDuringRestore{memoryProxyStore: newMemoryProxy()}
	journal := &memoryJournal{}
	if err := activateProxy(store, journal, 10810); err != nil {
		t.Fatal(err)
	}
	store.armed = true
	if err := recoverProxy(store, journal); err == nil || !strings.Contains(err.Error(), "changed externally during restore") {
		t.Fatalf("expected restore conflict, got %v", err)
	}
	if got := store.values["ProxyServer"]; got != text("external-restore.example:8123") {
		t.Fatalf("external value overwritten: %#v", got)
	}
	if journal.snapshot == nil {
		t.Fatal("journal lost during conflicting restore")
	}
}

type changeAfterNotification struct{ *memoryProxyStore }

func (s changeAfterNotification) Commit() error {
	delete(s.values, "AutoDetect")
	return nil
}

func TestActivationRejectsPlatformChangeAfterNotification(t *testing.T) {
	store := changeAfterNotification{newMemoryProxy()}
	journal := &memoryJournal{}
	err := activateProxy(store, journal, 10810)
	if err == nil || !strings.Contains(err.Error(), "AutoDetect changed after notification") {
		t.Fatalf("platform normalization was not rejected: %v", err)
	}
	if journal.snapshot == nil {
		t.Fatal("backup not retained")
	}
}

func TestActivationDetectsExternalChangeAfterCapture(t *testing.T) {
	store := newMemoryProxy()
	snapshot, err := captureProxy(store, 10810)
	if err != nil {
		t.Fatal(err)
	}
	store.values["ProxyServer"] = text("external.invalid:9999")
	before := copyValues(store.values)
	if err := applyProxy(store, snapshot); err == nil || !strings.Contains(err.Error(), "changed before activation") {
		t.Fatalf("expected preflight conflict, got %v", err)
	}
	if !reflect.DeepEqual(before, store.values) {
		t.Fatal("preflight conflict modified proxy settings")
	}
}

func TestActivationDetectsExternalChangeDuringApply(t *testing.T) {
	store := &changeDuringApply{memoryProxyStore: newMemoryProxy()}
	journal := &memoryJournal{}
	err := activateProxy(store, journal, 10810)
	if err == nil || !strings.Contains(err.Error(), "changed during activation") {
		t.Fatalf("expected mid-activation conflict, got %v", err)
	}
	if got := store.values["ProxyServer"]; got != text("external.invalid:9999") {
		t.Fatalf("external change overwritten: %#v", got)
	}
	if saved, err := journal.Load(); err != nil || saved == nil {
		t.Fatalf("backup not retained after conflict: %v", err)
	}
}
