package engine

import (
	"crypto/rand"
	"encoding/binary"
	"errors"
	"net/netip"
	"strings"
	"sync"

	"golang.org/x/net/dns/dnsmessage"
)

// fakeDNS maps synthetic addresses to host names for the lifetime of one TUN
// session. Addresses are never reused during a session: an application may
// retain a DNS result beyond its TTL and must not reach a different host.
// The randomized initial offset reduces collisions with cached answers from a
// previous session, although stale addresses are not a reliable cross-session
// identity and this mechanism does not provide persistence across restarts.
type fakeDNS struct {
	mu        sync.RWMutex
	byName    map[string]netip.Addr
	byAddr    map[netip.Addr]string
	start     uint32
	allocated uint32
	direct    func(string) bool
}

const fakeCount uint32 = (1 << 17) - 2

var fakePrefix = netip.MustParsePrefix("198.18.0.0/15")

func newFakeDNS(direct func(string) bool) (*fakeDNS, error) {
	var seed [4]byte
	if _, err := rand.Read(seed[:]); err != nil {
		return nil, err
	}
	return &fakeDNS{
		byName: make(map[string]netip.Addr),
		byAddr: make(map[netip.Addr]string),
		start:  binary.BigEndian.Uint32(seed[:]) % fakeCount,
		direct: direct,
	}, nil
}

// lookup returns whether an address is in the synthetic range and, separately,
// whether it belongs to this session. An unknown synthetic address must fail
// closed rather than being sent to an upstream proxy as an ordinary address.
func (f *fakeDNS) lookup(address netip.Addr) (string, bool, bool) {
	address = address.Unmap()
	if !fakePrefix.Contains(address) {
		return "", false, false
	}
	f.mu.RLock()
	name, ok := f.byAddr[address]
	f.mu.RUnlock()
	return name, true, ok
}

func fakeDomain(s string) bool {
	if len(s) == 0 || len(s) > 253 || !strings.Contains(s, ".") {
		return false
	}
	// Special-use local names and Windows' DNS connectivity check must not
	// receive fabricated public addresses. Leave them to the real resolver;
	// do not hard-code a provider address that may change over time.
	for _, suffix := range []string{"localhost", "local", "home.arpa"} {
		if s == suffix || strings.HasSuffix(s, "."+suffix) {
			return false
		}
	}
	if s == "dns.msftncsi.com" {
		return false
	}
	for _, label := range strings.Split(s, ".") {
		if len(label) == 0 || len(label) > 63 || label[0] == '-' || label[len(label)-1] == '-' {
			return false
		}
		for _, c := range label {
			if !(c >= 'a' && c <= 'z' || c >= '0' && c <= '9' || c == '-') {
				return false
			}
		}
	}
	return true
}

func (f *fakeDNS) address(name string) (netip.Addr, error) {
	f.mu.Lock()
	defer f.mu.Unlock()
	if address, ok := f.byName[name]; ok {
		return address, nil
	}
	if f.allocated >= fakeCount {
		return netip.Addr{}, errors.New("synthetic DNS address pool exhausted")
	}
	// Exclude the first and last address of the reserved range.
	offset := 1 + (f.start+f.allocated)%fakeCount
	value := uint32(198)<<24 | uint32(18)<<16 | offset
	address := netip.AddrFrom4([4]byte{byte(value >> 24), byte(value >> 16), byte(value >> 8), byte(value)})
	f.byName[name] = address
	f.byAddr[address] = name
	f.allocated++
	return address, nil
}

// answer handles ordinary A and AAAA questions for proxied domains only.
// Direct split-tunnel domains, DNSSEC requests and other record types continue
// to the real resolver; a fabricated answer must not claim DNSSEC validation.
// The boolean says whether the query was handled, including allocation errors.
func (f *fakeDNS) answer(query []byte) ([]byte, bool, error) {
	var message dnsmessage.Message
	if err := message.Unpack(query); err != nil || message.Response || message.OpCode != 0 || len(message.Questions) != 1 {
		return nil, false, nil
	}
	q := message.Questions[0]
	if q.Class != dnsmessage.ClassINET || (q.Type != dnsmessage.TypeA && q.Type != dnsmessage.TypeAAAA) {
		return nil, false, nil
	}
	for _, extra := range message.Additionals {
		if extra.Header.Type == dnsmessage.TypeOPT && extra.Header.TTL&0x8000 != 0 {
			return nil, false, nil
		}
	}
	name := strings.ToLower(strings.TrimSuffix(q.Name.String(), "."))
	if !fakeDomain(name) || (f.direct != nil && f.direct(name)) {
		return nil, false, nil
	}
	reply := dnsmessage.Message{
		Header:    dnsmessage.Header{ID: message.ID, Response: true, RecursionDesired: message.RecursionDesired, RecursionAvailable: true},
		Questions: message.Questions,
	}
	if q.Type == dnsmessage.TypeA {
		addr, err := f.address(name)
		if err != nil {
			return nil, true, err
		}
		reply.Answers = []dnsmessage.Resource{{
			Header: dnsmessage.ResourceHeader{Name: q.Name, Type: dnsmessage.TypeA, Class: dnsmessage.ClassINET, TTL: 60},
			Body:   &dnsmessage.AResource{A: addr.As4()},
		}}
	}
	wire, err := reply.Pack()
	return wire, true, err
}
