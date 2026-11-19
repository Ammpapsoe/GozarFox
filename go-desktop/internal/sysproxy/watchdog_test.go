package sysproxy

import (
	"bytes"
	"errors"
	"strings"
	"testing"
)

func TestSystemProxyWatchdogRecoversOnParentDeath(t *testing.T) {
	memory := newMemoryProxy()
	before := copyValues(memory.values)
	journal := &memoryJournal{}
	if err := activateProxy(memory, journal, 10810); err != nil {
		t.Fatal(err)
	}
	var output bytes.Buffer
	if err := serveRecoveryWatchdog(strings.NewReader(""), &output, func() error { return recoverProxy(memory, journal) }); err != nil {
		t.Fatal(err)
	}
	if output.String() != "READY\n" || journal.snapshot != nil {
		t.Fatal("watchdog did not start and recover")
	}
	for _, name := range proxyNames {
		want := before[name]
		if _, ok := before[name]; !ok {
			want = missing()
		}
		if got, _ := memory.Read(name); got != want {
			t.Fatalf("%s not restored", name)
		}
	}
}

func TestSystemProxyWatchdogPreservesConflictOnParentDeath(t *testing.T) {
	memory := newMemoryProxy()
	journal := &memoryJournal{}
	if err := activateProxy(memory, journal, 10810); err != nil {
		t.Fatal(err)
	}
	memory.values["ProxyServer"] = text("external.invalid:8080")
	var output bytes.Buffer
	err := serveRecoveryWatchdog(strings.NewReader(""), &output, func() error { return recoverProxy(memory, journal) })
	if err == nil || !strings.Contains(err.Error(), "changed externally") || journal.snapshot == nil || memory.values["ProxyServer"] != text("external.invalid:8080") {
		t.Fatalf("watchdog did not preserve conflict: %v", err)
	}
}

func TestSystemProxyWatchdogOrderlyExitDoesNotRecover(t *testing.T) {
	var output bytes.Buffer
	called := false
	if err := serveRecoveryWatchdog(strings.NewReader("D"), &output, func() error { called = true; return errors.New("unexpected") }); err != nil || called {
		t.Fatalf("orderly exit triggered recovery: %v", err)
	}
}
