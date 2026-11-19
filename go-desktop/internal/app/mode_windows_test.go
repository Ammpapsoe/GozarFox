//go:build windows

package app

import (
	"bytes"
	"errors"
	"os/exec"
	"reflect"
	"testing"

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
	// Per-connection WinINet configuration is stored separately, including
	// DefaultConnectionSettings, SavedLegacySettings and RAS entries.
	connections, err := registry.OpenKey(registry.CURRENT_USER, `Software\Microsoft\Windows\CurrentVersion\Internet Settings\Connections`, registry.QUERY_VALUE)
	if errors.Is(err, registry.ErrNotExist) {
		return values
	}
	if err != nil {
		t.Fatal(err)
	}
	defer connections.Close()
	names, err := connections.ReadValueNames(-1)
	if err != nil {
		t.Fatal(err)
	}
	for _, name := range names {
		size, _, err := connections.GetValue(name, nil)
		if err != nil {
			t.Fatal(err)
		}
		data := make([]byte, size)
		n, kind, err := connections.GetValue(name, data)
		if err != nil || n != len(data) {
			t.Fatal("could not read per-connection proxy value")
		}
		values[`Connections\`+name] = recordedProxyValue{kind: kind, data: string(data)}
	}
	return values
}

// WinINET values and the machine WinHTTP configuration are only read. No
// proxy settings are set or cleared by this test.
func readWinHTTPProxy(t *testing.T) []byte {
	t.Helper()
	output, err := exec.Command("netsh", "winhttp", "show", "proxy").CombinedOutput()
	if err != nil {
		t.Fatal("could not inspect WinHTTP proxy configuration")
	}
	return output
}

func TestConnectionModeProxyOnlyConnectDisconnectDoesNotChangeWindowsProxy(t *testing.T) {
	beforeUser := readWindowsProxyValues(t)
	beforeWinHTTP := readWinHTTPProxy(t)
	current := newProxyLifecycleApp(t)
	assertUnchanged := func(stage string) {
		t.Helper()
		if !reflect.DeepEqual(beforeUser, readWindowsProxyValues(t)) {
			t.Fatalf("WinINet user proxy changed %s", stage)
		}
		if !bytes.Equal(beforeWinHTTP, readWinHTTPProxy(t)) {
			t.Fatalf("WinHTTP proxy changed %s", stage)
		}
	}
	assertUnchanged("after selection")
	if err := current.Connect(); err != nil {
		t.Fatal(err)
	}
	if !current.State().Live {
		t.Fatal("proxy session did not become live")
	}
	assertUnchanged("after connect")
	exerciseProxyLifecycleTraffic(t, current.State().Port)
	assertUnchanged("while traffic was proxied")
	current.Disconnect()
	assertUnchanged("after disconnect")
	current.Close()
	assertUnchanged("after close")
}
