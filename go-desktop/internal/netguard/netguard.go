// Package netguard is the desktop core's self-tuning reachability brain:
// it detects DNS poisoning (sanctioned hostnames answered with private
// ranges such as 10.10.34.34), remembers last known-good addresses, and
// segments the TLS ClientHello when a middlebox proves itself hostile.
// Everything is automatic; nothing here appears in user settings.
package netguard

import (
	"context"
	"net"
	"net/http"
	"sync"
	"time"
)

type Mode int

const (
	ModePlain Mode = iota
	ModeSplit
	ModeCached
)

const (
	cacheTTL       = 6 * time.Hour
	quarantine     = 10 * time.Minute
	probeEvery     = 12
	splitHeadBytes = 5 // TLS record header, then the rest of the hello
	splitPause     = 25 * time.Millisecond
)

// Fake reports addresses a public hostname must never resolve to.
func Fake(ip net.IP) bool {
	if ip == nil || ip.IsLoopback() || ip.IsLinkLocalUnicast() || ip.IsLinkLocalMulticast() ||
		ip.IsUnspecified() || ip.IsMulticast() || ip.IsPrivate() {
		return true
	}
	// Carrier-grade NAT space is a common poisoning sinkhole.
	if v4 := ip.To4(); v4 != nil && v4[0] == 100 && v4[1] >= 64 && v4[1] <= 127 {
		return true
	}
	return false
}

// Resolve returns routable answers only; empty means poisoned or failed.
func Resolve(host string) []net.IP {
	addresses, err := net.LookupIP(host)
	if err != nil {
		return nil
	}
	out := make([]net.IP, 0, len(addresses))
	for _, address := range addresses {
		if !Fake(address) {
			out = append(out, address)
		}
	}
	return out
}

// Poisoned reports whether every answer for host is a fake sinkhole.
func Poisoned(host string) bool {
	addresses, err := net.LookupIP(host)
	if err != nil || len(addresses) == 0 {
		return false
	}
	for _, address := range addresses {
		if !Fake(address) {
			return false
		}
	}
	return true
}

var (
	mu     sync.Mutex
	good   = map[string]cacheEntry{}
	states = map[string]*state{}
)

type cacheEntry struct {
	ip string
	at time.Time
}

// Remember stores a last known-good address for host.
func Remember(host, ip string) {
	mu.Lock()
	defer mu.Unlock()
	if len(good) >= 64 {
		for key := range good {
			delete(good, key)
			break
		}
	}
	good[host] = cacheEntry{ip: ip, at: time.Now()}
}

// Cached returns a fresh remembered address, if any.
func Cached(host string) string {
	mu.Lock()
	defer mu.Unlock()
	entry, ok := good[host]
	if !ok || time.Since(entry.at) > cacheTTL {
		delete(good, host)
		return ""
	}
	return entry.ip
}

type measure struct {
	average   float64
	successes int
	failures  int
	until     time.Time
}

type state struct {
	count                int
	plain, split, cached measure
}

func (s *state) of(mode Mode) *measure {
	switch mode {
	case ModeSplit:
		return &s.split
	case ModeCached:
		return &s.cached
	default:
		return &s.plain
	}
}

// Order returns candidate modes, best first, for host right now.
func Order(host string) []Mode {
	mu.Lock()
	defer mu.Unlock()
	st, ok := states[host]
	if !ok {
		if len(states) >= 64 {
			for key := range states {
				delete(states, key)
				break
			}
		}
		st = &state{}
		states[host] = st
	}
	_ = ok
	st.count++
	now := time.Now()
	ready := func(m *measure) bool { return !m.until.After(now) }
	hasCached := Cached(host) != ""
	score := map[Mode]bool{
		ModePlain:  ready(&st.plain),
		ModeSplit:  ready(&st.split),
		ModeCached: hasCached && ready(&st.cached),
	}
	rank := []Mode{ModePlain, ModeSplit, ModeCached}
	best := ModePlain
	bestScore := -1.0
	for _, mode := range rank {
		if !score[mode] {
			continue
		}
		m := st.of(mode)
		value := m.average
		if m.successes == 0 {
			value = 0.5 // untried modes sit in the middle
		}
		weight := value
		if mode == ModePlain {
			weight *= 0.9 // small home advantage for the boring path
		}
		if weight > bestScore {
			bestScore = weight
			best = mode
		}
	}
	out := []Mode{best}
	for _, mode := range rank {
		if mode != best && score[mode] {
			out = append(out, mode)
		}
	}
	return out
}

func stateOf(host string) *state {
	st, ok := states[host]
	if !ok {
		st = &state{}
		states[host] = st
	}
	return st
}

// Success records a timed win for mode.
func Success(host string, mode Mode, elapsed time.Duration) {
	mu.Lock()
	defer mu.Unlock()
	m := stateOf(host).of(mode)
	value := float64(elapsed.Milliseconds())
	if value < 1 {
		value = 1
	}
	if m.successes == 0 {
		m.average = value
	} else {
		m.average = m.average*0.8 + value*0.2
	}
	m.successes++
	m.failures = 0
	m.until = time.Time{}
}

// Failure quarantines a mode after two consecutive losses.
func Failure(host string, mode Mode) {
	mu.Lock()
	defer mu.Unlock()
	m := stateOf(host).of(mode)
	m.failures++
	if m.failures >= 2 {
		m.until = time.Now().Add(quarantine)
		m.failures = 0
	}
}

// SplitConn segments the first write - the TLS ClientHello - into two TCP
// writes, so a middlebox inspecting the first packet sees a record header
// without a parseable hello. Later writes pass through untouched.
type SplitConn struct {
	net.Conn
	once sync.Once
}

func (c *SplitConn) Write(b []byte) (int, error) {
	var err error
	c.once.Do(func() {
		if len(b) > splitHeadBytes+8 {
			if _, err = c.Conn.Write(b[:splitHeadBytes]); err != nil {
				return
			}
			_ = c.Conn.SetNoDelay(true)
			time.Sleep(splitPause)
			_, err = c.Conn.Write(b[splitHeadBytes:])
			return
		}
		_, err = c.Conn.Write(b)
	})
	if err != nil {
		return 0, err
	}
	return len(b), nil
}

// DialContext returns a dialer function that refuses poisoned answers: it
// connects only to routable addresses, falls back to a remembered one when
// the resolver sinks the hostname, and segments the hello when the learner
// currently prefers the split mode for that host.
func DialContext(dialer *net.Dialer) func(ctx context.Context, network, addr string) (net.Conn, error) {
	if dialer == nil {
		dialer = &net.Dialer{Timeout: 25 * time.Second, KeepAlive: 30 * time.Second}
	}
	return func(ctx context.Context, network, addr string) (net.Conn, error) {
		host, port, err := net.SplitHostPort(addr)
		if err != nil {
			return nil, err
		}
		target := host
		if routable := Resolve(host); len(routable) > 0 {
			target = routable[0].String()
			Remember(host, target)
		} else if cached := Cached(host); cached != "" {
			target = cached
		} else if Poisoned(host) {
			return nil, &net.DNSError{Err: "resolver answered only with sinkholed addresses", Name: host, IsNotFound: true}
		}
		conn, err := dialer.DialContext(ctx, network, net.JoinHostPort(target, port))
		if err != nil {
			return nil, err
		}
		if modes := Order(host); len(modes) > 0 && modes[0] == ModeSplit {
			conn = &SplitConn{Conn: conn}
		}
		return conn, nil
	}
}

// GuardedTransport clones base with the netguard dialer installed.
func GuardedTransport(base *http.Transport) *http.Transport {
	transport := base.Clone()
	transport.DialContext = DialContext(nil)
	return transport
}
