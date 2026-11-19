//go:build windows && proxyexperiment

package app

import (
	"fmt"
	"os"
	"strings"
	"testing"

	"github.com/Ammpapsoe/GozarFox/go-desktop/internal/config"
	"github.com/Ammpapsoe/GozarFox/go-desktop/internal/sysproxy"
)

// The experimental watchdog launches the current executable. No production
// app entry point or production starter is enabled by this test.
func TestMain(m *testing.M) {
	if len(os.Args) == 2 && os.Args[1] == "--internal-lan-proxy-watchdog" {
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
	current := newProxyLifecycleApp(t)
	if err := current.SetMode(config.ModeSystemProxy); err != nil {
		t.Fatal(err)
	}
	current.systemProxyStart = sysproxy.StartExperimentalLANSession
	if err := current.Connect(); err != nil {
		t.Fatal(err)
	}
	state := current.State()
	want := sysproxy.LANProxySettings{Flags: 3, Server: fmt.Sprintf("127.0.0.1:%d", state.Port), Bypass: "localhost;127.*;<local>"}
	got, err := sysproxy.ReadExperimentalLANSettings()
	if err != nil || got != want || !state.Live {
		t.Fatalf("app failed to apply LAN proxy: %#v, %v", got, err)
	}
	// This tests the app's real local proxy and lifecycle, but uses an explicit
	// proxy client and a fake local relay, not a Mozilla account or WinINet
	// auto-detection of proxy settings.
	exerciseProxyLifecycleTraffic(t, state.Port)
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
