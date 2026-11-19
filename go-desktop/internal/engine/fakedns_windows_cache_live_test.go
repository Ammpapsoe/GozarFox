//go:build windows

package engine

import (
	"fmt"
	"io"
	"net"
	"net/netip"
	"os"
	"strings"
	"testing"
	"time"

	"github.com/Ammpapsoe/GozarFox/go-desktop/internal/wintun"
	"golang.org/x/net/dns/dnsmessage"
	"golang.org/x/sys/windows"
)

// DNS_QUERY_NO_WIRE_QUERY documented by Microsoft: cache lookup only.
const windowsDNSCacheOnly = 0x10

func windowsQueryIPv4(name string, options uint32) (netip.Addr, error) {
	var records *windows.DNSRecord
	if err := windows.DnsQuery(name, windows.DNS_TYPE_A, options, nil, &records, nil); err != nil {
		return netip.Addr{}, err
	}
	if records != nil {
		defer windows.DnsRecordListFree(records, 0)
	}
	for r := records; r != nil; r = r.Next {
		if r.Type == windows.DNS_TYPE_A && r.Length >= 4 {
			return netip.AddrFrom4([4]byte{r.Data[0], r.Data[1], r.Data[2], r.Data[3]}), nil
		}
	}
	return netip.Addr{}, fmt.Errorf("no IPv4 DNS answer")
}

// Uses Windows DNS Client, a real Wintun and local mock peers on a disposable
// GitHub runner. It records observed cache retention; the simulated foreign
// DNS allocator is not the real ArmanLife application. A cache miss after
// reconfiguration is an observation, not proof that all apps flush their cache.
func TestFakeDNSWindowsSystemCacheDiagnostic(t *testing.T) {
	if os.Getenv("GOZAR_LIVE_TUN_TEST") != "1" || os.Getenv("GITHUB_ACTIONS") != "true" {
		t.Skip("requires disposable Windows CI runner")
	}
	if err := wintun.Ensure(); err != nil {
		t.Fatal(err)
	}
	name := fmt.Sprintf("gzcache-%x.example", time.Now().UnixNano())
	foreign, err := newJournalFakeDNS(&memoryDNSJournal{exists: true, start: 3}, nil)
	if err != nil {
		t.Fatal(err)
	}
	foreignIP, err := foreign.address(name)
	if err != nil {
		t.Fatal(err)
	}
	// Covers unusual DNS flags by returning the same mock address as FakeDNS.
	fallback := fakeUDPResolver{answer: func(q dnsmessage.Message) (dnsmessage.Message, error) {
		if len(q.Questions) != 1 || !strings.EqualFold(q.Questions[0].Name.String(), name+".") {
			return dnsmessage.Message{}, fmt.Errorf("unexpected local DNS question")
		}
		reply := dnsmessage.Message{Header: dnsmessage.Header{ID: q.ID, Response: true, RecursionDesired: q.RecursionDesired, RecursionAvailable: true}, Questions: q.Questions}
		if q.Questions[0].Type == dnsmessage.TypeA {
			reply.Answers = []dnsmessage.Resource{{Header: dnsmessage.ResourceHeader{Name: q.Questions[0].Name, Type: dnsmessage.TypeA, Class: dnsmessage.ClassINET, TTL: 60}, Body: &dnsmessage.AResource{A: foreignIP.As4()}}}
		}
		return reply, nil
	}}
	unusedDial := func(string, int) (io.ReadWriteCloser, error) {
		return nil, fmt.Errorf("no upstream connections in DNS cache stage")
	}
	first, err := startUDPManaged("test.invalid", unusedDial, nil, fallback, nil, nil, nil, foreign, io.NopCloser(strings.NewReader("")))
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(first.Stop)
	observed, err := windowsQueryIPv4(name, 0)
	if err != nil || observed != foreignIP {
		t.Fatalf("Windows DNS Client initial query: got %s want %s error %v", observed, foreignIP, err)
	}
	before, beforeErr := windowsQueryIPv4(name, windowsDNSCacheOnly)
	t.Logf("WINDOWS DNS CACHE before disconnect: address=%s error=%v", before, beforeErr)
	first.Stop()
	after, afterErr := windowsQueryIPv4(name, windowsDNSCacheOnly)
	t.Logf("WINDOWS DNS CACHE after disconnect: address=%s error=%v", after, afterErr)
	if afterErr != nil || after != foreignIP {
		t.Log("Windows DNS Client did not retain this answer across the adapter teardown on this runner; no conclusion about browser/application caches")
		return
	}

	local, err := newJournalFakeDNS(&memoryDNSJournal{exists: true, start: 3}, nil)
	if err != nil {
		t.Fatal(err)
	}
	localIP, err := local.address("local.example")
	if err != nil || localIP != foreignIP {
		t.Fatalf("cannot stage independent collision: %v", err)
	}
	listener, err := net.Listen("tcp4", "127.0.0.1:0")
	if err != nil {
		t.Fatal(err)
	}
	defer listener.Close()
	go func() {
		c, err := listener.Accept()
		if err == nil {
			defer c.Close()
			_, _ = io.Copy(c, c)
		}
	}()
	target := make(chan string, 1)
	dial := func(host string, port int) (io.ReadWriteCloser, error) {
		target <- fmt.Sprintf("%s:%d", host, port)
		return net.DialTimeout("tcp4", listener.Addr().String(), 5*time.Second)
	}
	second, err := startUDPManaged("test.invalid", dial, nil, liveResolver{}, nil, nil, nil, local, io.NopCloser(strings.NewReader("")))
	if err != nil {
		t.Fatal(err)
	}
	defer second.Stop()
	onNew, onNewErr := windowsQueryIPv4(name, windowsDNSCacheOnly)
	t.Logf("WINDOWS DNS CACHE after new adapter: address=%s error=%v", onNew, onNewErr)
	if onNewErr != nil || onNew != foreignIP {
		t.Log("cache invalidated by new adapter; no conclusion about application-held addresses")
		return
	}
	c, err := net.DialTimeout("tcp4", net.JoinHostPort(onNew.String(), "443"), 8*time.Second)
	if err != nil {
		t.Fatal(err)
	}
	defer c.Close()
	_ = c.SetDeadline(time.Now().Add(5 * time.Second))
	if _, err = c.Write([]byte("a")); err != nil {
		t.Fatal(err)
	}
	var b [1]byte
	if _, err = io.ReadFull(c, b[:]); err != nil {
		t.Fatal(err)
	}
	select {
	case got := <-target:
		if got != "local.example:443" {
			t.Fatalf("unexpected target %s", got)
		}
		t.Logf("UNRESOLVED actual Windows DNS Client cache collision: %s cached %s but local FakeDNS forwarded to %s", name, onNew, got)
	case <-time.After(5 * time.Second):
		t.Fatal("no target")
	}
}
