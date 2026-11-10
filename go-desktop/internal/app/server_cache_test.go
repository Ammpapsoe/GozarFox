package app

import (
	"encoding/json"
	"errors"
	"os"
	"path/filepath"
	"strings"
	"sync/atomic"
	"testing"
	"time"

	"github.com/bardia049b-stack/Gozar1/go-desktop/internal/config"
	"github.com/bardia049b-stack/Gozar1/go-desktop/internal/fxa"
)

func sampleLocations() []fxa.Location {
	return []fxa.Location{{Code: "NL", Country: "Netherlands", City: "Amsterdam", Host: "nl.mozilla.example", Port: 2499}}
}

func TestServerSnapshotIsBoundedAndRejectsInvalidInputs(t *testing.T) {
	now := time.Unix(1800000000, 0)
	encoded := encodeServerCache(sampleLocations(), now)
	got, until := decodeServerCache(encoded, now.Add(time.Hour))
	if len(got) != 1 || got[0].Host != sampleLocations()[0].Host || !until.Equal(now.Add(24*time.Hour)) {
		t.Fatalf("valid snapshot did not round trip: %#v %v", got, until)
	}
	if locations, _ := decodeServerCache(encoded, now.Add(24*time.Hour)); len(locations) != 0 {
		t.Fatal("expired list accepted")
	}
	if locations, _ := decodeServerCache(encoded, now.Add(-6*time.Minute)); len(locations) != 0 {
		t.Fatal("future timestamp accepted")
	}
	for _, host := range []string{"", "127.0.0.1", "bad\r\nHost: x", "name", "bad..example", "-bad.example"} {
		invalid := sampleLocations()
		invalid[0].Host = host
		if encodeServerCache(invalid, now) != "" {
			t.Fatalf("invalid relay host accepted: %q", host)
		}
	}
	invalid := sampleLocations()
	invalid[0].Port = 65536
	if encodeServerCache(invalid, now) != "" || encodeServerCache(nil, now) != "" {
		t.Fatal("empty or malformed list cached")
	}
	if locations, _ := decodeServerCache(`{"version":1,"fetched_at":1800000000,"locations":[{"code":"NL","host":"evil\r\nx","port":443}]}`, now); len(locations) != 0 {
		t.Fatal("malformed stored location accepted")
	}
}

func TestSealedServerCacheBootstrapsWithoutDirectFetchAndRejectsTampering(t *testing.T) {
	a := accountApp(t)
	a.mu.Lock()
	if err := a.saveFreshServersLocked(sampleLocations(), time.Now()); err != nil {
		a.mu.Unlock()
		t.Fatal(err)
	}
	a.mu.Unlock()
	name := filepath.Join(config.Dir(), "config.json")
	data, err := os.ReadFile(name)
	if err != nil {
		t.Fatal(err)
	}
	if strings.Contains(string(data), sampleLocations()[0].Host) {
		t.Fatal("server snapshot was persisted without authentication")
	}
	restarted := New("test")
	var direct atomic.Int32
	restarted.fetchServers = func() ([]fxa.Location, error) {
		direct.Add(1)
		return nil, errors.New("simulated direct 451")
	}
	got, err := restarted.LoadServers()
	if err != nil || len(got) != 1 || got[0].Host != sampleLocations()[0].Host || direct.Load() != 0 {
		t.Fatalf("cached bootstrap fetched directly: %v calls=%d", err, direct.Load())
	}
	restarted.Close()

	var disk map[string]json.RawMessage
	if err := json.Unmarshal(data, &disk); err != nil {
		t.Fatal(err)
	}
	injected, _ := json.Marshal(encodeServerCache(sampleLocations(), time.Now()))
	disk["server_cache"] = injected // valid JSON but not authenticated ciphertext
	tampered, _ := json.Marshal(disk)
	if err := os.WriteFile(name, tampered, 0600); err != nil {
		t.Fatal(err)
	}
	rejected := New("test")
	defer rejected.Close()
	var blocked atomic.Int32
	rejected.fetchServers = func() ([]fxa.Location, error) {
		blocked.Add(1)
		return nil, errors.New("simulated direct 451")
	}
	if len(rejected.servers) != 0 {
		t.Fatal("plaintext injection used as a server list")
	}
	if _, err := rejected.LoadServers(); err == nil || blocked.Load() != 1 {
		t.Fatal("missing verified cache did not surface direct failure")
	}
}

// Reuse of a sealed snapshot must not extend its lifetime: the stored FetchedAt
// stays unchanged across restart and reuse, and no network call is made.
func TestServerCacheReuseDoesNotExtendTimestamp(t *testing.T) {
	a := accountApp(t)
	fetchedAt := time.Now().Add(-23 * time.Hour)
	a.mu.Lock()
	if err := a.saveFreshServersLocked(sampleLocations(), fetchedAt); err != nil {
		a.mu.Unlock()
		t.Fatal(err)
	}
	sealed := a.cfg.ServerCache
	a.mu.Unlock()
	if sealed == "" {
		t.Fatal("snapshot not sealed")
	}

	restarted := New("test")
	defer restarted.Close()
	var calls atomic.Int32
	restarted.fetchServers = func() ([]fxa.Location, error) {
		calls.Add(1)
		return nil, errors.New("unexpected network fetch")
	}
	got, err := restarted.LoadServers()
	if err != nil || len(got) != 1 || calls.Load() != 0 {
		t.Fatalf("reuse of valid snapshot failed: err=%v calls=%d", err, calls.Load())
	}
	if restarted.cfg.ServerCache != sealed {
		t.Fatal("reuse rewrote the sealed snapshot")
	}
	want := fetchedAt.Add(serverCacheLifetime)
	if d := restarted.serversUntil.Sub(want); d < -time.Second || d > time.Second {
		t.Fatalf("expiry extended on reuse: got %v want %v", restarted.serversUntil, want)
	}
}

// A snapshot past its 24-hour bound is dropped at startup and never reused;
// the next load must go to the official list again.
func TestServerCacheExpiredSnapshotIsNotReused(t *testing.T) {
	a := accountApp(t)
	a.mu.Lock()
	if err := a.saveFreshServersLocked(sampleLocations(), time.Now().Add(-25*time.Hour)); err != nil {
		a.mu.Unlock()
		t.Fatal(err)
	}
	a.mu.Unlock()

	restarted := New("test")
	defer restarted.Close()
	if len(restarted.servers) != 0 || restarted.serversCached {
		t.Fatal("expired snapshot loaded at startup")
	}
	var calls atomic.Int32
	restarted.fetchServers = func() ([]fxa.Location, error) {
		calls.Add(1)
		return sampleLocations(), nil
	}
	if _, err := restarted.LoadServers(); err != nil || calls.Load() != 1 {
		t.Fatalf("expired snapshot not refreshed: err=%v calls=%d", err, calls.Load())
	}
}

// An in-memory snapshot that expires while the process is running is discarded
// on the next load rather than kept alive.
func TestServerCacheExpiry(t *testing.T) {
	a := accountApp(t)
	a.mu.Lock()
	a.servers = sampleLocations()
	a.serversCached = true
	a.serversUntil = time.Now().Add(-time.Second)
	a.mu.Unlock()

	var calls atomic.Int32
	a.fetchServers = func() ([]fxa.Location, error) {
		calls.Add(1)
		return sampleLocations(), nil
	}
	if _, err := a.LoadServers(); err != nil || calls.Load() != 1 {
		t.Fatalf("expired in-memory snapshot reused: err=%v calls=%d", err, calls.Load())
	}
}
