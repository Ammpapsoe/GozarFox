package engine

import (
	"bytes"
	"testing"

	"golang.org/x/net/dns/dnsmessage"
)

type recordingProtocolResolver struct{ got []byte }

func (r *recordingProtocolResolver) Resolve(query []byte) ([]byte, error) {
	r.got = append([]byte(nil), query...)
	return []byte("resolver response"), nil
}
func (*recordingProtocolResolver) Close() {}

func TestFakeDNSComplexQueriesPassUnchangedToResolver(t *testing.T) {
	// The prototype cannot honor EDNS options, DNSSEC signalling or signed
	// additional records. Forwarding them unchanged prevents silent stripping.
	root := dnsmessage.MustNewName(".")
	opt := dnsmessage.Resource{Header: dnsmessage.ResourceHeader{Name: root, Type: dnsmessage.TypeOPT, Class: 1232}, Body: &dnsmessage.OPTResource{}}
	withOption := dnsmessage.Resource{Header: opt.Header, Body: &dnsmessage.OPTResource{Options: []dnsmessage.Option{{Code: 10, Data: []byte{1, 2, 3, 4, 5, 6, 7, 8}}}}}
	tests := []struct {
		name string
		edit func(*dnsmessage.Message)
	}{
		{"edns-empty", func(m *dnsmessage.Message) { m.Additionals = []dnsmessage.Resource{opt} }},
		{"edns-cookie", func(m *dnsmessage.Message) { m.Additionals = []dnsmessage.Resource{withOption} }},
		{"edns-dnssec", func(m *dnsmessage.Message) { o := opt; o.Header.TTL = 0x8000; m.Additionals = []dnsmessage.Resource{o} }},
		{"edns-version", func(m *dnsmessage.Message) {
			o := opt
			o.Header.TTL = 1 << 16
			m.Additionals = []dnsmessage.Resource{o}
		}},
		{"query-ad", func(m *dnsmessage.Message) { m.AuthenticData = true }},
		{"query-cd", func(m *dnsmessage.Message) { m.CheckingDisabled = true }},
		{"no-recursion", func(m *dnsmessage.Message) { m.RecursionDesired = false }},
		{"truncated", func(m *dnsmessage.Message) { m.Truncated = true }},
		{"answer-in-query", func(m *dnsmessage.Message) {
			m.Answers = []dnsmessage.Resource{{Header: dnsmessage.ResourceHeader{Name: m.Questions[0].Name, Type: dnsmessage.TypeA, Class: dnsmessage.ClassINET}, Body: &dnsmessage.AResource{A: [4]byte{1, 1, 1, 1}}}}
		}},
		{"additional-non-opt", func(m *dnsmessage.Message) {
			m.Additionals = []dnsmessage.Resource{{Header: dnsmessage.ResourceHeader{Name: m.Questions[0].Name, Type: dnsmessage.TypeA, Class: dnsmessage.ClassINET}, Body: &dnsmessage.AResource{A: [4]byte{1, 1, 1, 1}}}}
		}},
	}
	for _, tc := range tests {
		t.Run(tc.name, func(t *testing.T) {
			f, err := newFakeDNS(nil)
			if err != nil {
				t.Fatal(err)
			}
			var message dnsmessage.Message
			if err := message.Unpack(fakeQuery(t, "example.org", dnsmessage.TypeA)); err != nil {
				t.Fatal(err)
			}
			tc.edit(&message)
			query, err := message.Pack()
			if err != nil {
				t.Fatal(err)
			}
			r := &recordingProtocolResolver{}
			e := &Engine{fakeDNS: f, dns: r}
			response, err := e.resolveDNS(query)
			if err != nil || !bytes.Equal(response, []byte("resolver response")) || !bytes.Equal(r.got, query) {
				t.Fatalf("query not passed unchanged: response=%q error=%v", response, err)
			}
			f.mu.RLock()
			allocated := f.allocated
			f.mu.RUnlock()
			if allocated != 0 {
				t.Fatal("pass-through query allocated a fake address")
			}
		})
	}
}
