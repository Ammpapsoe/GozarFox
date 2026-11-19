package engine

import (
	"bytes"
	"errors"
	"fmt"
	"testing"
	"time"

	"golang.org/x/net/dns/dnsmessage"
	"gvisor.dev/gvisor/pkg/tcpip"
	"gvisor.dev/gvisor/pkg/tcpip/adapters/gonet"
	"gvisor.dev/gvisor/pkg/tcpip/network/ipv4"
)

type fakeUDPResolver struct {
	answer func(dnsmessage.Message) (dnsmessage.Message, error)
}

func (r fakeUDPResolver) Resolve(q []byte) ([]byte, error) {
	var message dnsmessage.Message
	if err := message.Unpack(q); err != nil {
		return nil, err
	}
	reply, err := r.answer(message)
	if err != nil {
		return nil, err
	}
	return reply.Pack()
}
func (fakeUDPResolver) Close() {}

func syntheticUDPAnswer(query dnsmessage.Message, address [4]byte) dnsmessage.Message {
	alias, _ := dnsmessage.NewName("cdn.example.")
	query.Response = true
	query.Answers = []dnsmessage.Resource{
		{Header: dnsmessage.ResourceHeader{Name: query.Questions[0].Name, Type: dnsmessage.TypeCNAME, Class: dnsmessage.ClassINET}, Body: &dnsmessage.CNAMEResource{CNAME: alias}},
		{Header: dnsmessage.ResourceHeader{Name: alias, Type: dnsmessage.TypeA, Class: dnsmessage.ClassINET}, Body: &dnsmessage.AResource{A: address}},
	}
	return query
}

func TestFakeDNSUDPUsesTunneledResolverAndRealDestination(t *testing.T) {
	stream := newEchoDatagramTunnel()
	targets := make(chan string, 1)
	client, e, _ := virtualNetworkUDP(t, &mockResolver{}, func(host string, port int) (DatagramTunnel, error) {
		targets <- fmt.Sprintf("%s:%d", host, port)
		return stream, nil
	})
	e.dns = fakeUDPResolver{answer: func(q dnsmessage.Message) (dnsmessage.Message, error) {
		return syntheticUDPAnswer(q, [4]byte{1, 1, 1, 1}), nil
	}}
	fake, err := newFakeDNS(nil)
	if err != nil {
		t.Fatal(err)
	}
	e.fakeDNS = fake
	addr, err := fake.address("example.org")
	if err != nil {
		t.Fatal(err)
	}
	remote := &tcpip.FullAddress{NIC: 1, Addr: tcpip.AddrFrom4(addr.As4()), Port: 443}
	conn, err := gonet.DialUDP(client, nil, remote, ipv4.ProtocolNumber)
	if err != nil {
		t.Fatal(err)
	}
	defer conn.Close()
	_ = conn.SetDeadline(time.Now().Add(5 * time.Second))
	if _, err := conn.Write([]byte("hello")); err != nil {
		t.Fatal(err)
	}
	buf := make([]byte, 32)
	n, err := conn.Read(buf)
	if err != nil || !bytes.Equal(buf[:n], []byte("hello")) {
		t.Fatalf("UDP round trip: %q, %v", buf[:n], err)
	}
	select {
	case target := <-targets:
		if target != "1.1.1.1:443" {
			t.Fatalf("wrong UDP destination %s", target)
		}
	case <-time.After(3 * time.Second):
		t.Fatal("UDP target not opened")
	}
}

func TestFakeDNSUDPRejectsInvalidOrUnsafeAnswers(t *testing.T) {
	fake, err := newFakeDNS(nil)
	if err != nil {
		t.Fatal(err)
	}
	addr, err := fake.address("example.org")
	if err != nil {
		t.Fatal(err)
	}
	cases := map[string]func(dnsmessage.Message) (dnsmessage.Message, error){
		"resolver failure": func(q dnsmessage.Message) (dnsmessage.Message, error) { return q, errors.New("resolver unavailable") },
		"mismatched ID": func(q dnsmessage.Message) (dnsmessage.Message, error) {
			q = syntheticUDPAnswer(q, [4]byte{1, 1, 1, 1})
			q.ID++
			return q, nil
		},
		"private address": func(q dnsmessage.Message) (dnsmessage.Message, error) {
			return syntheticUDPAnswer(q, [4]byte{10, 0, 0, 1}), nil
		},
		"synthetic address": func(q dnsmessage.Message) (dnsmessage.Message, error) {
			return syntheticUDPAnswer(q, [4]byte{198, 18, 0, 2}), nil
		},
		"unrelated record": func(q dnsmessage.Message) (dnsmessage.Message, error) {
			q = syntheticUDPAnswer(q, [4]byte{1, 1, 1, 1})
			q.Answers = q.Answers[1:]
			return q, nil
		},
		"truncated": func(q dnsmessage.Message) (dnsmessage.Message, error) {
			q = syntheticUDPAnswer(q, [4]byte{1, 1, 1, 1})
			q.Truncated = true
			return q, nil
		},
	}
	for name, reply := range cases {
		t.Run(name, func(t *testing.T) {
			e := &Engine{fakeDNS: fake, dns: fakeUDPResolver{answer: reply}}
			if target, err := e.resolveDatagramTarget(addr.String()); err == nil {
				t.Fatalf("unsafe DNS answer selected %q", target)
			}
		})
	}
}
