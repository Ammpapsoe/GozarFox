//go:build windows && !proxyexperiment

package sysproxy

import (
	"context"
	"fmt"
	"reflect"
	"testing"

	"golang.org/x/sys/windows/registry"
)

func TestSystemProxyProductionGuardDoesNotChangeWindowsSettings(t *testing.T) {
	read := func(path string) map[string]string {
		t.Helper()
		key, err := registry.OpenKey(registry.CURRENT_USER, path, registry.QUERY_VALUE)
		if err != nil {
			t.Fatal(err)
		}
		defer key.Close()
		names, err := key.ReadValueNames(-1)
		if err != nil {
			t.Fatal(err)
		}
		out := make(map[string]string, len(names))
		for _, name := range names {
			size, _, err := key.GetValue(name, nil)
			if err != nil {
				t.Fatal(err)
			}
			data := make([]byte, size)
			n, kind, err := key.GetValue(name, data)
			if err != nil || n != size {
				t.Fatal("failed to read Windows proxy settings")
			}
			out[name] = fmt.Sprintf("%d:%x", kind, data)
		}
		return out
	}
	beforeSettings := read(settingsKey)
	beforeConnections := read(settingsKey + `\Connections`)
	if session, err := StartSystemProxy(context.Background(), 10810); err == nil || session != nil {
		t.Fatal("unsafe experimental backend unexpectedly enabled")
	}
	if !reflect.DeepEqual(beforeSettings, read(settingsKey)) || !reflect.DeepEqual(beforeConnections, read(settingsKey+`\Connections`)) {
		t.Fatal("blocked system proxy startup changed Windows settings")
	}
}
