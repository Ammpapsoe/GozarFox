package tundev

import (
	"context"
	"errors"
	"runtime"
	"sync"

	wtun "golang.zx2c4.com/wireguard/tun"
	"gvisor.dev/gvisor/pkg/buffer"
	"gvisor.dev/gvisor/pkg/tcpip"
	"gvisor.dev/gvisor/pkg/tcpip/header"
	"gvisor.dev/gvisor/pkg/tcpip/link/channel"
	"gvisor.dev/gvisor/pkg/tcpip/stack"
)

const Name = "Gozar"
const packetOffset = 16

type Device struct {
	link      wtun.Device
	end       *channel.Endpoint
	name      string
	mtu       int
	ctx       context.Context
	stop      context.CancelFunc
	pump      sync.WaitGroup
	failures  chan error
	closeOnce sync.Once
	closeErr  error
}

func Open(mtu int) (*Device, error) {
	name := Name
	switch runtime.GOOS {
	case "darwin":
		name = "utun"
	case "freebsd":
		name = "tun"
	}
	created, err := wtun.CreateTUN(name, mtu)
	if err != nil {
		return nil, err
	}
	actual, err := created.Name()
	if err != nil {
		created.Close()
		return nil, err
	}
	end := channel.New(512, uint32(mtu), "")
	ctx, stop := context.WithCancel(context.Background())
	device := &Device{link: created, end: end, name: actual, mtu: mtu, ctx: ctx, stop: stop, failures: make(chan error, 1)}
	device.pump.Add(2)
	go device.readLoop()
	go device.writeLoop()
	return device, nil
}

func (d *Device) Endpoint() stack.LinkEndpoint { return d.end }

func (d *Device) Name() string { return d.name }

func (d *Device) Failures() <-chan error { return d.failures }

func (d *Device) fail(err error) {
	if err == nil || d.ctx.Err() != nil {
		return
	}
	select {
	case d.failures <- err:
	default:
	}
	d.stop()
}

func (d *Device) readLoop() {
	defer d.pump.Done()
	batch := d.link.BatchSize()
	bufs := make([][]byte, batch)
	sizes := make([]int, batch)
	for index := range bufs {
		bufs[index] = make([]byte, d.mtu+packetOffset)
	}
	for {
		count, err := d.link.Read(bufs, sizes, packetOffset)
		if err != nil {
			d.fail(err)
			return
		}
		if count < 0 || count > len(bufs) {
			d.fail(errors.New("invalid tunnel packet count"))
			return
		}
		for index := 0; index < count; index++ {
			if sizes[index] < 0 || sizes[index] > d.mtu {
				d.fail(errors.New("invalid tunnel packet length"))
				return
			}
			packet := bufs[index][packetOffset : packetOffset+sizes[index]]
			if len(packet) == 0 {
				continue
			}
			proto := header.IPv4ProtocolNumber
			if packet[0]>>4 == 6 {
				proto = header.IPv6ProtocolNumber
			}
			payload := buffer.MakeWithData(append([]byte{}, packet...))
			incoming := stack.NewPacketBuffer(stack.PacketBufferOptions{Payload: payload})
			d.end.InjectInbound(proto, incoming)
			incoming.DecRef()
		}
	}
}

func (d *Device) writeLoop() {
	defer d.pump.Done()
	for {
		packet := d.end.ReadContext(d.ctx)
		if packet == nil {
			return
		}
		view := packet.ToView()
		packet.DecRef()
		data := view.AsSlice()
		if len(data) > 0 {
			outgoing := make([]byte, packetOffset+len(data))
			copy(outgoing[packetOffset:], data)
			_, err := d.link.Write([][]byte{outgoing}, packetOffset)
			if err != nil {
				view.Release()
				d.fail(err)
				return
			}
		}
		view.Release()
	}
}

func (d *Device) Close() error {
	d.closeOnce.Do(func() {
		d.stop()
		d.end.Close()
		d.closeErr = d.link.Close()
		d.pump.Wait()
		close(d.failures)
	})
	return d.closeErr
}

var Address = tcpip.AddrFrom4([4]byte{10, 111, 0, 2})
