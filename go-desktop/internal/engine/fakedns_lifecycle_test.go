package engine

import (
	"errors"
	"net/netip"
	"sync/atomic"
	"testing"
	"time"

	"github.com/Ammpapsoe/GozarFox/go-desktop/internal/geo"
	"golang.org/x/net/dns/dnsmessage"
)

type countingDNSOwner struct{ closes atomic.Int32 }

func (o *countingDNSOwner) Close() error { o.closes.Add(1); return nil }

func TestFakeDNSLifecycleDrainsInFlightPublication(t *testing.T) {
	fake, err := newFakeDNS(nil)
	if err != nil {
		t.Fatal(err)
	}
	owner := &countingDNSOwner{}
	e := &Engine{fakeDNS: fake, fakeOwner: owner, dns: &mockResolver{}}
	started := make(chan struct{})
	release := make(chan struct{})
	done := make(chan error, 1)
	query := fakeQuery(t, "pending.example", dnsmessage.TypeA)
	go func() {
		done <- e.publishDNS(query, false, func([]byte) error { close(started); <-release; return nil })
	}()
	select {
	case <-started:
	case <-time.After(3 * time.Second):
		t.Fatal("publication did not start")
	}
	closed := make(chan error, 1)
	go func() { closed <- e.closeDNSPublication() }()
	select {
	case <-closed:
		t.Fatal("closed owner before answer finished")
	case <-time.After(50 * time.Millisecond):
	}
	if owner.closes.Load() != 0 {
		t.Fatal("owner closed while publishing")
	}
	close(release)
	if err := <-done; err != nil {
		t.Fatal(err)
	}
	if err := <-closed; err != nil {
		t.Fatal(err)
	}
	published := false
	if err := e.publishDNS(fakeQuery(t, "later.example", dnsmessage.TypeA), false, func([]byte) error { published = true; return nil }); err == nil || published {
		t.Fatal("published after close")
	}
	if err := e.closeDNSPublication(); err != nil || owner.closes.Load() != 1 {
		t.Fatal("owner not closed exactly once", err)
	}
}

func TestFakeDNSLifecycleFailedStartupReleasesOwner(t *testing.T) {
	owner := &countingDNSOwner{}
	_, err := startUDPManaged("", nil, []netip.Addr{}, nil, nil, nil, nil, nil, owner)
	if err == nil || owner.closes.Load() != 1 {
		t.Fatal("invalid startup did not close its owner", err)
	}
}

func TestFakeDNSLifecycleLearnsDirectAddressBeforePublishing(t *testing.T) {
	rules, err := geo.Parse([]byte("direct.example\n"))
	if err != nil {
		t.Fatal(err)
	}
	fake, err := newFakeDNS(rules.Direct)
	if err != nil {
		t.Fatal(err)
	}
	e := &Engine{fakeDNS: fake, split: rules, dns: fakeUDPResolver{answer: func(q dnsmessage.Message) (dnsmessage.Message, error) {
		return syntheticUDPAnswer(q, [4]byte{1, 1, 1, 1}), nil
	}}}
	for _, udp := range []bool{false, true} {
		err = e.publishDNS(fakeQuery(t, "direct.example", dnsmessage.TypeA), udp, func([]byte) error {
			if !rules.DirectAddress(netip.MustParseAddr("1.1.1.1")) {
				return errors.New("direct route not learned before response write")
			}
			return nil
		})
		if err != nil {
			t.Fatal(err)
		}
	}
}

func TestFakeDNSLifecyclePublicationWriteFailure(t *testing.T) {
	fake, err := newFakeDNS(nil)
	if err != nil {
		t.Fatal(err)
	}
	owner := &countingDNSOwner{}
	e := &Engine{fakeDNS: fake, fakeOwner: owner, dns: &mockResolver{}}
	writeErr := errors.New("closed stream")
	if err := e.publishDNS(fakeQuery(t, "failed.example", dnsmessage.TypeA), false, func([]byte) error { return writeErr }); !errors.Is(err, writeErr) {
		t.Fatal("write failure not returned", err)
	}
	if err := e.closeDNSPublication(); err != nil || owner.closes.Load() != 1 {
		t.Fatal("owner not released after write failure", err)
	}
}
