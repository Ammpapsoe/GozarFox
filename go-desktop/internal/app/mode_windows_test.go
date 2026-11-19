//go:build windows

package app

import (
	"errors"
	"reflect"
	"testing"

	"github.com/Ammpapsoe/GozarFox/go-desktop/internal/config"
	"golang.org/x/sys/windows/registry"
)

type recordedProxyValue struct {
	kind uint32
	data string
}

func readWindowsProxyValues(t *testing.T) map[string]recordedProxyValue {
	t.Helper()
	key, err := registry.OpenKey(registry.CURRENT_USER, `Software\Microsoft\Windows\CurrentVersion\Internet Settings`, registry.QUERY_VALUE)
	if err != nil {
		t.Fatal(err)
	}
	defer key.Close()
	values := make(map[string]recordedProxyValue)
	for _, name := range []string{"ProxyEnable", "ProxyServer", "ProxyOverride", "AutoConfigURL", "AutoDetect"} {
		size, kind, err := key.GetValue(name, nil)
		if errors.Is(err, registry.ErrNotExist) {
			continue
		}
		if err != nil {
			t.Fatal(err)
		}
		data := make([]byte, size)
		n, kind, err := key.GetValue(name, data)
		if err != nil || n != len(data) {
			t.Fatalf("could not read proxy value %s: %v", name, err)
		}
		values[name] = recordedProxyValue{kind: kind, data: string(data)}
	}
	return values
}

// This phase only tests mode selection and the fail-closed connect guard; it
// does not establish a live proxy-only session or prove WinHTTP non-mutation.
func TestConnectionModeProxyOnlySelectionDoesNotChangeWindowsUserProxy(t *testing.T) {
	root := t.TempDir()
	t.Setenv("APPDATA", root)
	t.Setenv("HOME", root)
	before := readWindowsProxyValues(t)
	current := New("test")
	if err := current.SetMode(config.ModeProxyOnly); err != nil {
		t.Fatal(err)
	}
	if err := current.Connect(); err == nil {
		t.Fatal("unfinished proxy-only mode unexpectedly connected")
	}
	current.Close()
	after := readWindowsProxyValues(t)
	if !reflect.DeepEqual(before, after) {
		t.Fatal("user proxy settings changed during selection")
	}
}
