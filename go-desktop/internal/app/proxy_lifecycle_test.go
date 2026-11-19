package app

import (
	"fmt"
	"io"
	"net"
	"net/http"
	"net/http/httptest"
	"net/url"
	"testing"
	"time"

	"github.com/Ammpapsoe/GozarFox/go-desktop/internal/config"
)

// Uses the real App.Connect, local proxy server and App.Disconnect. Only the
// authenticated remote relay is replaced by a loopback origin; no real account
// or external endpoint is used in this test.
func newProxyLifecycleApp(t *testing.T) *App {
	t.Helper()
	root := t.TempDir()
	t.Setenv("HOME", root)
	t.Setenv("XDG_CONFIG_HOME", root)
	t.Setenv("APPDATA", root)
	origin := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if r.Host != "origin.example" || r.URL.Path != "/check" {
			w.WriteHeader(http.StatusBadRequest)
			return
		}
		_, _ = io.WriteString(w, "proxy lifecycle ok")
	}))
	t.Cleanup(origin.Close)
	current := New("test")
	t.Cleanup(func() { _ = current.Close() })
	if err := current.SetMode(config.ModeProxyOnly); err != nil {
		t.Fatal(err)
	}
	current.mu.Lock()
	current.cfg.Port = 0 // Ask the OS for a private test port.
	current.mu.Unlock()
	current.proxyTestDial = func(host string, port int) (io.ReadWriteCloser, error) {
		if host != "origin.example" || port != 80 {
			return nil, fmt.Errorf("unexpected destination %s:%d", host, port)
		}
		return net.DialTimeout("tcp", origin.Listener.Addr().String(), 3*time.Second)
	}
	return current
}

func exerciseProxyLifecycleTraffic(t *testing.T, port int) {
	t.Helper()
	proxyURL, err := url.Parse(fmt.Sprintf("http://127.0.0.1:%d", port))
	if err != nil {
		t.Fatal(err)
	}
	transport := &http.Transport{Proxy: http.ProxyURL(proxyURL)}
	defer transport.CloseIdleConnections()
	client := &http.Client{Transport: transport, Timeout: 5 * time.Second}
	resp, err := client.Get("http://origin.example/check")
	if err != nil {
		t.Fatal(err)
	}
	defer resp.Body.Close()
	body, err := io.ReadAll(resp.Body)
	if err != nil || resp.StatusCode != 200 || string(body) != "proxy lifecycle ok" {
		t.Fatalf("proxy response status=%d body=%q err=%v", resp.StatusCode, body, err)
	}
}

func TestConnectionModeProxyOnlyConnectTrafficAndDisconnect(t *testing.T) {
	current := newProxyLifecycleApp(t)
	if err := current.Connect(); err != nil {
		t.Fatal(err)
	}
	state := current.State()
	if !state.Live || state.Mode != string(config.ModeProxyOnly) || state.Port <= 0 || state.Device != "" || current.engine != nil {
		t.Fatalf("unexpected proxy state: %#v", state)
	}
	if err := current.SetMode(config.ModeTUN); err == nil {
		t.Fatal("mode changed during active proxy session")
	}
	if err := current.SetKillSwitch(true); err == nil {
		t.Fatal("kill switch falsely enabled in proxy-only mode")
	}
	if err := current.SetWarpUDP(true, true); err == nil {
		t.Fatal("WARP falsely enabled in proxy-only mode")
	}
	exerciseProxyLifecycleTraffic(t, state.Port)
	current.Disconnect()
	if current.State().Live || current.proxy != nil || current.modeLease != nil {
		t.Fatal("proxy resources remained published after disconnect")
	}
	conn, err := net.DialTimeout("tcp4", fmt.Sprintf("127.0.0.1:%d", state.Port), time.Second)
	if err == nil {
		conn.Close()
		t.Fatal("proxy listener remained open after disconnect")
	}
	if err := current.SetMode(config.ModeTUN); err != nil {
		t.Fatal(err)
	}
}

func TestConnectionModeProxyOnlyBindFailureReleasesOwnership(t *testing.T) {
	current := newProxyLifecycleApp(t)
	occupied, err := net.Listen("tcp4", "127.0.0.1:0")
	if err != nil {
		t.Fatal(err)
	}
	defer occupied.Close()
	current.mu.Lock()
	current.cfg.Port = occupied.Addr().(*net.TCPAddr).Port
	current.mu.Unlock()
	if err := current.Connect(); err == nil {
		t.Fatal("occupied port did not fail startup")
	}
	if current.State().Live || current.proxy != nil || current.modeLease != nil {
		t.Fatal("failed startup leaked proxy ownership")
	}
	current.mu.Lock()
	current.cfg.Port = 0
	current.mu.Unlock()
	if err := current.Connect(); err != nil {
		t.Fatalf("retry after bind failure: %v", err)
	}
	current.Disconnect()
}
