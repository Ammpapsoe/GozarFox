//go:build windows && proxyexperiment

package app

import (
	"fmt"
	"os"
	"strings"
	"testing"
	"time"

	"github.com/Ammpapsoe/GozarFox/go-desktop/internal/config"
	"github.com/Ammpapsoe/GozarFox/go-desktop/internal/sysproxy"
)

// The production watchdog launches the current executable; route its child
// entry point to the same recovery function used by the main application.
func TestMain(m *testing.M) {
	if len(os.Args) == 2 && os.Args[1] == "--internal-proxy-watchdog" {
		if err := sysproxy.RunExperimentalLANWatchdog(); err != nil {
			os.Exit(1)
		}
		os.Exit(0)
	}
	os.Exit(m.Run())
}

func TestSystemProxyExperimentalAppIntegration(t *testing.T) {
	if os.Getenv("GOZAR_DISPOSABLE_WININET_TEST") != "1" || os.Getenv("GITHUB_ACTIONS") != "true" || os.Getenv("RUNNER_ENVIRONMENT") != "github-hosted" {
		t.Fatal("refusing real WinINet changes outside an opted-in disposable runner")
	}
	original, err := sysproxy.ReadExperimentalLANSettings()
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() {
		if err := sysproxy.WriteExperimentalLANSettings(original); err != nil {
			t.Errorf("restore runner setting: %v", err)
			return
		}
		got, err := sysproxy.ReadExperimentalLANSettings()
		if err != nil || got != original {
			t.Errorf("verify runner setting: %#v, %v", got, err)
		}
	})
	before := sysproxy.LANProxySettings{Flags: 13, Server: "prior.example:8080", Bypass: "*.example", PAC: "https://example.invalid/proxy.pac"}
	if err := sysproxy.WriteExperimentalLANSettings(before); err != nil {
		t.Fatal(err)
	}
	// Simulate an OS restart where both the original owner and watchdog died.
	// The next app start must restore before any account or relay is needed.
	if err := sysproxy.PrepareExperimentalLANRecovery(23456); err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() {
		if err := sysproxy.RecoverExperimentalLAN(); err != nil {
			t.Errorf("recover startup test journal: %v", err)
		}
	})
	current := newProxyLifecycleApp(t)
	deadline := time.Now().Add(10 * time.Second)
	for {
		got, err := sysproxy.ReadExperimentalLANSettings()
		if err != nil {
			t.Fatal(err)
		}
		if got == before {
			break
		}
		if time.Now().After(deadline) {
			t.Fatalf("app startup did not recover prior setting: %#v", got)
		}
		time.Sleep(50 * time.Millisecond)
	}
	if err := current.SetMode(config.ModeSystemProxy); err != nil {
		t.Fatal(err)
	}
	if err := current.Connect(); err != nil {
		t.Fatal(err)
	}
	state := current.State()
	want := sysproxy.LANProxySettings{Flags: 3, Server: fmt.Sprintf("127.0.0.1:%d", state.Port), Bypass: "localhost;127.*;<local>"}
	got, err := sysproxy.ReadExperimentalLANSettings()
	if err != nil || got != want || !state.Live {
		t.Fatalf("app failed to apply LAN proxy: %#v, %v", got, err)
	}
	// Both an explicit client and a WinINet PRECONFIG client use the real
	// local listener; the remote Mozilla relay is still a local test double.
	exerciseProxyLifecycleTraffic(t, state.Port)
	exerciseSystemWinINetTraffic(t)
	if err := current.Disconnect(); err != nil {
		t.Fatal(err)
	}
	got, err = sysproxy.ReadExperimentalLANSettings()
	if err != nil || got != before {
		t.Fatalf("app failed to restore LAN: %#v, %v", got, err)
	}

	if err := current.Connect(); err != nil {
		t.Fatal(err)
	}
	state = current.State()
	want.Server = fmt.Sprintf("127.0.0.1:%d", state.Port)
	// If the test fails midway, try safe recovery after reinstating the exact
	// expected owned state. The runner itself is disposable.
	t.Cleanup(func() {
		if err := sysproxy.WriteExperimentalLANSettings(want); err != nil {
			t.Errorf("reinstate owned test setting: %v", err)
			return
		}
		if err := sysproxy.RecoverExperimentalLAN(); err != nil {
			t.Errorf("recover test journal: %v", err)
		}
	})
	other := sysproxy.LANProxySettings{Flags: 3, Server: "other.invalid:5555"}
	if err := sysproxy.WriteExperimentalLANSettings(other); err != nil {
		t.Fatal(err)
	}
	err = current.Disconnect()
	if err == nil || !strings.Contains(err.Error(), "changed externally") || !strings.Contains(current.State().Fault, "changed externally") {
		t.Fatalf("app hid restore conflict: %v", err)
	}
	got, err = sysproxy.ReadExperimentalLANSettings()
	if err != nil || got != other {
		t.Fatalf("app overwrote external setting: %#v, %v", got, err)
	}
}
