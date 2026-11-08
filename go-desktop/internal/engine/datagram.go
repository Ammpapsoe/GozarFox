package engine

import (
	"sync"
	"time"

	"gvisor.dev/gvisor/pkg/tcpip/adapters/gonet"
	"gvisor.dev/gvisor/pkg/tcpip/transport/udp"
	"gvisor.dev/gvisor/pkg/waiter"
)

const (
	maxDatagramFlows = 128
	datagramIdle = 90 * time.Second
)

// datagramFlow prevents a delayed upstream open from escaping Stop. The local
// endpoint is owned immediately; a remote stream may be attached only while
// the flow is still live. Neither path can fall back to a direct UDP socket.
type datagramFlow struct {
	mu sync.Mutex
	local *gonet.UDPConn
	remote DatagramTunnel
	closed bool
}

func (f *datagramFlow) attach(remote DatagramTunnel) bool {
	f.mu.Lock()
	defer f.mu.Unlock()
	if f.closed {
		return false
	}
	f.remote = remote
	return true
}

func (f *datagramFlow) close() {
	f.mu.Lock()
	if f.closed {
		f.mu.Unlock()
		return
	}
	f.closed = true
	local, remote := f.local, f.remote
	f.mu.Unlock()
	if local != nil {
		_ = local.Close()
	}
	if remote != nil {
		_ = remote.Close()
	}
}

// Reserve before constructing a gVisor endpoint, so both pending opens and
// established flows count against the bound. Returning false causes an ICMP
// unreachable rather than a direct-socket fallback.
func (e *Engine) reserveDatagram() bool {
	e.udpMu.Lock()
	defer e.udpMu.Unlock()
	if e.udpClosing || e.udpDial == nil || len(e.udpFlows)+e.udpReservations >= maxDatagramFlows {
		return false
	}
	e.udpReservations++
	return true
}

func (e *Engine) cancelDatagramReservation() {
	e.udpMu.Lock()
	e.udpReservations--
	e.udpMu.Unlock()
}

func (e *Engine) registerDatagram(flow *datagramFlow) bool {
	e.udpMu.Lock()
	defer e.udpMu.Unlock()
	e.udpReservations--
	if e.udpClosing {
		return false
	}
	if e.udpFlows == nil {
		e.udpFlows = make(map[*datagramFlow]struct{})
	}
	e.udpFlows[flow] = struct{}{}
	return true
}

func (e *Engine) forgetDatagram(flow *datagramFlow) {
	e.udpMu.Lock()
	delete(e.udpFlows, flow)
	e.udpMu.Unlock()
}

func (e *Engine) shutdownDatagrams() {
	e.udpMu.Lock()
	e.udpClosing = true
	flows := make([]*datagramFlow, 0, len(e.udpFlows))
	for flow := range e.udpFlows {
		flows = append(flows, flow)
	}
	e.udpMu.Unlock()
	for _, flow := range flows {
		flow.close()
	}
}

func (e *Engine) handleDatagram(request *udp.ForwarderRequest) {
	id := request.ID()
	queue := &waiter.Queue{}
	endpoint, err := request.CreateEndpoint(queue)
	if err != nil {
		e.cancelDatagramReservation()
		return
	}
	flow := &datagramFlow{local: gonet.NewUDPConn(queue, endpoint)}
	if !e.registerDatagram(flow) {
		flow.close()
		return
	}
	go e.relayDatagrams(flow, id.LocalAddress.String(), int(id.LocalPort))
}

func (e *Engine) relayDatagrams(flow *datagramFlow, host string, port int) {
	defer e.forgetDatagram(flow)
	defer flow.close()
	remote, err := e.udpDial(host, port)
	if err != nil || remote == nil {
		if remote != nil {
			_ = remote.Close()
		}
		return
	}
	if !flow.attach(remote) {
		_ = remote.Close()
		return
	}
	done := make(chan struct{}, 2)
	go func() {
		defer func() { done <- struct{}{} }()
		buffer := make([]byte, 65535)
		for {
			if flow.local.SetReadDeadline(time.Now().Add(datagramIdle)) != nil {
				return
			}
			count, err := flow.local.Read(buffer)
			if err != nil {
				return
			}
			// A zero-length read with no error is a valid UDP datagram.
			if err := remote.WriteDatagram(buffer[:count]); err != nil {
				return
			}
			e.up.Add(int64(count))
		}
	}()
	go func() {
		defer func() { done <- struct{}{} }()
		for {
			payload, err := remote.ReadDatagram()
			if err != nil {
				return
			}
			if len(payload) > 65507 {
				return
			}
			count, err := flow.local.Write(payload)
			if err != nil || count != len(payload) {
				return
			}
			// Incoming traffic also keeps the bidirectional flow alive.
			if flow.local.SetReadDeadline(time.Now().Add(datagramIdle)) != nil {
				return
			}
			e.down.Add(int64(count))
		}
	}()
	<-done
	flow.close()
	<-done
}
