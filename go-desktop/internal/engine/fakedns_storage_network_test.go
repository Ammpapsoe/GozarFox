//go:build linux || windows

package engine

import (
	"bytes"
	"context"
	"errors"
	"fmt"
	"io"
	"testing"
	"time"

	"golang.org/x/net/dns/dnsmessage"
	"gvisor.dev/gvisor/pkg/tcpip"
	"gvisor.dev/gvisor/pkg/tcpip/adapters/gonet"
	"gvisor.dev/gvisor/pkg/tcpip/network/ipv4"
)

// This is a virtual-stack integration test, not a real OS TUN or WAN test.
// A DNS response obtained in one session must still reach its original TCP
// authority and a tunneled UDP destination after opening a second session.
func TestFakeDNSStorageCachedAddressAcrossVirtualSessions(t *testing.T) {
	open := platformFakeDNSStorage(t)
	first, owner, err := open()
	if err != nil {
		t.Fatal(err)
	}
	defer owner.Close()
	client, firstEngine, _ := virtualNetwork(t, &mockResolver{})
	firstEngine.fakeDNS = first
	dnsAddress := &tcpip.FullAddress{NIC: 1, Addr: tcpip.AddrFrom4([4]byte{1, 1, 1, 1}), Port: 53}
	dns, err := gonet.DialUDP(client, nil, dnsAddress, ipv4.ProtocolNumber)
	if err != nil {
		t.Fatal(err)
	}
	_ = dns.SetDeadline(time.Now().Add(5 * time.Second))
	if _, err := dns.Write(fakeQuery(t, "cached.example", dnsmessage.TypeA)); err != nil {
		t.Fatal(err)
	}
	buffer := make([]byte, 2048)
	n, err := dns.Read(buffer)
	_ = dns.Close()
	if err != nil {
		t.Fatal(err)
	}
	var reply dnsmessage.Message
	if err := reply.Unpack(buffer[:n]); err != nil || len(reply.Answers) != 1 {
		t.Fatalf("invalid DNS reply: %v", err)
	}
	answer, ok := reply.Answers[0].Body.(*dnsmessage.AResource)
	if !ok {
		t.Fatal("expected A response")
	}
	cached := answer.A
	if err := owner.Close(); err != nil {
		t.Fatal(err)
	}

	second, nextOwner, err := open()
	if err != nil {
		t.Fatal(err)
	}
	defer nextOwner.Close()
	stream := newEchoDatagramTunnel()
	udpTarget := make(chan string, 1)
	newClient, secondEngine, _ := virtualNetworkUDP(t, &mockResolver{}, func(host string, port int) (DatagramTunnel, error) {
		udpTarget <- fmt.Sprintf("%s:%d", host, port)
		return stream, nil
	})
	secondEngine.fakeDNS = second
	secondEngine.dns = fakeUDPResolver{answer: func(q dnsmessage.Message) (dnsmessage.Message, error) {
		return syntheticUDPAnswer(q, [4]byte{1, 1, 1, 1}), nil
	}}
	tcpTarget := make(chan string, 1)
	secondEngine.dial = func(host string, port int) (io.ReadWriteCloser, error) {
		tcpTarget <- fmt.Sprintf("%s:%d", host, port)
		return nil, errors.New("test stops after TCP target capture")
	}
	ctx, cancel := context.WithTimeout(context.Background(), 5*time.Second)
	defer cancel()
	conn, _ := gonet.DialContextTCP(ctx, newClient, tcpip.FullAddress{NIC: 1, Addr: tcpip.AddrFrom4(cached), Port: 443}, ipv4.ProtocolNumber)
	if conn != nil {
		_ = conn.Close()
	}
	select {
	case got := <-tcpTarget:
		if got != "cached.example:443" {
			t.Fatalf("cached TCP mapped to %s", got)
		}
	case <-ctx.Done():
		t.Fatal("cached TCP did not reach upstream")
	}
	remote := &tcpip.FullAddress{NIC: 1, Addr: tcpip.AddrFrom4(cached), Port: 443}
	udp, err := gonet.DialUDP(newClient, nil, remote, ipv4.ProtocolNumber)
	if err != nil {
		t.Fatal(err)
	}
	defer udp.Close()
	_ = udp.SetDeadline(time.Now().Add(5 * time.Second))
	if _, err := udp.Write([]byte("cached")); err != nil {
		t.Fatal(err)
	}
	n, err = udp.Read(buffer)
	if err != nil || !bytes.Equal(buffer[:n], []byte("cached")) {
		t.Fatalf("cached UDP failed: %v", err)
	}
	select {
	case got := <-udpTarget:
		if got != "1.1.1.1:443" {
			t.Fatalf("cached UDP target %s", got)
		}
	case <-time.After(5 * time.Second):
		t.Fatal("cached UDP did not use tunneled resolver")
	}
	other, err := second.address("different.example")
	if err != nil || other.As4() == cached {
		t.Fatalf("reused cached address: %s, %v", other, err)
	}
}
