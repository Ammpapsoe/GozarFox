package engine

import (
	"context"
	"encoding/binary"
	"errors"
	"io"
	"sync/atomic"
	"testing"
	"time"

	"golang.org/x/net/dns/dnsmessage"
	"gvisor.dev/gvisor/pkg/buffer"
	"gvisor.dev/gvisor/pkg/tcpip"
	"gvisor.dev/gvisor/pkg/tcpip/adapters/gonet"
	"gvisor.dev/gvisor/pkg/tcpip/header"
	"gvisor.dev/gvisor/pkg/tcpip/link/channel"
	"gvisor.dev/gvisor/pkg/tcpip/network/ipv4"
	"gvisor.dev/gvisor/pkg/tcpip/stack"
	"gvisor.dev/gvisor/pkg/tcpip/transport/tcp"
	"gvisor.dev/gvisor/pkg/tcpip/transport/udp"
)

type mockResolver struct {
	queries atomic.Int32
	fail    atomic.Bool
}

func (r *mockResolver) Resolve(query []byte) ([]byte, error) {
	r.queries.Add(1)
	if r.fail.Load() {
		return nil, errors.New("upstream failed")
	}
	var message dnsmessage.Message
	if err := message.Unpack(query); err != nil {
		return nil, err
	}
	message.Response = true
	message.RecursionAvailable = true
	return message.Pack()
}

func (*mockResolver) Close() {}

func packetQuery(t *testing.T, id uint16) []byte {
	t.Helper()
	name, err := dnsmessage.NewName("example.org.")
	if err != nil {
		t.Fatal(err)
	}
	message := dnsmessage.Message{
		Header:    dnsmessage.Header{ID: id, RecursionDesired: true},
		Questions: []dnsmessage.Question{{Name: name, Type: dnsmessage.TypeA, Class: dnsmessage.ClassINET}},
	}
	wire, err := message.Pack()
	if err != nil {
		t.Fatal(err)
	}
	return wire
}

func checkDNSReply(t *testing.T, answer []byte, id uint16, code dnsmessage.RCode) {
	t.Helper()
	var message dnsmessage.Message
	if err := message.Unpack(answer); err != nil {
		t.Fatal(err)
	}
	if !message.Response || message.ID != id || message.RCode != code {
		t.Fatalf("unexpected DNS reply: %+v", message.Header)
	}
}

func virtualStack(t *testing.T, address [4]byte, permissive bool) (*stack.Stack, *channel.Endpoint) {
	t.Helper()
	link := channel.New(128, 1500, "")
	s := stack.New(stack.Options{
		NetworkProtocols:   []stack.NetworkProtocolFactory{ipv4.NewProtocol},
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
		Protocol:          ipv4.ProtocolNumber,
		AddressWithPrefix: tcpip.AddrFrom4(address).WithPrefix(),
	}, stack.AddressProperties{}); err != nil {
		t.Fatal(err)
	}
	s.SetRouteTable([]tcpip.Route{{Destination: header.IPv4EmptySubnet, NIC: 1}})
	return s, link
}

func virtualNetwork(t *testing.T, resolver *mockResolver) (*stack.Stack, *Engine, <-chan []byte) {
	return virtualNetworkUDP(t, resolver, nil)
}

func virtualNetworkUDP(t *testing.T, resolver *mockResolver, udpDial DatagramDialer) (*stack.Stack, *Engine, <-chan []byte) {
	t.Helper()
	client, clientLink := virtualStack(t, [4]byte{10, 111, 0, 2}, false)
	server, serverLink := virtualStack(t, [4]byte{10, 111, 0, 1}, true)
	e := &Engine{stack: server, dns: resolver, udpDial: udpDial, dial: func(host string, port int) (io.ReadWriteCloser, error) {
		return nil, errors.New("unexpected upstream connection")
	}}
	tcpForwarder := tcp.NewForwarder(server, 0, 64, e.handleTCP)
	server.SetTransportProtocolHandler(tcp.ProtocolNumber, tcpForwarder.HandlePacket)
	e.listenUDP(server)
	ctx, cancel := context.WithCancel(context.Background())
	icmp := make(chan []byte, 4)
	move := func(from, to *channel.Endpoint, inspect bool) {
		for {
			packet := from.ReadContext(ctx)
			if packet == nil {
				return
			}
			view := packet.ToView()
			data := append([]byte(nil), view.AsSlice()...)
			view.Release()
			if inspect && len(data) >= 28 && header.IPv4(data).Protocol() == uint8(header.ICMPv4ProtocolNumber) {
				select {
				case icmp <- data:
				default:
				}
			}
			fresh := stack.NewPacketBuffer(stack.PacketBufferOptions{Payload: buffer.MakeWithData(data)})
			to.InjectInbound(packet.NetworkProtocolNumber, fresh)
			fresh.DecRef()
			packet.DecRef()
		}
	}

	go move(clientLink, serverLink, false)
	go move(serverLink, clientLink, true)
	t.Cleanup(func() {
		e.shutdownDatagrams()
		cancel()
		clientLink.Close()
		serverLink.Close()
		client.Close()
		server.Close()
		client.Wait()
		server.Wait()
	})
	return client, e, icmp
}

func TestUDPPort53ForAnyDestination(t *testing.T) {
	r := &mockResolver{}
	client, _, _ := virtualNetwork(t, r)
	for index, destination := range [][4]byte{{1, 1, 1, 1}, {8, 8, 8, 8}} {
		remote := &tcpip.FullAddress{NIC: 1, Addr: tcpip.AddrFrom4(destination), Port: 53}
		conn, err := gonet.DialUDP(client, nil, remote, ipv4.ProtocolNumber)
		if err != nil {
			t.Fatal(err)
		}
		_ = conn.SetDeadline(time.Now().Add(3 * time.Second))
		id := uint16(index + 10)
		if _, err := conn.Write(packetQuery(t, id)); err != nil {
			conn.Close()
			t.Fatal(err)
		}
		answer := make([]byte, 4096)
		n, err := conn.Read(answer)
		conn.Close()
		if err != nil {
			t.Fatal(err)
		}
		checkDNSReply(t, answer[:n], id, dnsmessage.RCodeSuccess)
	}
	if r.queries.Load() != 2 {
		t.Fatalf("wanted 2 queries, got %d", r.queries.Load())
	}
}

func TestUDPFailureReturnsSERVFAIL(t *testing.T) {
	r := &mockResolver{}
	r.fail.Store(true)
	client, _, _ := virtualNetwork(t, r)
	remote := &tcpip.FullAddress{NIC: 1, Addr: tcpip.AddrFrom4([4]byte{9, 9, 9, 9}), Port: 53}
	conn, err := gonet.DialUDP(client, nil, remote, ipv4.ProtocolNumber)
	if err != nil {
		t.Fatal(err)
	}
	defer conn.Close()
	_ = conn.SetDeadline(time.Now().Add(3 * time.Second))
	if _, err := conn.Write(packetQuery(t, 20)); err != nil {
		t.Fatal(err)
	}
	answer := make([]byte, 4096)
	n, err := conn.Read(answer)
	if err != nil {
		t.Fatal(err)
	}
	checkDNSReply(t, answer[:n], 20, dnsmessage.RCodeServerFailure)
}

func TestOtherUDPIsRefusedWithICMP(t *testing.T) {
	r := &mockResolver{}
	client, _, icmp := virtualNetwork(t, r)
	remote := &tcpip.FullAddress{NIC: 1, Addr: tcpip.AddrFrom4([4]byte{8, 8, 8, 8}), Port: 443}
	conn, err := gonet.DialUDP(client, nil, remote, ipv4.ProtocolNumber)
	if err != nil {
		t.Fatal(err)
	}
	defer conn.Close()
	if _, err := conn.Write([]byte("quic")); err != nil {
		t.Fatal(err)
	}
	select {
	case data := <-icmp:
		ip := header.IPv4(data)
		payload := data[ip.HeaderLength():]
		if len(payload) < 2 || payload[0] != 3 || payload[1] != 3 {
			t.Fatalf("expected ICMP port unreachable, got %x", payload)
		}
	case <-time.After(3 * time.Second):
		t.Fatal("unsupported UDP was silently dropped")
	}
	if r.queries.Load() != 0 {
		t.Fatal("non-DNS UDP reached the DNS resolver")
	}
}

func TestTCPPort53ForAnyDestination(t *testing.T) {
	r := &mockResolver{}
	client, _, _ := virtualNetwork(t, r)
	ctx, cancel := context.WithTimeout(context.Background(), 3*time.Second)
	defer cancel()
	remote := tcpip.FullAddress{NIC: 1, Addr: tcpip.AddrFrom4([4]byte{8, 8, 4, 4}), Port: 53}
	conn, err := gonet.DialContextTCP(ctx, client, remote, ipv4.ProtocolNumber)
	if err != nil {
		t.Fatal(err)
	}
	defer conn.Close()
	_ = conn.SetDeadline(time.Now().Add(3 * time.Second))
	for index := 0; index < 2; index++ {
		id := uint16(index + 50)
		if index == 1 {
			r.fail.Store(true)
		}
		query := packetQuery(t, id)
		frame := make([]byte, len(query)+2)
		binary.BigEndian.PutUint16(frame, uint16(len(query)))
		copy(frame[2:], query)
		if _, err := conn.Write(frame); err != nil {
			t.Fatal(err)
		}
		size := make([]byte, 2)
		if _, err := io.ReadFull(conn, size); err != nil {
			t.Fatal(err)
		}
		answer := make([]byte, binary.BigEndian.Uint16(size))
		if _, err := io.ReadFull(conn, answer); err != nil {
			t.Fatal(err)
		}
		code := dnsmessage.RCodeSuccess
		if index == 1 {
			code = dnsmessage.RCodeServerFailure
		}
		checkDNSReply(t, answer, id, code)
	}
	if r.queries.Load() != 2 {
		t.Fatalf("wanted 2 queries, got %d", r.queries.Load())
	}
}
