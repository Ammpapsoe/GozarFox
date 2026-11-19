//go:build windows && proxyexperiment

package sysproxy

import (
	"os"
	"testing"
)

// This modifies the actual user LAN proxy configuration. It is permitted only
// on an explicitly opted-in disposable GitHub-hosted Windows runner. The
// experimental setter lacks crash recovery and MUST NOT run on a user machine.
func TestWinINetRealRoundTrip(t *testing.T) {
	if os.Getenv("GOZAR_DISPOSABLE_WININET_TEST") != "1" || os.Getenv("GITHUB_ACTIONS") != "true" || os.Getenv("RUNNER_ENVIRONMENT") != "github-hosted" {
		t.Fatal("refusing to change WinINet outside the opted-in disposable GitHub-hosted runner")
	}
	original, err := queryLANProxy()
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() {
		if err := setLANProxy(original); err != nil {
			t.Errorf("restore original LAN setting: %v", err)
			return
		}
		got, err := queryLANProxy()
		if err != nil {
			t.Errorf("query restored LAN setting: %v", err)
			return
		}
		if got != original {
			t.Errorf("original logical LAN setting did not round-trip: got %#v, want %#v", got, original)
		}
	})
	cases := []struct {
		name  string
		value LANProxySettings
	}{
		{"manual", LANProxySettings{Flags: 3, Server: "127.0.0.1:23456", Bypass: "localhost;<local>", PAC: ""}},
		{"pac", LANProxySettings{Flags: 5, Server: "old.example:8080", Bypass: "*.example", PAC: "https://example.invalid/proxy.pac"}},
		{"autodetect", LANProxySettings{Flags: 9, Server: "old.example:8080", Bypass: "*.example", PAC: "https://example.invalid/proxy.pac"}},
		{"pac-and-autodetect", LANProxySettings{Flags: 13, Server: "old.example:8080", Bypass: "*.example", PAC: "https://example.invalid/proxy.pac"}},
	}
	for _, tc := range cases {
		t.Run(tc.name, func(t *testing.T) {
			if err := setLANProxy(tc.value); err != nil {
				t.Fatal(err)
			}
			got, err := queryLANProxy()
			if err != nil {
				t.Fatal(err)
			}
			if got != tc.value {
				t.Fatalf("logical LAN setting did not round-trip: got %#v, want %#v", got, tc.value)
			}
			manual := LANProxySettings{Flags: 3, Server: "127.0.0.1:23456", Bypass: "localhost;<local>"}
			if err := setLANProxy(manual); err != nil {
				t.Fatal(err)
			}
			if err := setLANProxy(tc.value); err != nil {
				t.Fatal(err)
			}
			got, err = queryLANProxy()
			if err != nil {
				t.Fatal(err)
			}
			if got != tc.value {
				t.Fatalf("logical LAN setting not restored: got %#v, want %#v", got, tc.value)
			}
			key := isolatedRegistry(t)
			journal := lanRegistryJournal{registryJournal{key: key.key}}
			store := wininetLANStore{}
			if err := activateLAN(store, journal, 23456); err != nil {
				t.Fatal(err)
			}
			other := LANProxySettings{Flags: 3, Server: "other.example:1234", Bypass: "localhost"}
			if err := setLANProxy(other); err != nil {
				t.Fatal(err)
			}
			if err := recoverLAN(store, journal); err == nil {
				t.Fatal("external setting was not treated as a conflict")
			}
			got, err = queryLANProxy()
			if err != nil || got != other {
				t.Fatalf("conflict changed external setting: %#v, %v", got, err)
			}
			if saved, err := journal.LoadLAN(); err != nil || saved == nil {
				t.Fatalf("conflict lost journal: %v", err)
			}
			if err := setLANProxy(desiredLAN(23456)); err != nil {
				t.Fatal(err)
			}
			if err := recoverLAN(store, journal); err != nil {
				t.Fatal(err)
			}
			got, err = queryLANProxy()
			if err != nil || got != tc.value {
				t.Fatalf("transaction did not restore setting: %#v, %v", got, err)
			}
			if saved, err := journal.LoadLAN(); err != nil || saved != nil {
				t.Fatalf("completed recovery retained journal: %v", err)
			}
		})
	}
}
