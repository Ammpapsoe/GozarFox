package engine

import (
	"context"
	"io"
	"net/netip"
	"os"
	"sync"
	"sync/atomic"
	"testing"
	"time"

	"github.com/bardia049b-stack/Gozar1/go-desktop/internal/geo"
	"golang.org/x/net/dns/dnsmessage"
	"gvisor.dev/gvisor/pkg/tcpip"
	"gvisor.dev/gvisor/pkg/tcpip/adapters/gonet"
	"gvisor.dev/gvisor/pkg/tcpip/network/ipv4"
	"gvisor.dev/gvisor/pkg/tcpip/stack"
)

type countingPipe struct {
	writes atomic.Int64
	once   sync.Once
	closed chan struct{}
}

func newCountingPipe() *countingPipe { return &countingPipe{closed: make(chan struct{})} }

func (p *countingPipe) Read(buffer []byte) (int, error) {
	<-p.closed
	return 0, io.EOF
}

func (p *countingPipe) Write(buffer []byte) (int, error) {
	p.writes.Add(int64(len(buffer)))
	return len(buffer), nil
}

func (p *countingPipe) Close() error {
	p.once.Do(func() { close(p.closed) })
	return nil
}

func dialVirtual(t *testing.T, client *stack.Stack, address [4]byte) *gonet.TCPConn {
	t.Helper()
	ctx, cancel := context.WithTimeout(context.Background(), 4*time.Second)
	defer cancel()
	conn, err := gonet.DialContextTCP(ctx, client, tcpip.FullAddress{NIC: 1, Addr: tcpip.AddrFrom4(address), Port: 443}, ipv4.ProtocolNumber)
	if err != nil {
		t.Fatal(err)
	}
	return conn
}

func learnA(t *testing.T, rules *geo.Rules, domain string, address [4]byte) {
	t.Helper()
	name := dnsmessage.MustNewName(domain + ".")
	query := dnsmessage.Message{Header: dnsmessage.Header{ID: 11}, Questions: []dnsmessage.Question{{Name: name, Type: dnsmessage.TypeA, Class: dnsmessage.ClassINET}}}
	answer := dnsmessage.Message{Header: dnsmessage.Header{ID: 11, Response: true}, Questions: query.Questions, Answers: []dnsmessage.Resource{{Header: dnsmessage.ResourceHeader{Name: name, Type: dnsmessage.TypeA, Class: dnsmessage.ClassINET, TTL: 60}, Body: &dnsmessage.AResource{A: address}}}}
	q, err := query.Pack()
	if err != nil {
		t.Fatal(err)
	}
	a, err := answer.Pack()
	if err != nil {
		t.Fatal(err)
	}
	rules.Learn(q, a)
}

func TestSplitTunnelDirectAndTunneledDestinations(t *testing.T) {
	client, e, _ := virtualNetwork(t, &mockResolver{})
	var tunneled, direct atomic.Int64
	e.dial = func(host string, port int) (io.ReadWriteCloser, error) {
		tunneled.Add(1)
		return newCountingPipe(), nil
	}
	e.direct = func(host string, port int) (io.ReadWriteCloser, error) {
		direct.Add(1)
		return newCountingPipe(), nil
	}
	e.split = geo.Default()
	learnA(t, e.split, "www.aparat.com", [4]byte{185, 141, 100, 10})
	learnA(t, e.split, "www.google.com", [4]byte{142, 250, 185, 14})

	for _, tc := range []struct {
		address [4]byte
		want    string
	}{
		{[4]byte{185, 141, 100, 10}, "direct"},
		{[4]byte{2, 57, 3, 1}, "direct"},
		{[4]byte{142, 250, 185, 14}, "tunnel"},
		{[4]byte{8, 8, 8, 8}, "tunnel"},
	} {
		beforeDirect, beforeTunnel := direct.Load(), tunneled.Load()
		conn := dialVirtual(t, client, tc.address)
		deadline := time.Now().Add(2 * time.Second)
		for time.Now().Before(deadline) {
			if direct.Load() > beforeDirect || tunneled.Load() > beforeTunnel {
				break
			}
			time.Sleep(10 * time.Millisecond)
		}
		conn.Close()
		gotDirect, gotTunnel := direct.Load() > beforeDirect, tunneled.Load() > beforeTunnel
		if tc.want == "direct" && (!gotDirect || gotTunnel) {
			t.Fatalf("%s used tunnel instead of direct", netip.AddrFrom4(tc.address))
		}
		if tc.want == "tunnel" && (!gotTunnel || gotDirect) {
			t.Fatalf("%s used direct instead of tunnel", netip.AddrFrom4(tc.address))
		}
	}
}

func TestIdleConnectionDoesNotProbeUpstream(t *testing.T) {
	client, e, _ := virtualNetwork(t, &mockResolver{})
	var calls atomic.Int64
	e.dial = func(host string, port int) (io.ReadWriteCloser, error) {
		calls.Add(1)
		return newCountingPipe(), nil
	}
	conn := dialVirtual(t, client, [4]byte{203, 0, 113, 7})
	defer conn.Close()
	deadline := time.Now().Add(2 * time.Second)
	for calls.Load() == 0 && time.Now().Before(deadline) {
		time.Sleep(10 * time.Millisecond)
	}
	if calls.Load() != 1 {
		t.Fatalf("expected one upstream connection, got %d", calls.Load())
	}
	down, up := e.Counters()
	time.Sleep(300 * time.Millisecond)
	afterDown, afterUp := e.Counters()
	if calls.Load() != 1 || afterDown != down || afterUp != up {
		t.Fatalf("idle traffic changed: calls=%d down=%d->%d up=%d->%d", calls.Load(), down, afterDown, up, afterUp)
	}
}

func TestIdleFiveMinutesSoak(t *testing.T) {
	if os.Getenv("GOZAR_IDLE_SOAK") != "1" {
		t.Skip("set GOZAR_IDLE_SOAK=1 to run the five minute idle proof")
	}
	client, e, _ := virtualNetwork(t, &mockResolver{})
	var calls atomic.Int64
	e.dial = func(host string, port int) (io.ReadWriteCloser, error) {
		calls.Add(1)
		return newCountingPipe(), nil
	}
	conn := dialVirtual(t, client, [4]byte{203, 0, 113, 7})
	defer conn.Close()
	deadline := time.Now().Add(5 * time.Second)
	for calls.Load() == 0 && time.Now().Before(deadline) {
		time.Sleep(20 * time.Millisecond)
	}
	if calls.Load() != 1 {
		t.Fatalf("expected one upstream connection, got %d", calls.Load())
	}
	down, up := e.Counters()
	time.Sleep(5 * time.Minute)
	afterDown, afterUp := e.Counters()
	if calls.Load() != 1 || afterDown != down || afterUp != up {
		t.Fatalf("idle five minutes changed traffic: calls=%d down=%d->%d up=%d->%d", calls.Load(), down, afterDown, up, afterUp)
	}
}
