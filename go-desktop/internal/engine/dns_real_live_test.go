//go:build linux || windows

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

// A genuine OS TUN with a deterministic local DNS and relay. This validates
// cached real-IP forwarding on reconnect, not WAN, TLS or Mozilla behavior.
func TestDNSRealLiveKernelReconnect(t *testing.T) {
	if os.Getenv("GOZAR_LIVE_TUN_TEST") != "1" || os.Getenv("GITHUB_ACTIONS") != "true" {
		t.Skip("requires disposable privileged CI runner")
	}
	if err := wintun.Ensure(); err != nil {
		t.Fatal(err)
	}
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
		target := fmt.Sprintf("%s:%d", host, port)
		tcpTargets <- target
		if target != "203.0.113.7:443" {
			return nil, fmt.Errorf("unexpected TCP target %s", target)
		}
		return net.DialTimeout("tcp4", listener.Addr().String(), 5*time.Second)
	}
	udpTargets := make(chan string, 4)
	udpDial := func(host string, port int) (DatagramTunnel, error) {
		udpTargets <- fmt.Sprintf("%s:%d", host, port)
		return newEchoDatagramTunnel(), nil
	}
	startSession := func() *Engine {
		e, err := startUDP("test.invalid", dial, nil, liveResolver{}, nil, nil, udpDial)
		if err != nil {
			t.Fatal(err)
		}
		t.Cleanup(e.Stop)
		return e
	}
	first := startSession()
	dns, err := net.DialTimeout("udp4", net.JoinHostPort(dnsproxy.Address, "53"), 5*time.Second)
	if err != nil {
		t.Fatal(err)
	}
	_ = dns.SetDeadline(time.Now().Add(8 * time.Second))
	if _, err = dns.Write(liveDNSMessage(t)); err != nil {
		t.Fatal(err)
	}
	buf := make([]byte, 4096)
	n, err := dns.Read(buf)
	_ = dns.Close()
	if err != nil {
		t.Fatal(err)
	}
	var answer dnsmessage.Message
	if err = answer.Unpack(buf[:n]); err != nil || len(answer.Answers) != 1 {
		t.Fatalf("invalid DNS answer: %v", err)
	}
	record, ok := answer.Answers[0].Body.(*dnsmessage.AResource)
	if !ok {
		t.Fatal("expected A answer")
	}
	cached := netip.AddrFrom4(record.A)
	if cached != netip.MustParseAddr("203.0.113.7") {
		t.Fatalf("unexpected real address %s", cached)
	}
	first.Stop()
	second := startSession()
	defer second.Stop()
	c, err := net.DialTimeout("tcp4", net.JoinHostPort(cached.String(), "443"), 8*time.Second)
	if err != nil {
		t.Fatal(err)
	}
	_ = c.SetDeadline(time.Now().Add(8 * time.Second))
	if _, err = c.Write([]byte("tcp-cached")); err != nil {
		t.Fatal(err)
	}
	got := make([]byte, 10)
	if _, err = io.ReadFull(c, got); err != nil || string(got) != "tcp-cached" {
		t.Fatalf("TCP: %q %v", got, err)
	}
	_ = c.Close()
	select {
	case target := <-tcpTargets:
		if target != "203.0.113.7:443" {
			t.Fatalf("TCP destination %s", target)
		}
	case <-time.After(5 * time.Second):
		t.Fatal("no TCP target")
	}
	u, err := net.DialTimeout("udp4", net.JoinHostPort(cached.String(), "443"), 5*time.Second)
	if err != nil {
		t.Fatal(err)
	}
	defer u.Close()
	_ = u.SetDeadline(time.Now().Add(8 * time.Second))
	if _, err = u.Write([]byte("udp-cached")); err != nil {
		t.Fatal(err)
	}
	n, err = u.Read(buf)
	if err != nil || string(buf[:n]) != "udp-cached" {
		t.Fatalf("UDP: %q %v", buf[:n], err)
	}
	select {
	case target := <-udpTargets:
		if target != "203.0.113.7:443" {
			t.Fatalf("UDP destination %s", target)
		}
	case <-time.After(5 * time.Second):
		t.Fatal("no UDP target")
	}
}
