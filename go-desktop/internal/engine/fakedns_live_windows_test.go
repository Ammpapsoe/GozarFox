//go:build windows

package engine

import (
	"fmt"
	"io"
	"net"
	"net/netip"
	"os"
	"testing"
	"time"

	"github.com/Ammpapsoe/GozarFox/go-desktop/internal/dnsproxy"
	"github.com/Ammpapsoe/GozarFox/go-desktop/internal/wintun"
	"golang.org/x/net/dns/dnsmessage"
)

// This runs on a disposable privileged Windows CI runner. It uses a real Wintun
// interface and OS routing but a local echo peer and fake DNS/upstream relay;
// it is not an internet, Mozilla, packet-loss or performance comparison.
func TestFakeDNSLiveWindowsReconnect(t *testing.T) {
	if os.Getenv("GOZAR_LIVE_TUN_TEST") != "1" {
		t.Skip("requires isolated privileged Windows TUN")
	}
	if err := wintun.Ensure(); err != nil {
		t.Fatal(err)
	}
	parent := windowsMachineTestParent(t)
	listener, err := net.Listen("tcp4", "127.0.0.1:0")
	if err != nil {
		t.Fatal(err)
	}
	defer listener.Close()
	go func() {
		for {
			c, e := listener.Accept()
			if e != nil {
				return
			}
			go func() { defer c.Close(); _, _ = io.Copy(c, c) }()
		}
	}()
	tcpTargets := make(chan string, 4)
	dial := func(host string, port int) (io.ReadWriteCloser, error) {
		tcpTargets <- fmt.Sprintf("%s:%d", host, port)
		if host != "example.test" || port != 443 {
			return nil, fmt.Errorf("unexpected target %s:%d", host, port)
		}
		return net.DialTimeout("tcp4", listener.Addr().String(), 5*time.Second)
	}
	udpTargets := make(chan string, 4)
	udpDial := func(host string, port int) (DatagramTunnel, error) {
		udpTargets <- fmt.Sprintf("%s:%d", host, port)
		return newEchoDatagramTunnel(), nil
	}
	startSession := func() *Engine {
		fake, owner, err := openWindowsMachineDNSStorage(parent, nil)
		if err != nil {
			t.Fatal(err)
		}
		e, err := startUDPManaged("test.invalid", dial, nil, liveResolver{}, nil, nil, udpDial, fake, owner)
		if err != nil {
			t.Fatal(err)
		}
		t.Cleanup(e.Stop)
		return e
	}
	first := startSession()
	if _, owner, err := openWindowsMachineDNSStorage(parent, nil); err == nil {
		_ = owner.Close()
		t.Fatal("live session did not retain lock")
	}
	dns, err := net.DialTimeout("udp4", net.JoinHostPort(dnsproxy.Address, "53"), 5*time.Second)
	if err != nil {
		t.Fatal(err)
	}
	_ = dns.SetDeadline(time.Now().Add(8 * time.Second))
	if _, err = dns.Write(liveDNSMessage(t)); err != nil {
		t.Fatal(err)
	}
	buffer := make([]byte, 4096)
	n, err := dns.Read(buffer)
	_ = dns.Close()
	if err != nil {
		t.Fatal(err)
	}
	var msg dnsmessage.Message
	if err = msg.Unpack(buffer[:n]); err != nil || len(msg.Answers) != 1 {
		t.Fatalf("bad FakeDNS answer: %v", err)
	}
	record, ok := msg.Answers[0].Body.(*dnsmessage.AResource)
	if !ok {
		t.Fatal("missing synthetic A")
	}
	cached := netip.AddrFrom4(record.A)
	if !fakePrefix.Contains(cached) {
		t.Fatalf("address is not synthetic: %s", cached)
	}
	first.Stop()
	second := startSession()
	defer second.Stop()
	c, err := net.DialTimeout("tcp4", net.JoinHostPort(cached.String(), "443"), 8*time.Second)
	if err != nil {
		t.Fatal(err)
	}
	_ = c.SetDeadline(time.Now().Add(8 * time.Second))
	if _, err = c.Write([]byte("cached-tcp")); err != nil {
		t.Fatal(err)
	}
	got := make([]byte, 10)
	if _, err = io.ReadFull(c, got); err != nil || string(got) != "cached-tcp" {
		t.Fatalf("cached TCP round trip %q: %v", got, err)
	}
	_ = c.Close()
	select {
	case target := <-tcpTargets:
		if target != "example.test:443" {
			t.Fatalf("cached TCP mapped to %s", target)
		}
	case <-time.After(5 * time.Second):
		t.Fatal("TCP target missing")
	}
	u, err := net.DialTimeout("udp4", net.JoinHostPort(cached.String(), "443"), 5*time.Second)
	if err != nil {
		t.Fatal(err)
	}
	defer u.Close()
	_ = u.SetDeadline(time.Now().Add(8 * time.Second))
	if _, err = u.Write([]byte("cached-udp")); err != nil {
		t.Fatal(err)
	}
	n, err = u.Read(buffer)
	if err != nil || string(buffer[:n]) != "cached-udp" {
		t.Fatalf("cached UDP round trip %q: %v", buffer[:n], err)
	}
	select {
	case target := <-udpTargets:
		if target != "203.0.113.7:443" {
			t.Fatalf("cached UDP mapped to %s", target)
		}
	case <-time.After(5 * time.Second):
		t.Fatal("UDP target missing")
	}
}
