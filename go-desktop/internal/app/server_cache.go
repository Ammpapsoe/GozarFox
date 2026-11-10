package app

import (
	"encoding/json"
	"net"
	"strings"
	"time"
	"unicode"

	"github.com/bardia049b-stack/Gozar1/go-desktop/internal/config"
	"github.com/bardia049b-stack/Gozar1/go-desktop/internal/fxa"
)

// A cached Mozilla list is a bootstrap hint, not a permanent server registry.
// It is refreshed through the verified tunnel and cannot outlive this bound on
// disk. It contains no account credentials. Its sealed envelope also carries
// the timestamp so modified or replayed plaintext is not trusted on restart.
const serverCacheLifetime = 24 * time.Hour
const serverCacheMaxEntries = 512

type serverSnapshot struct {
	Version   int            `json:"version"`
	FetchedAt int64          `json:"fetched_at"`
	Locations []fxa.Location `json:"locations"`
}

func cacheHostOK(host string) bool {
	if len(host) == 0 || len(host) > 253 || net.ParseIP(host) != nil {
		return false
	}
	labels := strings.Split(host, ".")
	if len(labels) < 2 {
		return false
	}
	for _, label := range labels {
		if len(label) == 0 || len(label) > 63 || label[0] == '-' || label[len(label)-1] == '-' {
			return false
		}
		for _, c := range label {
			if !(c >= 'a' && c <= 'z') && !(c >= 'A' && c <= 'Z') && !(c >= '0' && c <= '9') && c != '-' {
				return false
			}
		}
	}
	return true
}

func cacheLocationOK(place fxa.Location) bool {
	if len(place.Code) == 0 || len(place.Code) > 16 || len(place.Country) > 200 || len(place.City) > 200 ||
		place.Port < 1 || place.Port > 65535 || !cacheHostOK(place.Host) {
		return false
	}
	for _, c := range place.Code {
		if !(c >= 'a' && c <= 'z') && !(c >= 'A' && c <= 'Z') && !(c >= '0' && c <= '9') && c != '-' && c != '_' {
			return false
		}
	}
	for _, text := range []string{place.Country, place.City} {
		for _, c := range text {
			if unicode.IsControl(c) {
				return false
			}
		}
	}
	return true
}

// Call only with a fresh successful TLS-verified Mozilla server-list response.
// Invalid records are omitted; an empty response cannot replace a good cache.
func encodeServerCache(locations []fxa.Location, now time.Time) string {
	if now.Unix() <= 0 || len(locations) == 0 || len(locations) > serverCacheMaxEntries {
		return ""
	}
	valid := make([]fxa.Location, 0, len(locations))
	for _, location := range locations {
		if cacheLocationOK(location) {
			valid = append(valid, location)
		}
	}
	if len(valid) == 0 {
		return ""
	}
	raw, err := json.Marshal(serverSnapshot{Version: 1, FetchedAt: now.Unix(), Locations: valid})
	if err != nil || len(raw) > 1<<20 {
		return ""
	}
	return string(raw)
}

func decodeServerCache(raw string, now time.Time) ([]fxa.Location, time.Time) {
	if len(raw) == 0 || len(raw) > 1<<20 {
		return nil, time.Time{}
	}
	var snap serverSnapshot
	if json.Unmarshal([]byte(raw), &snap) != nil || snap.Version != 1 || snap.FetchedAt <= 0 ||
		len(snap.Locations) == 0 || len(snap.Locations) > serverCacheMaxEntries {
		return nil, time.Time{}
	}
	fetched := time.Unix(snap.FetchedAt, 0)
	expires := fetched.Add(serverCacheLifetime)
	if fetched.After(now.Add(5*time.Minute)) || !expires.After(now) {
		return nil, time.Time{}
	}
	for _, location := range snap.Locations {
		if !cacheLocationOK(location) {
			return nil, time.Time{}
		}
	}
	return append([]fxa.Location(nil), snap.Locations...), expires
}

// Must hold a.mu. A list loaded from disk is never indefinitely kept alive by
// reconnects: only a fresh verified settings response updates its timestamp.
func (a *App) expireServersLocked(now time.Time) {
	if !a.serversUntil.IsZero() && !a.serversUntil.After(now) {
		a.servers = nil
		a.serversUntil = time.Time{}
		a.serversCached = false
	}
}

// Must hold a.mu. Never replace a persisted good snapshot with an empty or
// invalid one. A fresh in-memory list can still be used without a cache.
func (a *App) saveFreshServersLocked(locations []fxa.Location, now time.Time) error {
	a.servers = append([]fxa.Location(nil), locations...)
	a.serversUntil = now.Add(serverCacheLifetime)
	a.serversCached = false
	encoded := encodeServerCache(locations, now)
	if encoded == "" {
		return nil
	}
	next := a.cfg
	next.ServerCache = encoded
	if err := config.Save(next); err != nil {
		return err
	}
	a.cfg = next
	return nil
}

// Refresh the public list only through the live account's current tunnel. A
// late response from a stopped or replaced transport cannot update the cache.
func (a *App) refreshServersThroughTunnel() {
	a.mu.Lock()
	transport, revision, live := a.transport, a.sessionRevision, a.live
	a.mu.Unlock()
	if !live || transport == nil {
		return
	}
	client, closeIdle, ok := a.controlClient()
	if !ok {
		return
	}
	defer closeIdle()
	locations, err := fxa.ServersThrough(client)
	if err != nil || len(locations) == 0 {
		return
	}
	a.mu.Lock()
	defer a.mu.Unlock()
	if !a.live || a.transport != transport || a.sessionRevision != revision {
		return
	}
	_ = a.saveFreshServersLocked(locations, time.Now())
}
