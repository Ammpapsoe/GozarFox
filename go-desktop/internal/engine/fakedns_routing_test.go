package engine

import (
	"context"
	"encoding/binary"
	"errors"
	"io"
	"net/netip"
	"testing"
	"time"

	"github.com/Ammpapsoe/GozarFox/go-desktop/internal/geo"
	"golang.org/x/net/dns/dnsmessage"
	"gvisor.dev/gvisor/pkg/tcpip"
	"gvisor.dev/gvisor/pkg/tcpip/adapters/gonet"
	"gvisor.dev/gvisor/pkg/tcpip/network/ipv4"
)

func TestFakeDNSVirtualUDPQueryToTCPAuthority(t *testing.T) {
	resolver := &mockResolver{}
	client, engine, _ := virtualNetwork(t, resolver)
	fake, err := newFakeDNS(nil)
	if err != nil {
		t.Fatal(err)
	}
	engine.fakeDNS = fake
	type destination struct {
		host string
		port int
	}
	target := make(chan destination, 1)
	engine.dial = func(host string, port int) (io.ReadWriteCloser, error) {
		target <- destination{host: host, port: port}
		return nil, errors.New("stop after recording destination")
	}
	remote := &tcpip.FullAddress{NIC: 1, Addr: tcpip.AddrFrom4([4]byte{1, 1, 1, 1}), Port: 53}
	dns, err := gonet.DialUDP(client, nil, remote, ipv4.ProtocolNumber)
	if err != nil {
		t.Fatal(err)
	}
	_ = dns.SetDeadline(time.Now().Add(3 * time.Second))
	if _, err := dns.Write(fakeQuery(t, "EXAMPLE.org", dnsmessage.TypeA)); err != nil {
		t.Fatal(err)
	}
	buffer := make([]byte, 2048)
	n, err := dns.Read(buffer)
	dns.Close()
	if err != nil {
		t.Fatal(err)
	}
	var reply dnsmessage.Message
	if err := reply.Unpack(buffer[:n]); err != nil || len(reply.Answers) != 1 {
		t.Fatalf("bad synthetic answer: %v", err)
	}
	addr := reply.Answers[0].Body.(*dnsmessage.AResource).A
	if resolver.queries.Load() != 0 {
		t.Fatal("synthetic query reached real resolver")
	}
	ctx, cancel := context.WithTimeout(context.Background(), 3*time.Second)
	defer cancel()
	conn, _ := gonet.DialContextTCP(ctx, client, tcpip.FullAddress{NIC: 1, Addr: tcpip.AddrFrom4(addr), Port: 443}, ipv4.ProtocolNumber)
	if conn != nil {
		conn.Close()
	}
	select {
	case got := <-target:
		if got.host != "example.org" || got.port != 443 {
			t.Fatalf("TCP authority = %+v", got)
		}
	case <-time.After(3 * time.Second):
		t.Fatal("synthetic TCP destination did not reach proxy")
	}
}

func TestFakeDNSRoutingDirectAndPolicyChange(t *testing.T) {
	rules, err := geo.Parse([]byte("local.example\n"))
	if err != nil {
		t.Fatal(err)
	}
	fake, err := newFakeDNS(rules.Direct)
	if err != nil {
		t.Fatal(err)
	}
	resolver := &mockResolver{}
	engine := &Engine{fakeDNS: fake, dns: resolver, split: rules}
	dialed := false
	engine.dial = func(string, int) (io.ReadWriteCloser, error) {
		dialed = true
		return nil, errors.New("unexpected dial")
	}
	if _, err := engine.resolveDNS(fakeQuery(t, "local.example", dnsmessage.TypeA)); err != nil || resolver.queries.Load() != 1 {
		t.Fatalf("direct query did not use resolver: %v", err)
	}
	addr, err := fake.address("foreign.example")
	if err != nil {
		t.Fatal(err)
	}
	if err := rules.Replace([]byte("foreign.example\n")); err != nil {
		t.Fatal(err)
	}
	if _, err := engine.dialTCP(addr.String(), 443); err == nil || dialed {
		t.Fatal("stale synthetic address ignored changed split policy")
	}
	unknown := netip.MustParseAddr("198.18.0.0")
	if _, err := engine.dialTCP(unknown.String(), 443); err == nil || dialed {
		t.Fatal("unknown synthetic address reached upstream")
	}
}

func TestFakeDNSUDPDoesNotLeakSyntheticAddress(t *testing.T) {
	called := make(chan string, 1)
	client, engine, icmp := virtualNetworkUDP(t, &mockResolver{}, func(host string, port int) (DatagramTunnel, error) {
		called <- host
		return nil, errors.New("unexpected datagram")
	})
	fake, err := newFakeDNS(nil)
	if err != nil {
		t.Fatal(err)
	}
	engine.fakeDNS = fake
	// An unknown synthetic identity is rejected before opening any flow.
	addr := netip.MustParseAddr("198.18.0.0")
	remote := &tcpip.FullAddress{NIC: 1, Addr: tcpip.AddrFrom4(addr.As4()), Port: 443}
	conn, err := gonet.DialUDP(client, nil, remote, ipv4.ProtocolNumber)
	if err != nil {
		t.Fatal(err)
	}
	defer conn.Close()
	if _, err := conn.Write([]byte("udp")); err != nil {
		t.Fatal(err)
	}
	select {
	case <-icmp:
	case got := <-called:
		t.Fatalf("synthetic UDP address reached datagram dialer: %s", got)
	case <-time.After(3 * time.Second):
		t.Fatal("synthetic UDP did not fail closed")
	}
	select {
	case got := <-called:
		t.Fatalf("synthetic UDP address reached datagram dialer: %s", got)
	default:
	}
}

func TestFakeDNSTCPQueriesAndExhaustion(t *testing.T) {
	resolver := &mockResolver{}
	client, engine, _ := virtualNetwork(t, resolver)
	fake, err := newFakeDNS(nil)
	if err != nil {
		t.Fatal(err)
	}
	engine.fakeDNS = fake
	ctx, cancel := context.WithTimeout(context.Background(), 3*time.Second)
	defer cancel()
	remote := tcpip.FullAddress{NIC: 1, Addr: tcpip.AddrFrom4([4]byte{8, 8, 4, 4}), Port: 53}
	conn, err := gonet.DialContextTCP(ctx, client, remote, ipv4.ProtocolNumber)
	if err != nil {
		t.Fatal(err)
	}
	defer conn.Close()
	_ = conn.SetDeadline(time.Now().Add(3 * time.Second))
	query := fakeQuery(t, "example.org", dnsmessage.TypeA)
	frame := make([]byte, len(query)+2)
	binary.BigEndian.PutUint16(frame, uint16(len(query)))
	copy(frame[2:], query)
	if _, err := conn.Write(frame); err != nil {
		t.Fatal(err)
	}
	var size [2]byte
	if _, err := io.ReadFull(conn, size[:]); err != nil {
		t.Fatal(err)
	}
	answer := make([]byte, binary.BigEndian.Uint16(size[:]))
	if _, err := io.ReadFull(conn, answer); err != nil {
		t.Fatal(err)
	}
	var message dnsmessage.Message
	if err := message.Unpack(answer); err != nil || len(message.Answers) != 1 {
		t.Fatalf("bad TCP synthetic answer: %v", err)
	}
	// Simulate exhaustion without allocating an entire pool. This write is
	// under the same mutex used by the DNS handler, so race CI remains useful.
	fake.mu.Lock()
	fake.allocated = fakeCount
	fake.mu.Unlock()
	query = fakeQuery(t, "other.example", dnsmessage.TypeA)
	frame = make([]byte, len(query)+2)
	binary.BigEndian.PutUint16(frame, uint16(len(query)))
	copy(frame[2:], query)
	if _, err := conn.Write(frame); err != nil {
		t.Fatal(err)
	}
	if _, err := io.ReadFull(conn, size[:]); err != nil {
		t.Fatal(err)
	}
	answer = make([]byte, binary.BigEndian.Uint16(size[:]))
	if _, err := io.ReadFull(conn, answer); err != nil {
		t.Fatal(err)
	}
	checkDNSReply(t, answer, 42, dnsmessage.RCodeServerFailure)
	if resolver.queries.Load() != 0 {
		t.Fatal("exhausted synthetic pool leaked to real resolver")
	}
}
