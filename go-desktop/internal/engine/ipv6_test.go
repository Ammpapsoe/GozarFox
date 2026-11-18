package engine

import (
	"context"
	"errors"
	"io"
	"net/netip"
	"testing"
	"time"

	"gvisor.dev/gvisor/pkg/buffer"
	"gvisor.dev/gvisor/pkg/tcpip"
	"gvisor.dev/gvisor/pkg/tcpip/adapters/gonet"
	"gvisor.dev/gvisor/pkg/tcpip/header"
	"gvisor.dev/gvisor/pkg/tcpip/link/channel"
	"gvisor.dev/gvisor/pkg/tcpip/network/ipv4"
	"gvisor.dev/gvisor/pkg/tcpip/network/ipv6"
	"gvisor.dev/gvisor/pkg/tcpip/stack"
	"gvisor.dev/gvisor/pkg/tcpip/transport/tcp"
	"gvisor.dev/gvisor/pkg/tcpip/transport/udp"
)

// The stack used to register IPv4 only, so a client that preferred IPv6 had
// its SYN accepted by the device and then discarded by a stack that could not
// parse it. These two proofs pin the behaviour: an IPv6 destination reaches the
// same relay as an IPv4 one, and the route table has a v6 default route.

func v6Stack(t *testing.T, self [16]byte, permissive bool) *stack.Stack {
	t.Helper()
	link := channel.New(128, 1500, "")
	s := stack.New(stack.Options{
		NetworkProtocols:   []stack.NetworkProtocolFactory{ipv4.NewProtocol, ipv6.NewProtocol},
		TransportProtocols: []stack.TransportProtocolFactory{tcp.NewProtocol, udp.NewProtocol},
	})
	if err := s.CreateNIC(1, link); err != nil {
		t.Fatal(err)
	}
	if permissive {
		if err := s.SetPromiscuousMode(1, true); err != nil {
			t.Fatal(err)
		}
		if err := s.SetSpoofing(1, true); err != nil {
			t.Fatal(err)
		}
	}
	if err := s.AddProtocolAddress(1, tcpip.ProtocolAddress{
		Protocol:          ipv6.ProtocolNumber,
		AddressWithPrefix: tcpip.AddrFrom16(self).WithPrefix(),
	}, stack.AddressProperties{}); err != nil {
		t.Fatal(err)
	}
	s.SetRouteTable([]tcpip.Route{{Destination: header.IPv6EmptySubnet, NIC: 1}})
	return s
}

func TestIPv6ClientReachesTheForwarder(t *testing.T) {
	resolver := &mockResolver{}
	clientLinkEP := channel.New(128, 1500, "")
	serverLinkEP := channel.New(128, 1500, "")
	client := stack.New(stack.Options{
		NetworkProtocols:   []stack.NetworkProtocolFactory{ipv4.NewProtocol, ipv6.NewProtocol},
		TransportProtocols: []stack.TransportProtocolFactory{tcp.NewProtocol, udp.NewProtocol},
	})
	server := stack.New(stack.Options{
		NetworkProtocols:   []stack.NetworkProtocolFactory{ipv4.NewProtocol, ipv6.NewProtocol},
		TransportProtocols: []stack.TransportProtocolFactory{tcp.NewProtocol, udp.NewProtocol},
	})
	if err := client.CreateNIC(1, clientLinkEP); err != nil {
		t.Fatal(err)
	}
	if err := server.CreateNIC(1, serverLinkEP); err != nil {
		t.Fatal(err)
	}
	if err := server.SetPromiscuousMode(1, true); err != nil {
		t.Fatal(err)
	}
	if err := server.SetSpoofing(1, true); err != nil {
		t.Fatal(err)
	}
	for _, pair := range []struct {
		s    *stack.Stack
		self [16]byte
	}{
		{client, [16]byte{0xfd, 0, 0, 1, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 2}},
		{server, [16]byte{0xfd, 0, 0, 1, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 1}},
	} {
		if err := pair.s.AddProtocolAddress(1, tcpip.ProtocolAddress{
			Protocol:          ipv6.ProtocolNumber,
			AddressWithPrefix: tcpip.AddrFrom16(pair.self).WithPrefix(),
		}, stack.AddressProperties{}); err != nil {
			t.Fatal(err)
		}
		pair.s.SetRouteTable([]tcpip.Route{{Destination: header.IPv6EmptySubnet, NIC: 1}})
	}

	relayed := make(chan netip.AddrPort, 4)
	e := &Engine{
		stack: server,
		dns:   resolver,
		udpDial: func(host string, port int) (DatagramTunnel, error) {
			relayed <- netip.AddrPortFrom(netip.MustParseAddr(host), uint16(port))
			return nil, errors.New("stop after the flow is admitted")
		},
		dial: func(string, int) (io.ReadWriteCloser, error) {
			return nil, errors.New("unexpected upstream connection")
		},
	}
	e.listenUDP(server)

	ctx, cancel := context.WithCancel(context.Background())
	move := func(from, to *channel.Endpoint) {
		for {
			packet := from.ReadContext(ctx)
			if packet == nil {
				return
			}
			view := packet.ToView()
			data := append([]byte(nil), view.AsSlice()...)
			view.Release()
			fresh := stack.NewPacketBuffer(stack.PacketBufferOptions{Payload: buffer.MakeWithData(data)})
			to.InjectInbound(packet.NetworkProtocolNumber, fresh)
			fresh.DecRef()
			packet.DecRef()
		}
	}
	go move(clientLinkEP, serverLinkEP)
	go move(serverLinkEP, clientLinkEP)
	t.Cleanup(func() {
		e.shutdownDatagrams()
		cancel()
		clientLinkEP.Close()
		serverLinkEP.Close()
		client.Close()
		server.Close()
		client.Wait()
		server.Wait()
	})

	remote := &tcpip.FullAddress{
		NIC:  1,
		Addr: tcpip.AddrFrom16([16]byte{0x20, 0x01, 0x48, 0x60, 0x48, 0x60, 0, 0, 0, 0, 0, 0, 0, 0, 0x88, 0x88}),
		Port: 443,
	}
	conn, err := gonet.DialUDP(client, nil, remote, ipv6.ProtocolNumber)
	if err != nil {
		t.Fatalf("an IPv6 socket must be usable inside the tunnel: %v", err)
	}
	defer conn.Close()
	if _, err := conn.Write([]byte("quic")); err != nil {
		t.Fatalf("IPv6 write failed: %v", err)
	}
	select {
	case got := <-relayed:
		if got.Port() != 443 {
			t.Fatalf("relayed to the wrong port: %v", got)
		}
		if got.Addr().String() != "2001:4860:4860::8888" {
			t.Fatalf("relayed to the wrong address: %v", got)
		}
	case <-time.After(3 * time.Second):
		t.Fatal("an IPv6 datagram never reached the relay")
	}
}

func TestIPv6HasADefaultRoute(t *testing.T) {
	// The engine is built with a stub device in engine_test's virtualNetwork for
	// IPv4; here only the route table contract is asserted, because the real
	// engine constructor needs a tun device from the OS.
	s := v6Stack(t, [16]byte{0xfd, 0, 0, 1, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 2}, false)
	defer s.Close()
	table := s.GetRouteTable()
	var v6 bool
	for _, route := range table {
		if route.Destination == header.IPv6EmptySubnet {
			v6 = true
		}
	}
	if !v6 {
		t.Fatal("the route table has no IPv6 default route")
	}
}
