package engine

import (
	"errors"
	"io"
	"net/netip"
	"runtime"
	"testing"

	"golang.org/x/net/dns/dnsmessage"
)

// Model an address retained by an application from an unrelated resolver:
// for example ArmanLife's desktop allocator starts at 198.18.0.4. This test
// intentionally demonstrates the unresolved ambiguity; it does not import or
// run the other project's code. The receiving engine sees only an address, not
// the name or namespace the application originally associated with it.
func TestFakeDNSForeignCachedAddressCanAliasKnownIdentity(t *testing.T) {
	foreignCached := netip.MustParseAddr("198.18.0.4")
	journal := &memoryDNSJournal{exists: true, start: 3}
	fake, err := newJournalFakeDNS(journal, nil)
	if err != nil {
		t.Fatal(err)
	}
	local, err := fake.address("local.example")
	if err != nil || local != foreignCached {
		t.Fatalf("collision setup: %s, %v", local, err)
	}
	var dialed string
	e := &Engine{fakeDNS: fake, dial: func(host string, port int) (io.ReadWriteCloser, error) {
		dialed = host
		return nil, errors.New("stop after observing destination")
	}}
	_, err = e.dialTCP(foreignCached.String(), 443)
	if err == nil || dialed != "local.example" {
		t.Fatalf("collision was not reproduced: host=%q err=%v", dialed, err)
	}
	var queried string
	e.dns = fakeUDPResolver{answer: func(q dnsmessage.Message) (dnsmessage.Message, error) {
		queried = q.Questions[0].Name.String()
		return syntheticUDPAnswer(q, [4]byte{1, 1, 1, 1}), nil
	}}
	target, err := e.resolveDatagramTarget(foreignCached.String())
	if err != nil || queried != "local.example." || target != "1.1.1.1" {
		t.Fatalf("UDP collision was not reproduced: name=%q target=%q err=%v", queried, target, err)
	}
}

// Only Windows has the cache-flush mitigation; other platforms must not
// provision state, create a tunnel, or silently downgrade through this API.
func TestFakeDNSProductionEntryPointUnsupportedPlatforms(t *testing.T) {
	if runtime.GOOS == "windows" {
		t.Skip("Windows has a separately tested production starter")
	}
	var dialed bool
	dial := func(string, int) (io.ReadWriteCloser, error) { dialed = true; return nil, nil }
	e, err := StartSplitUDPWithFakeDNS("test.invalid", dial, dial, nil, nil, nil)
	if e != nil || !errors.Is(err, ErrFakeDNSUnsafeNamespace) || dialed {
		t.Fatalf("unsafe startup was not refused: engine=%v err=%v dialed=%v", e, err, dialed)
	}
}
