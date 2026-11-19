//go:build linux || windows

package engine

import (
	"fmt"
	"io"
	"net"
	"os"
	"strings"
	"testing"
	"time"

	"github.com/Ammpapsoe/GozarFox/go-desktop/internal/wintun"
	"golang.org/x/net/dns/dnsmessage"
)

// Demonstrates the unresolved foreign-namespace collision on a genuine OS
// TUN, using a retained address model and local simulated peers only. It does
// not assert that Windows or any browser actually retains an address, and must
// never be interpreted as a passing safety or production-activation test.
func TestFakeDNSForeignCollisionLiveKernelDemonstration(t *testing.T) {
	if os.Getenv("GOZAR_LIVE_TUN_TEST") != "1" || os.Getenv("GITHUB_ACTIONS") != "true" {
		t.Skip("requires disposable privileged runner")
	}
	if err := wintun.Ensure(); err != nil {
		t.Fatal(err)
	}
	foreign, err := newJournalFakeDNS(&memoryDNSJournal{exists: true, start: 3}, nil)
	if err != nil {
		t.Fatal(err)
	}
	cached, err := foreign.address("foreign.example")
	if err != nil {
		t.Fatal(err)
	}
	local, err := newJournalFakeDNS(&memoryDNSJournal{exists: true, start: 3}, nil)
	if err != nil {
		t.Fatal(err)
	}
	assigned, err := local.address("local.example")
	if err != nil || assigned != cached {
		t.Fatalf("collision setup failed: %s %s %v", cached, assigned, err)
	}

	listener, err := net.Listen("tcp4", "127.0.0.1:0")
	if err != nil {
		t.Fatal(err)
	}
	defer listener.Close()
	go func() {
		for {
			c, err := listener.Accept()
			if err != nil {
				return
			}
			go func() { defer c.Close(); _, _ = io.Copy(c, c) }()
		}
	}()
	tcpTargets := make(chan string, 1)
	dial := func(host string, port int) (io.ReadWriteCloser, error) {
		tcpTargets <- fmt.Sprintf("%s:%d", host, port)
		return net.DialTimeout("tcp4", listener.Addr().String(), 5*time.Second)
	}
	queries := make(chan string, 1)
	resolver := fakeUDPResolver{answer: func(q dnsmessage.Message) (dnsmessage.Message, error) {
		queries <- q.Questions[0].Name.String()
		return syntheticUDPAnswer(q, [4]byte{1, 1, 1, 1}), nil
	}}
	udpTargets := make(chan string, 1)
	udpDial := func(host string, port int) (DatagramTunnel, error) {
		udpTargets <- fmt.Sprintf("%s:%d", host, port)
		return newEchoDatagramTunnel(), nil
	}
	e, err := startUDPManaged("test.invalid", dial, nil, resolver, nil, nil, udpDial, local, io.NopCloser(strings.NewReader("")))
	if err != nil {
		t.Fatal(err)
	}
	defer e.Stop()

	c, err := net.DialTimeout("tcp4", net.JoinHostPort(cached.String(), "443"), 8*time.Second)
	if err != nil {
		t.Fatal(err)
	}
	_ = c.SetDeadline(time.Now().Add(8 * time.Second))
	if _, err = c.Write([]byte("probe")); err != nil {
		t.Fatal(err)
	}
	buf := make([]byte, 5)
	if _, err = io.ReadFull(c, buf); err != nil || string(buf) != "probe" {
		t.Fatalf("TCP echo %q %v", buf, err)
	}
	_ = c.Close()
	select {
	case host := <-tcpTargets:
		if host != "local.example:443" {
			t.Fatalf("unexpected TCP authority %s", host)
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
	if _, err = u.Write([]byte("probe")); err != nil {
		t.Fatal(err)
	}
	n, err := u.Read(buf)
	if err != nil || string(buf[:n]) != "probe" {
		t.Fatalf("UDP echo %q %v", buf[:n], err)
	}
	select {
	case host := <-queries:
		if host != "local.example." {
			t.Fatalf("unexpected UDP DNS authority %s", host)
		}
	case <-time.After(5 * time.Second):
		t.Fatal("no UDP DNS lookup")
	}
	select {
	case host := <-udpTargets:
		if host != "1.1.1.1:443" {
			t.Fatalf("unexpected UDP target %s", host)
		}
	case <-time.After(5 * time.Second):
		t.Fatal("no UDP target")
	}
	t.Logf("UNRESOLVED: retained address %s for foreign.example was interpreted as local.example on real TUN for TCP and UDP; test peers were local", cached)
}
