//go:build windows

package sysproxy

import (
	"fmt"
	"os"
	"reflect"
	"sync/atomic"
	"testing"
	"time"

	"golang.org/x/sys/windows/registry"
)

var isolatedKeySequence atomic.Uint64

func isolatedRegistry(t *testing.T) registryProxyStore {
	t.Helper()
	// Windows clock resolution is insufficient to distinguish two keys created
	// in the same tick; the process-local sequence prevents collisions.
	path := fmt.Sprintf(`Software\Gozar-Proxy-Test-%d-%d-%d`, os.Getpid(), time.Now().UnixNano(), isolatedKeySequence.Add(1))
	key, existed, err := registry.CreateKey(registry.CURRENT_USER, path, registry.ALL_ACCESS)
	if err != nil {
		t.Fatal(err)
	}
	if existed {
		key.Close()
		t.Fatal("test key already existed")
	}
	t.Cleanup(func() {
		key.Close()
		if err := registry.DeleteKey(registry.CURRENT_USER, path); err != nil {
			t.Errorf("delete isolated test key: %v", err)
		}
	})
	return registryProxyStore{key: key}
}

func TestRegistryProxyTransaction(t *testing.T) {
	store := isolatedRegistry(t)
	original := map[string]ProxyValue{
		"ProxyEnable": number(1), "ProxyServer": text("proxy.example:3128"),
		"ProxyOverride": {Kind: "expand", Text: "%INTRANET%;例.example"},
		"AutoConfigURL": missing(), "AutoDetect": number(1),
	}
	for name, value := range original {
		if err := store.Write(name, value); err != nil {
			t.Fatal(err)
		}
	}
	snapshot, err := captureProxy(store, 10810)
	if err != nil {
		t.Fatal(err)
	}
	if !reflect.DeepEqual(snapshot.Previous, original) {
		t.Fatalf("snapshot differs: %#v", snapshot.Previous)
	}
	if err := applyProxy(store, snapshot); err != nil {
		t.Fatal(err)
	}
	for name, want := range desired(10810) {
		got, err := store.Read(name)
		if err != nil || got != want {
			t.Fatalf("%s: got %#v, error %v; want %#v", name, got, err, want)
		}
	}
	if err := restoreProxy(store, snapshot); err != nil {
		t.Fatal(err)
	}
	for name, want := range original {
		got, err := store.Read(name)
		if err != nil || got != want {
			t.Fatalf("%s: got %#v, error %v; want %#v", name, got, err, want)
		}
	}
}

func TestRegistryProxyRejectsUnsupportedType(t *testing.T) {
	store := isolatedRegistry(t)
	if err := store.key.SetBinaryValue("ProxyServer", []byte{1, 2, 3}); err != nil {
		t.Fatal(err)
	}
	if _, err := captureProxy(store, 10810); err == nil {
		t.Fatal("unsupported type accepted")
	}
	if got, _, err := store.key.GetBinaryValue("ProxyServer"); err != nil || !reflect.DeepEqual(got, []byte{1, 2, 3}) {
		t.Fatalf("unsupported value was changed: %v %v", got, err)
	}
}
