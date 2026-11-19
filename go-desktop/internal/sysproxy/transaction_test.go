package sysproxy

import (
	"errors"
	"reflect"
	"testing"
)

type memoryProxyStore struct {
	values map[string]ProxyValue
	failOn string
	failed bool
}

func (s *memoryProxyStore) Read(name string) (ProxyValue, error) {
	if v, ok := s.values[name]; ok {
		return v, nil
	}
	return missing(), nil
}
func (s *memoryProxyStore) Write(name string, value ProxyValue) error {
	if s.failOn == name && !s.failed {
		s.failed = true
		return errors.New("injected failure")
	}
	if value.Kind == "missing" {
		delete(s.values, name)
	} else {
		s.values[name] = value
	}
	return nil
}
func newMemoryProxy() *memoryProxyStore {
	return &memoryProxyStore{values: map[string]ProxyValue{
		"ProxyEnable": number(1), "ProxyServer": text("proxy.example:3128"),
		"ProxyOverride": {Kind: "expand", Text: "%INTRANET%"}, "AutoConfigURL": text("https://pac.example/config"),
	}}
}
func copyValues(v map[string]ProxyValue) map[string]ProxyValue {
	out := make(map[string]ProxyValue, len(v))
	for key, value := range v {
		out[key] = value
	}
	return out
}

func TestProxyTransactionRestoresExactState(t *testing.T) {
	store := newMemoryProxy()
	original := copyValues(store.values)
	snapshot, err := captureProxy(store, 10810)
	if err != nil {
		t.Fatal(err)
	}
	if err := applyProxy(store, snapshot); err != nil {
		t.Fatal(err)
	}
	for name, value := range desired(10810) {
		if got, _ := store.Read(name); got != value {
			t.Fatalf("%s: %#v != %#v", name, got, value)
		}
	}
	if err := restoreProxy(store, snapshot); err != nil {
		t.Fatal(err)
	}
	if !reflect.DeepEqual(store.values, original) {
		t.Fatalf("not restored: %#v", store.values)
	}
}

func TestProxyTransactionRollsBackPartialApply(t *testing.T) {
	for _, name := range []string{"ProxyServer", "ProxyOverride", "AutoConfigURL", "AutoDetect"} {
		t.Run(name, func(t *testing.T) {
			store := newMemoryProxy()
			original := copyValues(store.values)
			snapshot, err := captureProxy(store, 10810)
			if err != nil {
				t.Fatal(err)
			}
			store.failOn = name
			if err := applyProxy(store, snapshot); err == nil {
				t.Fatal("expected injected failure")
			}
			if !reflect.DeepEqual(store.values, original) {
				t.Fatalf("rollback incomplete: %#v", store.values)
			}
		})
	}
}

func TestProxyTransactionDoesNotOverwriteExternalChanges(t *testing.T) {
	store := newMemoryProxy()
	snapshot, err := captureProxy(store, 10810)
	if err != nil {
		t.Fatal(err)
	}
	if err := applyProxy(store, snapshot); err != nil {
		t.Fatal(err)
	}
	store.values["ProxyServer"] = text("other.example:8080")
	before := copyValues(store.values)
	if err := restoreProxy(store, snapshot); err == nil {
		t.Fatal("expected conflict")
	}
	if !reflect.DeepEqual(store.values, before) {
		t.Fatal("conflict mutated settings")
	}
}

func TestProxyTransactionRecoveryAfterPartialApply(t *testing.T) {
	store := newMemoryProxy()
	original := copyValues(store.values)
	snapshot, err := captureProxy(store, 10810)
	if err != nil {
		t.Fatal(err)
	}
	// Simulate a process dying after its first two writes.
	_ = store.Write("ProxyEnable", number(0))
	_ = store.Write("ProxyServer", desired(snapshot.Port)["ProxyServer"])
	if err := restoreProxy(store, snapshot); err != nil {
		t.Fatal(err)
	}
	if !reflect.DeepEqual(store.values, original) {
		t.Fatalf("recovery incomplete: %#v", store.values)
	}
}

func TestProxyTransactionRejectsMalformedBackup(t *testing.T) {
	store := newMemoryProxy()
	before := copyValues(store.values)
	if err := restoreProxy(store, ProxySnapshot{Version: 1, Port: 10810}); err == nil {
		t.Fatal("invalid backup accepted")
	}
	if !reflect.DeepEqual(store.values, before) {
		t.Fatal("invalid backup changed settings")
	}
}
