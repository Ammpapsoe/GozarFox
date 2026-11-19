package engine

import (
	"crypto/rand"
	"encoding/binary"
	"errors"
	"net/netip"
	"strings"

	"golang.org/x/net/dns/dnsmessage"
)

// allowSyntheticDatagram rejects unknown or newly-direct synthetic identities
// before allocating a UDP endpoint. FakeDNS is not enabled in production yet.
func (e *Engine) allowSyntheticDatagram(host string) bool {
	if !e.syntheticAddress(host) {
		return true
	}
	address, err := netip.ParseAddr(host)
	if err != nil {
		return false
	}
	name, _, known := e.fakeDNS.lookup(address)
	return known && e.dns != nil && (e.split == nil || !e.split.Direct(name))
}

// resolveDatagramTarget converts a known synthetic address to a real address
// through the engine's tunneled DNS resolver. WARP accepts IPv4 addresses,
// not hostnames. No system resolver or direct socket is used as a fallback.
func (e *Engine) resolveDatagramTarget(host string) (string, error) {
	if !e.syntheticAddress(host) {
		return host, nil
	}
	address, err := netip.ParseAddr(host)
	if err != nil {
		return "", err
	}
	name, _, known := e.fakeDNS.lookup(address)
	if !known || e.dns == nil {
		return "", errors.New("unknown synthetic UDP destination")
	}
	if e.split != nil && e.split.Direct(name) {
		return "", errors.New("synthetic UDP destination changed routing policy")
	}
	dnsName, err := dnsmessage.NewName(name + ".")
	if err != nil {
		return "", err
	}
	var idBytes [2]byte
	if _, err := rand.Read(idBytes[:]); err != nil {
		return "", err
	}
	question := dnsmessage.Question{Name: dnsName, Type: dnsmessage.TypeA, Class: dnsmessage.ClassINET}
	query := dnsmessage.Message{Header: dnsmessage.Header{ID: binary.BigEndian.Uint16(idBytes[:]), RecursionDesired: true}, Questions: []dnsmessage.Question{question}}
	wire, err := query.Pack()
	if err != nil {
		return "", err
	}
	answer, err := e.dns.Resolve(wire)
	if err != nil {
		return "", err
	}
	var msg dnsmessage.Message
	if err := msg.Unpack(answer); err != nil {
		return "", err
	}
	if !msg.Response || msg.ID != query.ID || msg.OpCode != 0 || msg.Truncated || msg.RCode != dnsmessage.RCodeSuccess || len(msg.Questions) != 1 || msg.Questions[0] != question {
		return "", errors.New("invalid tunneled DNS answer for synthetic UDP")
	}
	// Follow only records in the answer's CNAME chain, never unrelated A
	// records or additional-section glue. Bound aliases to avoid loops.
	current := strings.ToLower(dnsName.String())
	seen := make(map[string]bool)
	for i := 0; i < 16; i++ {
		if seen[current] {
			return "", errors.New("synthetic UDP DNS alias loop")
		}
		seen[current] = true
		next := ""
		for _, record := range msg.Answers {
			if record.Header.Class != dnsmessage.ClassINET || strings.ToLower(record.Header.Name.String()) != current {
				continue
			}
			switch body := record.Body.(type) {
			case *dnsmessage.AResource:
				target := netip.AddrFrom4(body.A)
				if !target.IsGlobalUnicast() || target.IsPrivate() || fakePrefix.Contains(target) {
					continue
				}
				if e.split != nil && e.split.Direct(name) {
					return "", errors.New("synthetic UDP destination changed routing policy")
				}
				return target.String(), nil
			case *dnsmessage.CNAMEResource:
				next = strings.ToLower(body.CNAME.String())
			}
		}
		if next == "" {
			return "", errors.New("no usable IPv4 answer for synthetic UDP")
		}
		current = next
	}
	return "", errors.New("synthetic UDP DNS alias chain too long")
}
