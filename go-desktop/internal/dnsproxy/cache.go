package dnsproxy

import (
	"encoding/binary"
	"strings"
	"sync"
	"time"

	"golang.org/x/net/dns/dnsmessage"
)

// responseCache is deliberately a small, conservative cache for plain
// positive A/AAAA responses. It is per tunnel session, bounded in memory and
// never persists browsing history. Everything else, including EDNS/DNSSEC,
// negative answers, aliases and unusual record sections, goes to the upstream
// resolver unchanged. This is not a full recursive DNS cache.
const (
	cacheMaxEntries  = 512
	cacheMaxWire     = 4096
	cacheMaxRecords  = 32
	cacheMaxLifetime = 60 * time.Second
)

type cacheEntry struct {
	wire    []byte
	stored  time.Time
	expires time.Time
}

type responseCache struct {
	mu      sync.Mutex
	entries map[string]cacheEntry
	closed  bool
	now     func() time.Time
}

func newResponseCache() *responseCache {
	return &responseCache{entries: make(map[string]cacheEntry), now: time.Now}
}

// Keep all query bytes except the DNS transaction ID as the cache key. This
// retains exact name casing, flags and question data instead of accidentally
// mixing queries from different clients or resolver policies.
func cacheKey(wire []byte) (string, bool) {
	if len(wire) < 12 || len(wire) > cacheMaxWire {
		return "", false
	}
	var q dnsmessage.Message
	if q.Unpack(wire) != nil || q.Response || q.OpCode != 0 || !q.RecursionDesired || q.Truncated || len(q.Questions) != 1 || len(q.Answers) != 0 || len(q.Authorities) != 0 || len(q.Additionals) != 0 {
		return "", false
	}
	question := q.Questions[0]
	if question.Class != dnsmessage.ClassINET || (question.Type != dnsmessage.TypeA && question.Type != dnsmessage.TypeAAAA) {
		return "", false
	}
	key := make([]byte, len(wire))
	copy(key, wire)
	key[0], key[1] = 0, 0
	return string(key), true
}

func (c *responseCache) get(query []byte) ([]byte, bool) {
	key, ok := cacheKey(query)
	if !ok {
		return nil, false
	}
	c.mu.Lock()
	if c.closed {
		c.mu.Unlock()
		return nil, false
	}
	now := c.now()
	entry, ok := c.entries[key]
	if ok && !now.Before(entry.expires) {
		delete(c.entries, key)
		ok = false
	}
	c.mu.Unlock()
	if !ok {
		return nil, false
	}
	var answer dnsmessage.Message
	if answer.Unpack(entry.wire) != nil {
		return nil, false
	}
	answer.ID = binary.BigEndian.Uint16(query)
	age := now.Sub(entry.stored)
	if age < 0 {
		return nil, false
	}
	// Round elapsed age up so a downstream cache can never extend an RR's
	// original expiry by rounding a fractional second down.
	seconds := uint32((age + time.Second - 1) / time.Second)
	for i := range answer.Answers {
		if seconds >= answer.Answers[i].Header.TTL {
			answer.Answers[i].Header.TTL = 0
		} else {
			answer.Answers[i].Header.TTL -= seconds
		}
	}
	wire, err := answer.Pack()
	return wire, err == nil
}

func (c *responseCache) put(query, wire []byte) {
	key, ok := cacheKey(query)
	if !ok || len(wire) > cacheMaxWire {
		return
	}
	var q, answer dnsmessage.Message
	if q.Unpack(query) != nil || answer.Unpack(wire) != nil || !answer.Response || answer.Truncated || answer.RCode != dnsmessage.RCodeSuccess || len(answer.Answers) == 0 || len(answer.Answers) > cacheMaxRecords || len(answer.Authorities) != 0 || len(answer.Additionals) != 0 {
		return
	}
	lifetime := cacheMaxLifetime
	for _, record := range answer.Answers {
		if record.Header.Type != q.Questions[0].Type || record.Header.Class != dnsmessage.ClassINET || !strings.EqualFold(record.Header.Name.String(), q.Questions[0].Name.String()) || record.Header.TTL == 0 {
			return
		}
		switch record.Body.(type) {
		case *dnsmessage.AResource, *dnsmessage.AAAAResource:
		default:
			return
		}
		ttl := time.Duration(record.Header.TTL) * time.Second
		if ttl < lifetime {
			lifetime = ttl
		}
	}
	c.mu.Lock()
	defer c.mu.Unlock()
	if c.closed {
		return
	}
	now := c.now()
	if len(c.entries) >= cacheMaxEntries {
		// Reclaim expired entries first; if full, evict the oldest entry.
		oldest := ""
		var oldestTime time.Time
		for k, v := range c.entries {
			if !now.Before(v.expires) {
				delete(c.entries, k)
				continue
			}
			if oldestTime.IsZero() || v.stored.Before(oldestTime) {
				oldest, oldestTime = k, v.stored
			}
		}
		if len(c.entries) >= cacheMaxEntries {
			delete(c.entries, oldest)
		}
	}
	c.entries[key] = cacheEntry{wire: append([]byte(nil), wire...), stored: now, expires: now.Add(lifetime)}
}

func (c *responseCache) clear() {
	c.mu.Lock()
	clear(c.entries)
	c.closed = true
	c.mu.Unlock()
}
