package app

import (
	"sync"
	"sync/atomic"
	"testing"

	"github.com/Ammpapsoe/GozarFox/go-desktop/internal/config"
)

func TestConnectionModePlansAreExclusiveAndProxyOnlyNeverRequestsSystemProxy(t *testing.T) {
	cases := []struct {
		mode config.ConnectionMode
		want connectionPlan
	}{
		{config.ModeProxyOnly, connectionPlan{localProxy: true}},
		{config.ModeSystemProxy, connectionPlan{localProxy: true, systemProxy: true}},
		{config.ModeTUN, connectionPlan{tun: true}},
	}
	for _, tc := range cases {
		got, err := planForMode(tc.mode)
		if err != nil || got != tc.want {
			t.Fatalf("plan %q = %#v, %v", tc.mode, got, err)
		}
		if got.tun && (got.localProxy || got.systemProxy) || got.systemProxy && !got.localProxy {
			t.Fatalf("incompatible resources in %q: %#v", tc.mode, got)
		}
	}
	if _, err := planForMode("future"); err == nil {
		t.Fatal("unknown mode accepted")
	}
}

func TestConnectionModeOwnershipAllowsOneConcurrentOwner(t *testing.T) {
	var ownership modeOwnership
	const contenders = 90
	start := make(chan struct{})
	var wg sync.WaitGroup
	var winners atomic.Int32
	var held atomic.Pointer[modeLease]
	for i := 0; i < contenders; i++ {
		wg.Add(1)
		go func(i int) {
			defer wg.Done()
			<-start
			modes := []config.ConnectionMode{config.ModeTUN, config.ModeProxyOnly, config.ModeSystemProxy}
			if lease, err := ownership.acquire(modes[i%len(modes)]); err == nil {
				winners.Add(1)
				held.Store(lease)
			}
		}(i)
	}
	close(start)
	wg.Wait()
	if winners.Load() != 1 {
		t.Fatalf("got %d simultaneous owners", winners.Load())
	}
	first := held.Load()
	first.release()
	second, err := ownership.acquire(config.ModeSystemProxy)
	if err != nil {
		t.Fatal(err)
	}
	first.release() // an old callback cannot release the new owner
	if _, err := ownership.acquire(config.ModeTUN); err == nil {
		t.Fatal("stale release removed another owner's lease")
	}
	second.release()
}

func TestConnectionModeLeaseIsHeldUntilTeardown(t *testing.T) {
	lease, err := processModeOwnership.acquire(config.ModeTUN)
	if err != nil {
		t.Fatal(err)
	}
	current := &App{modeLease: lease}
	defer current.teardown()
	if _, err := processModeOwnership.acquire(config.ModeProxyOnly); err == nil {
		t.Fatal("another mode acquired resources before teardown")
	}
	current.teardown()
	other, err := processModeOwnership.acquire(config.ModeProxyOnly)
	if err != nil {
		t.Fatal(err)
	}
	other.release()
}

func TestConnectionModeSelectionDoesNotActivateProxyOrAllowUnsafeConnect(t *testing.T) {
	root := t.TempDir()
	t.Setenv("HOME", root)
	t.Setenv("XDG_CONFIG_HOME", root)
	t.Setenv("APPDATA", root)
	current := New("test")
	defer current.Close()
	for _, mode := range []config.ConnectionMode{config.ModeProxyOnly, config.ModeSystemProxy} {
		if err := current.SetMode(mode); err != nil {
			t.Fatal(err)
		}
		if current.State().Mode != string(mode) || config.Load().Mode != mode {
			t.Fatalf("mode %q was not persisted", mode)
		}
		if err := current.Connect(); err == nil {
			t.Fatalf("unfinished mode %q connected", mode)
		}
		if current.State().Live || current.engine != nil || current.modeLease != nil {
			t.Fatalf("unfinished mode %q activated resources", mode)
		}
	}
	current.mu.Lock()
	current.live = true
	current.mu.Unlock()
	if err := current.SetMode(config.ModeTUN); err == nil {
		t.Fatal("live mode change accepted")
	}
	current.mu.Lock()
	current.live = false
	current.mu.Unlock()
}
