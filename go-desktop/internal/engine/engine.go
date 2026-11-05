package engine

import (
	"errors"
	"io"
	"net/netip"
	"sync"
	"sync/atomic"
	"time"

	"github.com/bardia049b-stack/Gozar1/go-desktop/internal/dnsproxy"
	"github.com/bardia049b-stack/Gozar1/go-desktop/internal/geo"
	"github.com/bardia049b-stack/Gozar1/go-desktop/internal/tundev"
	"gvisor.dev/gvisor/pkg/tcpip"
	"gvisor.dev/gvisor/pkg/tcpip/adapters/gonet"
	"gvisor.dev/gvisor/pkg/tcpip/header"
	"gvisor.dev/gvisor/pkg/tcpip/network/ipv4"
	"gvisor.dev/gvisor/pkg/tcpip/stack"
	"gvisor.dev/gvisor/pkg/tcpip/transport/tcp"
	"gvisor.dev/gvisor/pkg/tcpip/transport/udp"
	"gvisor.dev/gvisor/pkg/waiter"
)

const mtu = 1500

func setTCP[T any](created *stack.Stack, value T) error {
	if err := created.SetTransportProtocolOption(tcp.ProtocolNumber, any(&value).(tcpip.SettableTransportProtocolOption)); err != nil {
		return errors.New(err.String())
	}
	return nil
}

func tuneTCP(created *stack.Stack) error {
	for _, option := range []func() error{
		func() error { return setTCP(created, tcpip.TCPSACKEnabled(true)) },
		func() error { return setTCP(created, tcpip.TCPRecovery(tcpip.TCPRACKLossDetection)) },
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
	down    atomic.Int64
	up      atomic.Int64
	routeMu sync.Mutex
	once    sync.Once
}

func Start(proxyHost string, dial Dialer, protect []netip.Addr) (*Engine, error) {
	return start(proxyHost, dial, protect, nil, nil, nil)
}

func StartSplit(proxyHost string, dial, direct Dialer, protect []netip.Addr, split *geo.Rules) (*Engine, error) {
	return start(proxyHost, dial, protect, nil, direct, split)
}

func start(proxyHost string, dial Dialer, protect []netip.Addr, testResolver resolver, direct Dialer, split *geo.Rules) (*Engine, error) {
	if !tundev.Privileged() {
		return nil, errors.New("the tunnel needs administrator rights")
	}
	device, err := tundev.Open(mtu)
	if err != nil {
		return nil, err
	}
	created := stack.New(stack.Options{
		NetworkProtocols:   []stack.NetworkProtocolFactory{ipv4.NewProtocol},
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
	created.SetRouteTable([]tcpip.Route{
		{Destination: header.IPv4EmptySubnet, NIC: 1},
	})

	engine := &Engine{stack: created, device: device, dial: dial, direct: direct, split: split, dns: testResolver}
	if engine.dns == nil {
		engine.dns = dnsproxy.New(dial)
	}

	tcpForwarder := tcp.NewForwarder(created, 0, 4096, engine.handleTCP)
	created.SetTransportProtocolHandler(tcp.ProtocolNumber, tcpForwarder.HandlePacket)

	engine.listenUDP(created)

	keep := append([]netip.Addr{}, protect...)
	engine.config = tundev.Config{Interface: device.Name(), DNS: dnsproxy.Address, Keep: keep}
	if err := tundev.Configure(&engine.config); err != nil {
		engine.dns.Close()
		device.Close()
		created.Close()
		return nil, err
	}
	_ = proxyHost
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
		var remote io.ReadWriteCloser
		var err error
		if e.split != nil && e.direct != nil && e.split.DirectAddress(netip.MustParseAddr(host)) {
			remote, err = e.direct(host, port)
		} else {
			remote, err = e.dial(host, port)
		}
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
		if id.LocalPort != 53 {
			return false
		}
		return forwarder.HandlePacket(id, packet)
	})
}

func (e *Engine) handleUDP(request *udp.ForwarderRequest) {
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
			answer, err := e.dns.Resolve(query)
			if err != nil {
				answer = dnsproxy.Failure(query)
			}
			answer = dnsproxy.LimitUDP(query, answer)
			if len(answer) == 0 {
				continue
			}
			if _, err := conn.Write(answer); err != nil {
				return
			}
			if e.split != nil {
				e.split.Learn(query, answer)
			}
			e.down.Add(int64(len(answer)))
		}
	}()
}

func copyMetered(dst io.Writer, src io.Reader, counter *atomic.Int64) error {
	buffer := make([]byte, 32*1024)
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
		answer, err := e.dns.Resolve(query)
		if err != nil {
			answer = dnsproxy.Failure(query)
		}
		if len(answer) == 0 || len(answer) > 65535 {
			return
		}
		if e.split != nil {
			e.split.Learn(query, answer)
		}
		framed := make([]byte, 2+len(answer))
		framed[0] = byte(len(answer) >> 8)
		framed[1] = byte(len(answer))
		copy(framed[2:], answer)
		if _, err := local.Write(framed); err != nil {
			return
		}
		e.down.Add(int64(len(answer)))
	}
}
