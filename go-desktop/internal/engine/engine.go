package engine

import (
	"errors"
	"io"
	"net/netip"
	"runtime"
	"sync"
	"sync/atomic"
	"time"

	"github.com/Ammpapsoe/GozarFox/go-desktop/internal/dnsproxy"
	"github.com/Ammpapsoe/GozarFox/go-desktop/internal/geo"
	"github.com/Ammpapsoe/GozarFox/go-desktop/internal/tundev"
	"gvisor.dev/gvisor/pkg/tcpip"
	"gvisor.dev/gvisor/pkg/tcpip/adapters/gonet"
	"gvisor.dev/gvisor/pkg/tcpip/header"
	"gvisor.dev/gvisor/pkg/tcpip/network/ipv4"
	"gvisor.dev/gvisor/pkg/tcpip/network/ipv6"
	"gvisor.dev/gvisor/pkg/tcpip/stack"
	"gvisor.dev/gvisor/pkg/tcpip/transport/tcp"
	"gvisor.dev/gvisor/pkg/tcpip/transport/udp"
	"gvisor.dev/gvisor/pkg/waiter"
)

// mtu is what the tunnel device accepts from local applications. It is larger
// than an Ethernet frame because the device carries no Ethernet header, so the
// stack never has to fragment a full-size local segment before handing it to
// the proxy stream.
const mtu = 1600

// tunnelMTU keeps the WARP datagram path at 1280: its packet demultiplexer
// cannot reassemble larger fragments. On Windows without UDP, TCP terminates
// in the userspace stack and the separate upstream TCP socket remains subject
// to the host network stack's path-MTU handling. This is not a PMTU discovery
// implementation for the WARP or Android datagram paths.
func tunnelMTU(platform string, udpEnabled bool) int {
	if udpEnabled {
		// WARP's CONNECT-IP inner path uses a 1280-byte MTU; avoid emitting
		// UDP fragments that the initial packet demultiplexer cannot reassemble.
		return 1280
	}
	if platform == "windows" {
		return 9000
	}
	return mtu
}

// Copy buffers. A 32 KiB buffer cannot keep a long-haul TCP flow saturated:
// the sender stalls while userspace copies, so the receive window never fills
// and throughput stays far below the link. 64 KiB per direction was the old
// value and still capped a fast relay: on a 200 Mbit path a 64 KiB copy turn
// makes the pump wait for the kernel more often than it moves data. 128 KiB
// keeps two full 64 KiB window updates in flight per turn, which is the size
// where loopback copies stop showing up in profiles.
const relayBuffer = 128 * 1024

// relayPool recycles the copy buffers. Every relayed connection allocated one
// 128 KiB slice per direction and dropped it on close, so a browser opening and
// closing hundreds of flows churned tens of megabytes through the collector.
// The pool keeps that allocation out of the steady state; a buffer is always
// put back with its full length, never a truncated slice.
var relayPool = sync.Pool{
	New: func() any {
		buffer := make([]byte, relayBuffer)
		return &buffer
	},
}

func takeRelayBuffer() *[]byte {
	return relayPool.Get().(*[]byte)
}

func putRelayBuffer(buffer *[]byte) {
	if cap(*buffer) < relayBuffer {
		return
	}
	*buffer = (*buffer)[:relayBuffer]
	relayPool.Put(buffer)
}

func setTCP[T any](created *stack.Stack, value T) error {
	if err := created.SetTransportProtocolOption(tcp.ProtocolNumber, any(&value).(tcpip.SettableTransportProtocolOption)); err != nil {
		return errors.New(err.String())
	}
	return nil
}

func tuneTCP(created *stack.Stack) error {
	return tuneTCPForPlatform(created, runtime.GOOS)
}

func tuneTCPForPlatform(created *stack.Stack, platform string) error {
	// On the Windows Wintun path, sustained downloads stalled with RACK in
	// the real-device CI diagnostic. With it disabled five runs completed.
	// Keep other platforms unchanged; loss and WAN behavior still need study.
	recovery := tcpip.TCPRecovery(tcpip.TCPRACKLossDetection)
	if platform == "windows" {
		recovery = 0
	}
	for _, option := range []func() error{
		func() error { return setTCP(created, tcpip.TCPSACKEnabled(true)) },
		func() error { return setTCP(created, recovery) },
		func() error { return setTCP(created, tcpip.TCPModerateReceiveBufferOption(true)) },
		func() error { return setTCP(created, tcpip.TCPDelayEnabled(false)) },
		func() error { return setTCP(created, tcpip.TCPMinRTOOption(200*time.Millisecond)) },
		func() error { return setTCP(created, tcpip.TCPMaxRTOOption(60*time.Second)) },
		func() error { return setTCP(created, tcpip.TCPSynRetriesOption(5)) },
		func() error {
			return setTCP(created, tcpip.TCPSendBufferSizeRangeOption{Min: 4 << 10, Default: 64 << 10, Max: 4 << 20})
		},
		func() error {
			return setTCP(created, tcpip.TCPReceiveBufferSizeRangeOption{Min: 4 << 10, Default: 64 << 10, Max: 4 << 20})
		},
	} {
		if err := option(); err != nil {
			return err
		}
	}
	return nil
}

type Dialer func(host string, port int) (io.ReadWriteCloser, error)

// DatagramTunnel is a message-preserving, authenticated UDP relay stream.
// Close must unblock an in-progress ReadDatagram or WriteDatagram.
type DatagramTunnel interface {
	ReadDatagram() ([]byte, error)
	WriteDatagram([]byte) error
	Close() error
}

type DatagramDialer func(host string, port int) (DatagramTunnel, error)

type resolver interface {
	Resolve([]byte) ([]byte, error)
	Close()
}

type Engine struct {
	stack  *stack.Stack
	device *tundev.Device
	config tundev.Config
	dial   Dialer
	direct Dialer
	split  *geo.Rules
	dns    resolver
	// FakeDNS is prepared before traffic starts. Its durable owner outlives all
	// DNS publication and is released only after in-flight answers are drained.
	fakeDNS          *fakeDNS
	fakeOwner        io.Closer
	dnsPublishMu     sync.RWMutex
	dnsPublishClosed bool
	udpDial          DatagramDialer
	udpMu            sync.Mutex
	udpClosing       bool
	udpReservations  int
	udpFlows         map[*datagramFlow]struct{}
	down             atomic.Int64
	up               atomic.Int64
	routeMu          sync.Mutex
	once             sync.Once
}

func Start(proxyHost string, dial Dialer, protect []netip.Addr) (*Engine, error) {
	return start(proxyHost, dial, protect, nil, nil, nil)
}

func StartSplit(proxyHost string, dial, direct Dialer, protect []netip.Addr, split *geo.Rules) (*Engine, error) {
	return start(proxyHost, dial, protect, nil, direct, split)
}

// StartSplitUDP enables non-DNS UDP through the authenticated datagram dialer.
// A missing or refusing relay never causes a fallback to a direct UDP socket.
func StartSplitUDP(proxyHost string, dial, direct Dialer, protect []netip.Addr, split *geo.Rules, udpDial DatagramDialer) (*Engine, error) {
	return startUDP(proxyHost, dial, protect, nil, direct, split, udpDial)
}

// ErrFakeDNSUnsafeNamespace prevents accidental activation while other VPNs or
// applications can retain indistinguishable addresses from the same range.
var ErrFakeDNSUnsafeNamespace = errors.New("FakeDNS disabled: shared synthetic address identity is not safe")

// StartSplitUDPWithFakeDNS is intentionally unavailable in production. A
// machine-wide journal keeps Gozar allocations stable, but cannot identify
// answers cached from other independently allocated namespaces. Fail before
// provisioning files or changing the network; do not silently fall back here.
// Isolated tests exercise the implementation through startUDPManaged instead.
func StartSplitUDPWithFakeDNS(proxyHost string, dial, direct Dialer, protect []netip.Addr, split *geo.Rules, udpDial DatagramDialer) (*Engine, error) {
	return nil, ErrFakeDNSUnsafeNamespace
}

func start(proxyHost string, dial Dialer, protect []netip.Addr, testResolver resolver, direct Dialer, split *geo.Rules) (*Engine, error) {
	return startUDP(proxyHost, dial, protect, testResolver, direct, split, nil)
}

func startUDP(proxyHost string, dial Dialer, protect []netip.Addr, testResolver resolver, direct Dialer, split *geo.Rules, udpDial DatagramDialer) (*Engine, error) {
	return startUDPManaged(proxyHost, dial, protect, testResolver, direct, split, udpDial, nil, nil)
}

// The fake storage is exclusively owned by the engine, including every startup
// failure path. It must be installed before the device routes traffic.
func startUDPManaged(proxyHost string, dial Dialer, protect []netip.Addr, testResolver resolver, direct Dialer, split *geo.Rules, udpDial DatagramDialer, fake *fakeDNS, owner io.Closer) (*Engine, error) {
	started := false
	defer func() {
		if !started && owner != nil {
			_ = owner.Close()
		}
	}()
	if (fake == nil) != (owner == nil) {
		return nil, errors.New("invalid managed FakeDNS ownership")
	}
	if !tundev.Privileged() {
		return nil, errors.New("the tunnel needs administrator rights")
	}
	deviceMTU := tunnelMTU(runtime.GOOS, udpDial != nil)
	device, err := tundev.Open(deviceMTU)
	if err != nil {
		return nil, err
	}
	// IPv6 is a first-class protocol here, not an afterthought. The tunnel used
	// to run IPv4 only, so every application that prefers IPv6 (modern browsers
	// do, over Happy Eyeballs) either fell back after a timeout or, worse, had
	// its AAAA connection silently swallowed: the v6 SYN reached the device,
	// the stack had no protocol to parse it, and the packet died. That is the
	// "some sites load for ten seconds then give up" report.
	created := stack.New(stack.Options{
		NetworkProtocols:   []stack.NetworkProtocolFactory{ipv4.NewProtocol, ipv6.NewProtocol},
		TransportProtocols: []stack.TransportProtocolFactory{tcp.NewProtocolCUBIC, udp.NewProtocol},
	})
	if err := tuneTCP(created); err != nil {
		device.Close()
		created.Close()
		return nil, err
	}
	if err := created.CreateNIC(1, device.Endpoint()); err != nil {
		device.Close()
		return nil, errors.New(err.String())
	}
	if err := created.SetPromiscuousMode(1, true); err != nil {
		device.Close()
		created.Close()
		return nil, errors.New(err.String())
	}
	if err := created.SetSpoofing(1, true); err != nil {
		device.Close()
		created.Close()
		return nil, errors.New(err.String())
	}
	address := tcpip.AddrFrom4([4]byte{10, 111, 0, 2})
	if err := created.AddProtocolAddress(1, tcpip.ProtocolAddress{
		Protocol:          ipv4.ProtocolNumber,
		AddressWithPrefix: address.WithPrefix(),
	}, stack.AddressProperties{}); err != nil {
		device.Close()
		return nil, errors.New(err.String())
	}
	// A unique-local address for the v6 side of the same interface. Spoofing is
	// already enabled for the NIC, so outbound flows may use any source the
	// application picked; this address only has to exist for the stack to
	// answer neighbour solicitation and to give the route table a valid source.
	if err := created.AddProtocolAddress(1, tcpip.ProtocolAddress{
		Protocol:          ipv6.ProtocolNumber,
		AddressWithPrefix: tcpip.AddrFrom16([16]byte{0xfd, 0x00, 0x0d, 0x11, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 2}).WithPrefix(),
	}, stack.AddressProperties{}); err != nil {
		device.Close()
		created.Close()
		return nil, errors.New(err.String())
	}
	created.SetRouteTable([]tcpip.Route{
		{Destination: header.IPv4EmptySubnet, NIC: 1},
		{Destination: header.IPv6EmptySubnet, NIC: 1},
	})

	engine := &Engine{stack: created, device: device, dial: dial, direct: direct, split: split, dns: testResolver, udpDial: udpDial, fakeDNS: fake, fakeOwner: owner}
	if engine.dns == nil {
		engine.dns = dnsproxy.New(dial)
	}

	tcpForwarder := tcp.NewForwarder(created, 0, 4096, engine.handleTCP)
	created.SetTransportProtocolHandler(tcp.ProtocolNumber, tcpForwarder.HandlePacket)

	engine.listenUDP(created)

	keep := append([]netip.Addr{}, protect...)
	engine.config = tundev.Config{Interface: device.Name(), MTU: deviceMTU, DNS: dnsproxy.Address, Keep: keep}
	if err := tundev.Configure(&engine.config); err != nil {
		engine.dns.Close()
		device.Close()
		created.Close()
		created.Wait()
		engine.closeDNSPublication()
		owner = nil // closeDNSPublication already released the owner
		return nil, err
	}
	_ = proxyHost
	started = true
	return engine, nil
}

func (e *Engine) handleTCP(request *tcp.ForwarderRequest) {
	go func() {
		id := request.ID()
		host := id.LocalAddress.String()
		port := int(id.LocalPort)
		if port == 53 {
			queue := &waiter.Queue{}
			endpoint, terr := request.CreateEndpoint(queue)
			if terr != nil {
				request.Complete(true)
				return
			}
			request.Complete(false)
			e.serveDNS(gonet.NewTCPConn(queue, endpoint))
			return
		}
		remote, err := e.dialTCP(host, port)
		if err != nil {
			request.Complete(true)
			return
		}
		queue := &waiter.Queue{}
		endpoint, terr := request.CreateEndpoint(queue)
		if terr != nil {
			remote.Close()
			request.Complete(true)
			return
		}
		request.Complete(false)
		e.relay(gonet.NewTCPConn(queue, endpoint), remote)
	}()
}

func (e *Engine) listenUDP(created *stack.Stack) {
	forwarder := udp.NewForwarder(created, e.handleUDP)
	created.SetTransportProtocolHandler(udp.ProtocolNumber, func(id stack.TransportEndpointID, packet *stack.PacketBuffer) bool {
		if id.LocalPort != 53 && !e.allowSyntheticDatagram(id.LocalAddress.String()) {
			// Unknown synthetic addresses and identities whose routing policy
			// changed must not reach the datagram relay or a direct socket.
			return false
		}
		if id.LocalPort != 53 && !e.reserveDatagram() {
			// No relay, shutdown, or the bounded flow limit was reached.
			// Returning false lets the stack signal unreachable, not bypass.
			return false
		}
		return forwarder.HandlePacket(id, packet)
	})
}

func (e *Engine) handleUDP(request *udp.ForwarderRequest) {
	if request.ID().LocalPort != 53 {
		e.handleDatagram(request)
		return
	}
	go func() {
		queue := &waiter.Queue{}
		endpoint, terr := request.CreateEndpoint(queue)
		if terr != nil {
			return
		}
		conn := gonet.NewUDPConn(queue, endpoint)
		defer conn.Close()
		buffer := make([]byte, 65535)
		for {
			if err := conn.SetReadDeadline(time.Now().Add(45 * time.Second)); err != nil {
				return
			}
			count, err := conn.Read(buffer)
			if err != nil || count == 0 {
				return
			}
			query := append([]byte{}, buffer[:count]...)
			if err := e.publishDNS(query, true, func(answer []byte) error {
				n, err := conn.Write(answer)
				if err == nil && n != len(answer) {
					return io.ErrShortWrite
				}
				return err
			}); err != nil {
				return
			}
		}
	}()
}

func copyMetered(dst io.Writer, src io.Reader, counter *atomic.Int64) error {
	held := takeRelayBuffer()
	defer putRelayBuffer(held)
	buffer := *held
	for {
		count, readErr := src.Read(buffer)
		for offset := 0; offset < count; {
			written, err := dst.Write(buffer[offset:count])
			if written > 0 {
				offset += written
				counter.Add(int64(written))
			}
			if err != nil {
				return err
			}
			if written == 0 {
				return io.ErrShortWrite
			}
		}
		if readErr == io.EOF {
			return nil
		}
		if readErr != nil {
			return readErr
		}
	}
}

func (e *Engine) relay(local *gonet.TCPConn, remote io.ReadWriteCloser) {
	e.relayDuplex(local, remote)
}

func (e *Engine) relayDuplex(local, remote io.ReadWriteCloser) {
	done := make(chan error, 2)
	pump := func(dst, src io.ReadWriteCloser, counter *atomic.Int64) {
		err := copyMetered(dst, src, counter)
		if err == nil {
			if half, ok := dst.(interface{ CloseWrite() error }); ok {
				err = half.CloseWrite()
			} else {
				err = errors.New("half-close unavailable")
			}
		}
		done <- err
	}
	go pump(remote, local, &e.up)
	go pump(local, remote, &e.down)
	if err := <-done; err != nil {
		local.Close()
		remote.Close()
	}
	<-done
	local.Close()
	remote.Close()
}

func (e *Engine) Counters() (int64, int64) {
	return e.down.Load(), e.up.Load()
}

func (e *Engine) Device() string {
	return e.config.Interface
}

func (e *Engine) Bypass(address netip.Addr) error {
	e.routeMu.Lock()
	defer e.routeMu.Unlock()
	return tundev.Keep(&e.config, address)
}

func (e *Engine) Failures() <-chan error {
	return e.device.Failures()
}

func (e *Engine) Stop() {
	e.once.Do(func() {
		// Revoke datagram publication before network routes are restored.
		e.shutdownDatagrams()
		tundev.Restore(&e.config)
		if e.dns != nil {
			e.dns.Close()
		}
		if e.device != nil {
			e.device.Close()
		}
		if e.stack != nil {
			e.stack.Close()
			e.stack.Wait()
		}
		// Drain any response being written before releasing the durable owner;
		// later requests are rejected rather than publishing stale identities.
		e.closeDNSPublication()
		time.Sleep(200 * time.Millisecond)
	})
}

func (e *Engine) serveDNS(local io.ReadWriteCloser) {
	defer local.Close()
	for {
		size := make([]byte, 2)
		if _, err := io.ReadFull(local, size); err != nil {
			return
		}
		length := int(size[0])<<8 | int(size[1])
		if length < 12 {
			return
		}
		query := make([]byte, length)
		if _, err := io.ReadFull(local, query); err != nil {
			return
		}
		if err := e.publishDNS(query, false, func(answer []byte) error {
			framed := make([]byte, 2+len(answer))
			framed[0] = byte(len(answer) >> 8)
			framed[1] = byte(len(answer))
			copy(framed[2:], answer)
			n, err := local.Write(framed)
			if err == nil && n != len(framed) {
				return io.ErrShortWrite
			}
			return err
		}); err != nil {
			return
		}
	}
}
