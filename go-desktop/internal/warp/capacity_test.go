package warp

import (
	"context"
	"fmt"
	"io"
	"net"
	"testing"
	"time"
)

// This exercises the production CONNECT-IP session against a local HTTP/2
// capsule echo peer. It is not a Cloudflare, WAN, TUN or rival benchmark.
func TestConnectIPCapacityReleaseAndClose(t *testing.T) {
	server, identity := newH2Echo(t)
	defer server.Close()
	dial := func(ctx context.Context, _ string, _ int) (io.ReadWriteCloser, error) {
		return (&net.Dialer{}).DialContext(ctx, "tcp", server.Listener.Addr().String())
	}
	ctx, cancel := context.WithTimeout(context.Background(), 20*time.Second)
	defer cancel()
	session, err := openSession(ctx, identity, dial, "162.159.198.3", 443)
	if err != nil {
		t.Fatal(err)
	}
	defer session.Close()
	flows := make([]*Flow, 0, maxFlows)
	for i := 0; i < maxFlows; i++ {
		flow, err := session.OpenUDP("203.0.113.3", 10000+i)
		if err != nil {
			t.Fatalf("open flow %d: %v", i, err)
		}
		flows = append(flows, flow)
		message := []byte(fmt.Sprintf("packet-%d", i))
		if err := flow.WriteDatagram(message); err != nil {
			t.Fatalf("write flow %d: %v", i, err)
		}
		reply, err := flow.ReadDatagram()
		if err != nil || string(reply) != string(message) {
			t.Fatalf("echo flow %d: %q %v", i, reply, err)
		}
	}
	if _, err := session.OpenUDP("203.0.113.3", 20000); err == nil {
		t.Fatal("flow past CONNECT-IP capacity was admitted")
	}
	if err := flows[0].Close(); err != nil {
		t.Fatal(err)
	}
	replacement, err := session.OpenUDP("203.0.113.3", 20000)
	if err != nil {
		t.Fatalf("closed flow did not release capacity: %v", err)
	}
	if err := replacement.WriteDatagram([]byte("replacement")); err != nil {
		t.Fatal(err)
	}
	if reply, err := replacement.ReadDatagram(); err != nil || string(reply) != "replacement" {
		t.Fatalf("replacement echo %q %v", reply, err)
	}
	done := make(chan error, 1)
	go func() { _, err := replacement.ReadDatagram(); done <- err }()
	if err := session.Close(); err != nil {
		t.Fatal(err)
	}
	select {
	case err := <-done:
		if err == nil {
			t.Fatal("closed session returned a successful read")
		}
	case <-time.After(3 * time.Second):
		t.Fatal("session close did not unblock a flow reader")
	}
	if _, err := session.OpenUDP("203.0.113.3", 20000); err == nil {
		t.Fatal("closed session accepted a flow")
	}
}
