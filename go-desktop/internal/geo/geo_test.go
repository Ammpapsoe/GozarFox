package geo

import (
	"net/netip"
	"testing"
	"time"

	"golang.org/x/net/dns/dnsmessage"
)

func TestIranianAndForeignDomains(t *testing.T) {
	rules := Default()
	for _, host := range []string{"aparat.com", "www.aparat.com", "digikala.com", "api.digikala.com", "varzesh3.com", "shad.ir", "sub.shad.ir", "bankmellat.ir", "snapp.ir"} {
		if !rules.Direct(host) {
			t.Fatalf("%s must be direct", host)
		}
	}
	for _, host := range []string{"google.com", "www.google.com", "youtube.com", "youtu.be", "cloudflare.com", "example.com", "notaparat.com", "aparat.com.evil.example"} {
		if rules.Direct(host) {
			t.Fatalf("%s must use the tunnel", host)
		}
	}
}

func TestLearnedDNSAddress(t *testing.T) {
	rules := Default()
	name := dnsmessage.MustNewName("www.aparat.com.")
	query := dnsmessage.Message{Header: dnsmessage.Header{ID: 7}, Questions: []dnsmessage.Question{{Name: name, Type: dnsmessage.TypeA, Class: dnsmessage.ClassINET}}}
	answer := dnsmessage.Message{Header: dnsmessage.Header{ID: 7, Response: true}, Questions: query.Questions, Answers: []dnsmessage.Resource{{Header: dnsmessage.ResourceHeader{Name: name, Type: dnsmessage.TypeA, Class: dnsmessage.ClassINET, TTL: 60}, Body: &dnsmessage.AResource{A: [4]byte{185, 141, 100, 10}}}}}
	q, _ := query.Pack()
	a, _ := answer.Pack()
	rules.Learn(q, a)
	if !rules.DirectAddress(netip.MustParseAddr("185.141.100.10")) {
		t.Fatal("learned Iranian address must be direct")
	}
	if rules.DirectAddress(netip.MustParseAddr("142.250.185.14")) {
		t.Fatal("unlearned foreign address must use the tunnel")
	}
	rules.mu.Lock()
	entry := rules.learned[netip.MustParseAddr("185.141.100.10")]
	entry.expires = time.Now().Add(-time.Second)
	rules.learned[netip.MustParseAddr("185.141.100.10")] = entry
	rules.mu.Unlock()
	if rules.DirectAddress(netip.MustParseAddr("185.141.100.10")) {
		t.Fatal("expired DNS learning must not bypass the tunnel")
	}
}

func TestReplaceKeepsLastGoodList(t *testing.T) {
	rules := Default()
	if err := rules.Replace([]byte("# comment\nexample.ir\nbad_domain\n")); err == nil {
		t.Fatal("invalid list was accepted")
	}
	if !rules.Direct("aparat.com") {
		t.Fatal("invalid update replaced the working list")
	}
	if err := rules.Replace([]byte("custom-service.com\n")); err != nil {
		t.Fatal(err)
	}
	if !rules.Direct("cdn.custom-service.com") || rules.Direct("aparat.com") {
		t.Fatal("valid update was not applied")
	}
}
