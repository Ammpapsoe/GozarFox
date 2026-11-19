//go:build windows && proxyexperiment

// These tests intentionally exercise the incomplete experimental backend on a
// disposable runner. They currently reveal loss of per-connection settings.

package sysproxy

import (
	"bufio"
	"context"
	"fmt"
	"net"
	"os"
	"os/exec"
	"reflect"
	"strings"
	"testing"
	"time"

	"golang.org/x/sys/windows/registry"
)

// The actual production watchdog launches the current executable. Teach this
// test executable the same internal entry point; no real account is used.
func TestMain(m *testing.M) {
	if len(os.Args) == 2 && os.Args[1] == "--internal-proxy-watchdog" {
		if err := RunRecoveryWatchdog(); err != nil {
			os.Exit(1)
		}
		os.Exit(0)
	}
	os.Exit(m.Run())
}

func readLiveValues(t *testing.T, b *windowsBackend) map[string]ProxyValue {
	t.Helper()
	values := make(map[string]ProxyValue, len(proxyNames))
	for _, name := range proxyNames {
		value, err := b.store.Read(name)
		if err != nil {
			t.Fatal(err)
		}
		values[name] = value
	}
	return values
}

func productionTestBackend(t *testing.T) (*windowsBackend, map[string]ProxyValue) {
	t.Helper()
	b, err := openWindowsBackend()
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(b.Close)
	ctx, cancel := context.WithTimeout(context.Background(), 5*time.Second)
	defer cancel()
	if err := b.lock(ctx, func(bool) error {
		snapshot, err := b.journal.Load()
		if err != nil {
			return err
		}
		if snapshot != nil {
			return fmt.Errorf("unexpected existing proxy recovery journal")
		}
		return nil
	}); err != nil {
		t.Fatal(err)
	}
	return b, readLiveValues(t, b)
}

func readLiveConnectionValues(t *testing.T) map[string]string {
	t.Helper()
	key, err := registry.OpenKey(registry.CURRENT_USER, settingsKey+`\Connections`, registry.QUERY_VALUE)
	if err != nil {
		t.Fatal(err)
	}
	defer key.Close()
	names, err := key.ReadValueNames(-1)
	if err != nil {
		t.Fatal(err)
	}
	values := make(map[string]string, len(names))
	for _, name := range names {
		n, kind, err := key.GetValue(name, nil)
		if err != nil {
			t.Fatal(err)
		}
		data := make([]byte, n)
		n, kind, err = key.GetValue(name, data)
		if err != nil || n != len(data) {
			t.Fatal("could not read connection setting")
		}
		values[name] = fmt.Sprintf("%d:%x", kind, data)
	}
	return values
}

func TestSystemProxyProductionConnectRestore(t *testing.T) {
	b, before := productionTestBackend(t)
	beforeConnections := readLiveConnectionValues(t)
	listener, err := net.Listen("tcp4", "127.0.0.1:0")
	if err != nil {
		t.Fatal(err)
	}
	defer listener.Close()
	port := listener.Addr().(*net.TCPAddr).Port
	session, err := StartSystemProxy(context.Background(), port)
	if err != nil {
		afterConnections := readLiveConnectionValues(t)
		for name, value := range beforeConnections {
			if afterConnections[name] != value {
				t.Errorf("WinINet connection setting %s changed during failed startup", name)
			}
		}
		for name := range afterConnections {
			if _, ok := beforeConnections[name]; !ok {
				t.Errorf("WinINet connection setting %s appeared during failed startup", name)
			}
		}
		t.Fatal(err)
	}
	t.Cleanup(func() { _ = session.Close() })
	if got := readLiveValues(t, b); !reflect.DeepEqual(got, desired(port)) {
		for _, name := range proxyNames {
			want := desired(port)[name]
			value := got[name]
			if value != want {
				t.Errorf("WinINet setting %s differs: got kind=%s number=%d textlen=%d; want kind=%s number=%d textlen=%d", name, value.Kind, value.Number, len(value.Text), want.Kind, want.Number, len(want.Text))
			}
		}
		t.Fatal("WinINet settings do not point to the live listener")
	}
	if err := session.Close(); err != nil {
		t.Fatal(err)
	}
	if got := readLiveValues(t, b); !reflect.DeepEqual(got, before) {
		t.Fatal("WinINet settings were not restored")
	}
	if snap, err := b.journal.Load(); err != nil || snap != nil {
		t.Fatal("journal remained after restore", err)
	}
}

func TestSystemProxyProductionConflictIsReported(t *testing.T) {
	b, before := productionTestBackend(t)
	listener, err := net.Listen("tcp4", "127.0.0.1:0")
	if err != nil {
		t.Fatal(err)
	}
	defer listener.Close()
	port := listener.Addr().(*net.TCPAddr).Port
	session, err := StartSystemProxy(context.Background(), port)
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() { _ = session.Close() })
	if err := b.store.Write("ProxyServer", text("external.invalid:9988")); err != nil {
		t.Fatal(err)
	}
	if err := session.Close(); err == nil || !strings.Contains(err.Error(), "changed externally") {
		t.Fatalf("missing conflict: %v", err)
	}
	got, err := b.store.Read("ProxyServer")
	if err != nil || got != text("external.invalid:9988") {
		t.Fatal("external setting was overwritten")
	}
	if snap, err := b.journal.Load(); err != nil || snap == nil {
		t.Fatal("conflict lost journal", err)
	}
	// Simulate a user resolving the conflict, then explicitly recover.
	if err := b.store.Write("ProxyServer", desired(port)["ProxyServer"]); err != nil {
		t.Fatal(err)
	}
	if err := b.lock(context.Background(), func(bool) error { return recoverProxy(b.store, b.journal) }); err != nil {
		t.Fatal(err)
	}
	if got := readLiveValues(t, b); !reflect.DeepEqual(got, before) {
		t.Fatal("original settings not recovered after conflict resolution")
	}
}

func TestSystemProxyCrashWorker(t *testing.T) {
	if os.Getenv("GOZAR_SYSTEM_PROXY_CRASH_WORKER") != "1" {
		t.Skip("worker only")
	}
	listener, err := net.Listen("tcp4", "127.0.0.1:0")
	if err != nil {
		t.Fatal(err)
	}
	defer listener.Close()
	session, err := StartSystemProxy(context.Background(), listener.Addr().(*net.TCPAddr).Port)
	if err != nil {
		t.Fatal(err)
	}
	defer session.Close()
	fmt.Println("ACTIVATED")
	time.Sleep(time.Minute)
	t.Fatal("parent did not kill worker")
}

func TestSystemProxyProductionRestoresAfterCrash(t *testing.T) {
	b, before := productionTestBackend(t)
	cmd := exec.Command(os.Args[0], "-test.run=^TestSystemProxyCrashWorker$", "-test.timeout=45s")
	cmd.Env = append(os.Environ(), "GOZAR_SYSTEM_PROXY_CRASH_WORKER=1")
	stdout, err := cmd.StdoutPipe()
	if err != nil {
		t.Fatal(err)
	}
	cmd.Stderr = os.Stderr
	if err := cmd.Start(); err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() { _ = cmd.Process.Kill(); _ = cmd.Wait() })
	line := make(chan string, 1)
	go func() {
		scanner := bufio.NewScanner(stdout)
		if scanner.Scan() {
			line <- scanner.Text()
		} else {
			line <- ""
		}
	}()
	select {
	case got := <-line:
		if got != "ACTIVATED" {
			t.Fatalf("worker did not activate: %q", got)
		}
	case <-time.After(20 * time.Second):
		t.Fatal("worker did not start")
	}
	if err := cmd.Process.Kill(); err != nil {
		t.Fatal(err)
	}
	_ = cmd.Wait()
	deadline := time.Now().Add(20 * time.Second)
	for {
		ctx, cancel := context.WithTimeout(context.Background(), 2*time.Second)
		var remaining *ProxySnapshot
		err := b.lock(ctx, func(bool) error { var e error; remaining, e = b.journal.Load(); return e })
		cancel()
		if err == nil && remaining == nil && reflect.DeepEqual(readLiveValues(t, b), before) {
			break
		}
		if time.Now().After(deadline) {
			t.Fatalf("watchdog did not recover after crash: %v", err)
		}
		time.Sleep(100 * time.Millisecond)
	}
}
