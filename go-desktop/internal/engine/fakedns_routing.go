package engine

import (
	"errors"
	"io"
	"net/netip"

	"github.com/Ammpapsoe/GozarFox/go-desktop/internal/dnsproxy"
)

// publishDNS holds a read lock through actual network publication, not just
// allocation: Stop cannot release ownership while an answer is still being
// written. After closure every subsequent request fails without publishing.
func (e *Engine) publishDNS(query []byte, udp bool, write func([]byte) error) error {
	e.dnsPublishMu.RLock()
	defer e.dnsPublishMu.RUnlock()
	if e.dnsPublishClosed {
		return errors.New("DNS publication stopped")
	}
	answer, err := e.resolveDNS(query)
	if err != nil {
		answer = dnsproxy.Failure(query)
	}
	if udp {
		answer = dnsproxy.LimitUDP(query, answer)
	}
	if len(answer) == 0 {
		if udp {
			return nil
		}
		return errors.New("empty DNS answer")
	}
	if len(answer) > 65535 {
		return errors.New("DNS answer too large")
	}
	// Learn direct-routing answers before publication: the client may open a
	// connection as soon as its response is written on another thread.
	if e.split != nil {
		e.split.Learn(query, answer)
	}
	if err := write(answer); err != nil {
		return err
	}
	e.down.Add(int64(len(answer)))
	return nil
}

func (e *Engine) closeDNSPublication() error {
	e.dnsPublishMu.Lock()
	defer e.dnsPublishMu.Unlock()
	if e.dnsPublishClosed {
		return nil
	}
	e.dnsPublishClosed = true
	if e.fakeOwner == nil {
		return nil
	}
	err := e.fakeOwner.Close()
	e.fakeOwner = nil
	return err
}

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
