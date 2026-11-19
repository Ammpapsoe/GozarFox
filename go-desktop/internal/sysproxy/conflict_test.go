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
