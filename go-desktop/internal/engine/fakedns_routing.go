package engine

import (
	"errors"
	"io"
	"net/netip"
)

// resolveDNS is shared by the UDP and TCP DNS handlers. A handled synthetic
// allocation failure becomes SERVFAIL at the caller, never a real-IP fallback.
func (e *Engine) resolveDNS(query []byte) ([]byte, error) {
	if e.fakeDNS != nil {
		answer, handled, err := e.fakeDNS.answer(query)
		if handled {
			return answer, err
		}
	}
	return e.dns.Resolve(query)
}

func (e *Engine) syntheticAddress(host string) bool {
	if e.fakeDNS == nil {
		return false
	}
	address, err := netip.ParseAddr(host)
	return err == nil && fakePrefix.Contains(address.Unmap())
}

// dialTCP resolves synthetic identities before evaluating address-based split
// routing. Missing mappings are refused rather than leaked as literal IPs.
func (e *Engine) dialTCP(host string, port int) (io.ReadWriteCloser, error) {
	address, err := netip.ParseAddr(host)
	if err != nil {
		return nil, err
	}
	if e.fakeDNS != nil {
		name, synthetic, known := e.fakeDNS.lookup(address)
		if synthetic {
			if !known {
				return nil, errors.New("unknown synthetic DNS destination")
			}
			if e.split != nil && e.split.Direct(name) {
				// Policy may have changed after the answer was cached. Do not
				// silently proxy a domain that now requires direct routing.
				return nil, errors.New("synthetic DNS destination changed routing policy")
			}
			return e.dial(name, port)
		}
	}
	if e.split != nil && e.direct != nil && e.split.DirectAddress(address) {
		return e.direct(host, port)
	}
	return e.dial(host, port)
}
