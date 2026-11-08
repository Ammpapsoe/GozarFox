package engine

import (
	"bytes"
	"errors"
	"fmt"
	"io"
	"sync"
	"sync/atomic"
	"testing"
	"time"

	"gvisor.dev/gvisor/pkg/tcpip"
	"gvisor.dev/gvisor/pkg/tcpip/adapters/gonet"
	"gvisor.dev/gvisor/pkg/tcpip/network/ipv4"
)

type echoDatagramTunnel struct {
	packets chan []byte
	done chan struct{}
	once sync.Once
	closes atomic.Int32
}

func newEchoDatagramTunnel() *echoDatagramTunnel {
	return &echoDatagramTunnel{packets: make(chan []byte, 8), done: make(chan struct{})}
}

func (s *echoDatagramTunnel) WriteDatagram(payload []byte) error {
	copyOfPayload := append([]byte{}, payload...)
	select {
	case s.packets <- copyOfPayload:
		return nil
	case <-s.done:
		return errors.New("closed")
	}
}

func (s *echoDatagramTunnel) ReadDatagram() ([]byte, error) {
	select {
	case packet := <-s.packets:
		return packet, nil
	case <-s.done:
		return nil, errors.New("closed")
	}
}

func (s *echoDatagramTunnel) Close() error {
	s.once.Do(func() {
		s.closes.Add(1)
		close(s.done)
	})
	return nil
}

func TestDatagramPacketPathPreservesMessages(t *testing.T) {
	stream := newEchoDatagramTunnel()
	targets := make(chan string, 1)
	resolver := &mockResolver{}
	client, e, _ := virtualNetworkUDP(t, resolver, func(host string, port int) (DatagramTunnel, error) {
		targets <- fmt.Sprintf("%s:%d", host, port)
		return stream, nil
	})
	remote := &tcpip.FullAddress{NIC: 1, Addr: tcpip.AddrFrom4([4]byte{8, 8, 8, 8}), Port: 443}
	conn, err := gonet.DialUDP(client, nil, remote, ipv4.ProtocolNumber)
	if err != nil {
		t.Fatal(err)
	}
	defer conn.Close()
	_ = conn.SetDeadline(time.Now().Add(5 * time.Second))
	for _, payload := range [][]byte{[]byte("one"), {}, []byte("two-longer")} {
		if n, err := conn.Write(payload); err != nil || n != len(payload) {
			t.Fatalf("write %d-byte packet: %d, %v", len(payload), n, err)
		}
		buffer := make([]byte, 100)
		n, err := conn.Read(buffer)
		if err != nil || !bytes.Equal(payload, buffer[:n]) {
			t.Fatalf("roundtrip %q: got %q, %v", payload, buffer[:n], err)
		}
	}
	if target := <-targets; target != "8.8.8.8:443" {
		t.Fatalf("wrong UDP target %q", target)
	}
	if resolver.queries.Load() != 0 {
		t.Fatal("non-DNS UDP reached the DNS resolver")
	}
	want := int64(len("one") + len("two-longer"))
	deadline := time.Now().Add(time.Second)
	for {
		down, up := e.Counters()
		if down == want && up == want {
			break
		}
		if time.Now().After(deadline) {
			t.Fatalf("incorrect traffic counters down=%d up=%d", down, up)
		}
		time.Sleep(time.Millisecond)
	}
	e.shutdownDatagrams()
	select {
	case <-stream.done:
	case <-time.After(time.Second):
		t.Fatal("shutdown did not close the datagram relay")
	}
}

func TestDatagramRefusalNeverUsesTheDirectDialer(t *testing.T) {
	called := make(chan struct{}, 1)
	resolver := &mockResolver{}
	client, e, _ := virtualNetworkUDP(t, resolver, func(host string, port int) (DatagramTunnel, error) {
		called <- struct{}{}
		return nil, errors.New("synthetic relay refusal")
	})
	var direct atomic.Int32
	e.direct = func(string, int) (io.ReadWriteCloser, error) {
		direct.Add(1)
		return nil, errors.New("direct dial must not run")
	}
	remote := &tcpip.FullAddress{NIC: 1, Addr: tcpip.AddrFrom4([4]byte{203, 0, 113, 8}), Port: 443}
	conn, err := gonet.DialUDP(client, nil, remote, ipv4.ProtocolNumber)
	if err != nil {
		t.Fatal(err)
	}
	defer conn.Close()
	if _, err := conn.Write([]byte("hello")); err != nil {
		t.Fatal(err)
	}
	select {
	case <-called:
	case <-time.After(3 * time.Second):
		t.Fatal("datagram relay was not attempted")
	}
	deadline := time.Now().Add(time.Second)
	for {
		e.udpMu.Lock()
		remaining := len(e.udpFlows) + e.udpReservations
		e.udpMu.Unlock()
		if remaining == 0 {
			break
		}
		if time.Now().After(deadline) {
			t.Fatal("failed datagram flow did not close")
		}
		time.Sleep(time.Millisecond)
	}
	if resolver.queries.Load() != 0 || direct.Load() != 0 {
		t.Fatal("relay refusal used DNS or a direct connection")
	}
}

func TestDatagramCloseAndLateAttachFiftyRaces(t *testing.T) {
	for iteration := 0; iteration < 50; iteration++ {
		e := &Engine{udpDial: func(string, int) (DatagramTunnel, error) { return nil, errors.New("unused") }}
		if !e.reserveDatagram() {
			t.Fatalf("iteration %d: could not reserve", iteration)
		}
		flow := &datagramFlow{}
		if !e.registerDatagram(flow) {
			t.Fatalf("iteration %d: could not register", iteration)
		}
		remote := newEchoDatagramTunnel()
		start := make(chan struct{})
		done := make(chan struct{}, 2)
		go func() {
			<-start
			e.shutdownDatagrams()
			done <- struct{}{}
		}()
		go func() {
			<-start
			if !flow.attach(remote) {
				_ = remote.Close()
			}
			done <- struct{}{}
		}()
		close(start)
		<-done
		<-done
		select {
		case <-remote.done:
		default:
			t.Fatalf("iteration %d: relay survived shutdown", iteration)
		}
		if remote.closes.Load() != 1 || e.reserveDatagram() {
			t.Fatalf("iteration %d: incorrect close count or new work accepted", iteration)
		}
		e.forgetDatagram(flow)
	}
}

func TestDatagramReservationCannotPublishAfterShutdown(t *testing.T) {
	e := &Engine{udpDial: func(string, int) (DatagramTunnel, error) { return nil, errors.New("unused") }}
	if !e.reserveDatagram() {
		t.Fatal("could not reserve")
	}
	e.shutdownDatagrams()
	flow := &datagramFlow{}
	if e.registerDatagram(flow) {
		t.Fatal("reserved flow published after shutdown")
	}
	flow.close()
	if e.udpReservations != 0 || len(e.udpFlows) != 0 {
		t.Fatal("reservation leaked")
	}
}
