package geo

import (
	_ "embed"
	"errors"
	"net/netip"
	"strings"
	"sync"
	"time"

	"golang.org/x/net/dns/dnsmessage"
)

//go:embed ir-domains.txt
var irDomains string

//go:embed ir-ipv4.cidr
var irIPv4 string

//go:embed ir-ipv6.cidr
var irIPv6 string

const maxLearned = 8192

var defaultPrefixes = append(parsePrefixes(irIPv4), parsePrefixes(irIPv6)...)

type Rules struct {
	mu      sync.RWMutex
	domains  map[string]struct{}
	learned  map[netip.Addr]learnedEntry
	prefixes []netip.Prefix
}

type learnedEntry struct {
	domain  string
	expires time.Time
}

func Default() *Rules {
	rules, err := Parse([]byte(irDomains))
	if err != nil {
		panic(err)
	}
	rules.prefixes = append(parsePrefixes(irIPv4), parsePrefixes(irIPv6)...)
	return rules
}

func parsePrefixes(data string) []netip.Prefix {
	var prefixes []netip.Prefix
	for _, line := range strings.Split(data, "\n") {
		line = strings.TrimSpace(line)
		if line == "" || strings.HasPrefix(line, "#") {
			continue
		}
		if prefix, err := netip.ParsePrefix(line); err == nil {
			prefixes = append(prefixes, prefix.Masked())
		}
	}
	return prefixes
}

func Parse(data []byte) (*Rules, error) {
	domains := make(map[string]struct{})
	for _, line := range strings.Split(string(data), "\n") {
		line = strings.TrimSpace(line)
		if line == "" || strings.HasPrefix(line, "#") {
			continue
		}
		domain, ok := normalize(line)
		if !ok || !validDomain(domain) {
			return nil, errors.New("invalid domain in split-tunnel list")
		}
		domains[domain] = struct{}{}
		if len(domains) > 20000 {
			return nil, errors.New("split-tunnel list is too large")
		}
	}
	if len(domains) == 0 {
		return nil, errors.New("split-tunnel list is empty")
	}
	return &Rules{domains: domains, learned: make(map[netip.Addr]learnedEntry)}, nil
}

func normalize(host string) (string, bool) {
	host = strings.ToLower(strings.TrimSpace(host))
	host = strings.TrimSuffix(host, ".")
	host = strings.Trim(host, "[]")
	if host == "" || len(host) > 253 {
		return "", false
	}
	return host, true
}

func validDomain(domain string) bool {
	if _, err := netip.ParseAddr(domain); err == nil {
		return false
	}
	labels := strings.Split(domain, ".")
	if len(labels) < 2 {
		return false
	}
	for _, label := range labels {
		if len(label) == 0 || len(label) > 63 || label[0] == '-' || label[len(label)-1] == '-' {
			return false
		}
		for _, ch := range label {
			if !(ch >= 'a' && ch <= 'z' || ch >= '0' && ch <= '9' || ch == '-') {
				return false
			}
		}
	}
	return true
}

func (r *Rules) Replace(data []byte) error {
	next, err := Parse(data)
	if err != nil {
		return err
	}
	r.mu.Lock()
	r.domains = next.domains
	r.mu.Unlock()
	return nil
}

func (r *Rules) Direct(host string) bool {
	name, ok := normalize(host)
	if !ok {
		return false
	}
	if address, err := netip.ParseAddr(name); err == nil {
		return r.DirectAddress(address)
	}
	if name == "ir" || strings.HasSuffix(name, ".ir") {
		return true
	}
	r.mu.RLock()
	defer r.mu.RUnlock()
	return r.matchLocked(name)
}

func (r *Rules) matchLocked(name string) bool {
	if name == "ir" || strings.HasSuffix(name, ".ir") {
		return true
	}
	for {
		if _, ok := r.domains[name]; ok {
			return true
		}
		index := strings.IndexByte(name, '.')
		if index < 0 {
			return false
		}
		name = name[index+1:]
	}
}

func (r *Rules) DirectAddress(address netip.Addr) bool {
	address = address.Unmap()
	r.mu.RLock()
	entry, ok := r.learned[address]
	learnedOK := ok && time.Now().Before(entry.expires) && r.matchLocked(entry.domain)
	r.mu.RUnlock()
	if learnedOK {
		return true
	}
	return IranianAddress(address)
}

func IranianPrefixes() []netip.Prefix {
	return append([]netip.Prefix(nil), defaultPrefixes...)
}

func IranianAddress(address netip.Addr) bool {
	address = address.Unmap()
	for _, prefix := range defaultPrefixes {
		if prefix.Contains(address) {
			return true
		}
	}
	return false
}

func (r *Rules) Learn(query, answer []byte) {
	var question, reply dnsmessage.Message
	if question.Unpack(query) != nil || reply.Unpack(answer) != nil || !reply.Response {
		return
	}
	if len(question.Questions) == 0 {
		return
	}
	name, ok := normalize(question.Questions[0].Name.String())
	if !ok {
		return
	}
	now := time.Now()
	r.mu.Lock()
	defer r.mu.Unlock()
	for _, resource := range reply.Answers {
		var address netip.Addr
		switch body := resource.Body.(type) {
		case *dnsmessage.AResource:
			address = netip.AddrFrom4(body.A)
		case *dnsmessage.AAAAResource:
			address = netip.AddrFrom16(body.AAAA)
		default:
			continue
		}
		ttl := time.Duration(resource.Header.TTL) * time.Second
		if ttl <= 0 || ttl > 5*time.Minute {
			ttl = 5 * time.Minute
		}
		if len(r.learned) >= maxLearned {
			for key, entry := range r.learned {
				if now.After(entry.expires) {
					delete(r.learned, key)
				}
			}
			if len(r.learned) >= maxLearned {
				clear(r.learned)
			}
		}
		r.learned[address] = learnedEntry{domain: name, expires: now.Add(ttl)}
	}
}
