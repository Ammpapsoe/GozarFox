package engine

import (
	"net/netip"
	"strings"
	"sync"
	"testing"

	"golang.org/x/net/dns/dnsmessage"
)

func fakeQuery(t *testing.T, host string, typ dnsmessage.Type) []byte {
	t.Helper()
	name, err := dnsmessage.NewName(host + ".")
	if err != nil {
		t.Fatal(err)
	}
	query := dnsmessage.Message{Header: dnsmessage.Header{ID: 42, RecursionDesired: true}, Questions: []dnsmessage.Question{{Name: name, Type: typ, Class: dnsmessage.ClassINET}}}
	out, err := query.Pack()
	if err != nil {
		t.Fatal(err)
	}
	return out
}

func TestFakeDNSRoundTripAndAAAA(t *testing.T) {
	f, err := newFakeDNS(nil)
	if err != nil {
		t.Fatal(err)
	}
	out, handled, err := f.answer(fakeQuery(t, "EXAMPLE.org", dnsmessage.TypeA))
	if err != nil || !handled {
		t.Fatalf("A: handled=%t err=%v", handled, err)
	}
	var reply dnsmessage.Message
	if err := reply.Unpack(out); err != nil {
		t.Fatal(err)
	}
	if reply.ID != 42 || !reply.Response || len(reply.Answers) != 1 || reply.Answers[0].Header.TTL != 60 {
		t.Fatalf("bad response: %+v", reply)
	}
	address := netip.AddrFrom4(reply.Answers[0].Body.(*dnsmessage.AResource).A)
	if name, synthetic, ok := f.lookup(address); !synthetic || !ok || name != "example.org" {
		t.Fatalf("lookup: %s %t %t", name, synthetic, ok)
	}
	again, _, _ := f.answer(fakeQuery(t, "example.ORG", dnsmessage.TypeA))
	var second dnsmessage.Message
	if err := second.Unpack(again); err != nil {
		t.Fatal(err)
	}
	if got := netip.AddrFrom4(second.Answers[0].Body.(*dnsmessage.AResource).A); got != address {
		t.Fatalf("name case changed the allocation: %v != %v", got, address)
	}
	out, handled, err = f.answer(fakeQuery(t, "example.org", dnsmessage.TypeAAAA))
	if err != nil || !handled || reply.Unpack(out) != nil || len(reply.Answers) != 0 || reply.RCode != dnsmessage.RCodeSuccess {
		t.Fatalf("AAAA was not a successful empty response: handled=%t err=%v", handled, err)
	}
}

func TestFakeDNSPassesDirectAndOtherQuestions(t *testing.T) {
	f, err := newFakeDNS(func(name string) bool { return strings.HasSuffix(name, ".ir") })
	if err != nil {
		t.Fatal(err)
	}
	for _, tc := range []struct {
		host string
		typ  dnsmessage.Type
	}{
		{"example.ir", dnsmessage.TypeA}, {"printer.local", dnsmessage.TypeA}, {"host.home.arpa", dnsmessage.TypeA},
		{"example.org", dnsmessage.TypeMX}, {"printer", dnsmessage.TypeA},
	} {
		if _, handled, err := f.answer(fakeQuery(t, tc.host, tc.typ)); handled || err != nil {
			t.Errorf("%s %v: handled=%t err=%v", tc.host, tc.typ, handled, err)
		}
	}
	query := fakeQuery(t, "example.org", dnsmessage.TypeA)
	var msg dnsmessage.Message
	if err := msg.Unpack(query); err != nil {
		t.Fatal(err)
	}
	msg.Additionals = []dnsmessage.Resource{{Header: dnsmessage.ResourceHeader{Name: dnsmessage.MustNewName("."), Type: dnsmessage.TypeOPT, Class: 1232, TTL: 0x8000}, Body: &dnsmessage.OPTResource{}}}
	query, err = msg.Pack()
	if err != nil {
		t.Fatal(err)
	}
	if _, handled, err := f.answer(query); handled || err != nil {
		t.Fatalf("DNSSEC request synthesized: handled=%t err=%v", handled, err)
	}
}

func TestFakeDNSConcurrentAndNeverReuses(t *testing.T) {
	f, err := newFakeDNS(nil)
	if err != nil {
		t.Fatal(err)
	}
	var wg sync.WaitGroup
	for i := 0; i < 40; i++ {
		wg.Add(1)
		go func() {
			defer wg.Done()
			if _, err := f.address("example.org"); err != nil {
				t.Error(err)
			}
		}()
	}
	wg.Wait()
	if len(f.byName) != 1 || len(f.byAddr) != 1 {
		t.Fatal("concurrent allocations diverged")
	}
	first, _ := f.address("example.org")
	second, err := f.address("example.net")
	if err != nil || first == second {
		t.Fatalf("address reuse: %s %s %v", first, second, err)
	}
	f.allocated = fakeCount
	if _, err := f.address("new.example"); err == nil {
		t.Fatal("exhausted pool must fail")
	}
	if got, err := f.address("example.org"); err != nil || got != first {
		t.Fatal("existing entry lost when full")
	}
	if _, synthetic, ok := f.lookup(netip.MustParseAddr("198.18.0.0")); !synthetic || ok {
		t.Fatal("unknown synthetic address not classified")
	}
	if _, synthetic, _ := f.lookup(netip.MustParseAddr("203.0.113.1")); synthetic {
		t.Fatal("real address misclassified")
	}
}
