package warp

import (
	"context"
	"crypto/rand"
	"crypto/tls"
	"encoding/binary"
	"errors"
	"io"
	"net"
	"net/http"
	"net/netip"
	"net/url"
	"sync"
	"sync/atomic"
	"time"

	"golang.org/x/net/http2"
)

const connectAuthority = "cloudflareaccess.com:443"
const maxFlows = 128

// Dialer opens a TCP byte stream inside the authenticated Mozilla tunnel.
// It must never fall back to a direct network connection.
type Dialer func(context.Context, string, int) (io.ReadWriteCloser, error)

type fakeAddr string

func (a fakeAddr) Network() string { return "mozilla" }
func (a fakeAddr) String() string  { return string(a) }

type streamConn struct{ io.ReadWriteCloser }

func (c *streamConn) LocalAddr() net.Addr  { return fakeAddr("mozilla-local") }
func (c *streamConn) RemoteAddr() net.Addr { return fakeAddr("mozilla-relay") }
func (c *streamConn) SetDeadline(t time.Time) error {
	if v, ok := c.ReadWriteCloser.(interface{ SetDeadline(time.Time) error }); ok {
		return v.SetDeadline(t)
	}
	return nil // caller cancels by closing the underlying stream
}
func (c *streamConn) SetReadDeadline(t time.Time) error {
	if v, ok := c.ReadWriteCloser.(interface{ SetReadDeadline(time.Time) error }); ok {
		return v.SetReadDeadline(t)
	}
	return nil
}
func (c *streamConn) SetWriteDeadline(t time.Time) error {
	if v, ok := c.ReadWriteCloser.(interface{ SetWriteDeadline(time.Time) error }); ok {
		return v.SetWriteDeadline(t)
	}
	return nil
}

// HTTPClient reaches Cloudflare's registration API over the same Mozilla
// tunnel as the app. TLS still authenticates the actual API hostname.
func HTTPClient(dial Dialer) *http.Client {
	tr := &http.Transport{ForceAttemptHTTP2: true, DisableCompression: true,
		DialContext: func(ctx context.Context, _, address string) (net.Conn, error) {
			host, port, err := net.SplitHostPort(address)
			if err != nil || host != "api.cloudflareclient.com" || port != "443" {
				return nil, errors.New("unexpected WARP API destination")
			}
			conn, err := dial(ctx, host, 443)
			if err != nil {
				return nil, err
			}
			return &streamConn{conn}, nil
		},
	}
	return &http.Client{Transport: tr, Timeout: 25 * time.Second,
		CheckRedirect: func(*http.Request, []*http.Request) error { return http.ErrUseLastResponse }}
}

type Session struct {
	source  netip.Addr
	conn    net.Conn
	h2      *http2.ClientConn
	writer  *io.PipeWriter
	body    io.ReadCloser
	cancel  context.CancelFunc
	writeMu sync.Mutex
	mu      sync.Mutex
	flows   map[uint16]*Flow
	next    uint16
	closed  bool
	id      atomic.Uint32
}

type Flow struct {
	session                *Session
	target                 netip.Addr
	sourcePort, targetPort uint16
	queue                  chan []byte
	done                   chan struct{}
	mu                     sync.Mutex
	closed                 bool
	sending                bool
}

// openSession does not mark an endpoint usable until its caller has verified a
// full UDP round trip. It pins WARP's public key during the TLS handshake.
func openSession(ctx context.Context, identity *Identity, dial Dialer, endpoint string, port int) (*Session, error) {
	address, err := netip.ParseAddr(endpoint)
	if err != nil || !address.Is4() || !netip.MustParsePrefix("162.159.198.0/24").Contains(address) || port < 1 || port > 65535 {
		return nil, errors.New("invalid WARP endpoint")
	}
	tlsConfig, err := identity.tlsConfig()
	if err != nil {
		return nil, err
	}
	raw, err := dial(ctx, endpoint, port)
	if err != nil {
		return nil, err
	}
	transport := &streamConn{raw}
	stopCancel := context.AfterFunc(ctx, func() { _ = raw.Close() })
	ready := false
	defer func() {
		if !ready {
			stopCancel()
			_ = raw.Close()
		}
	}()
	secured := tls.Client(transport, tlsConfig)
	if err = secured.HandshakeContext(ctx); err != nil {
		return nil, errors.New("WARP TLS handshake failed")
	}
	if secured.ConnectionState().NegotiatedProtocol != "h2" {
		return nil, errors.New("WARP did not negotiate HTTP/2")
	}
	h2transport := &http2.Transport{DisableCompression: true}
	client, err := h2transport.NewClientConn(secured)
	if err != nil {
		return nil, err
	}
	streamContext, cancel := context.WithCancel(context.Background())
	reader, writer := io.Pipe()
	request := &http.Request{Method: http.MethodConnect,
		URL: &url.URL{Scheme: "https", Host: connectAuthority}, Host: connectAuthority,
		Header: make(http.Header), Body: reader, ContentLength: -1}
	request.Header.Set("cf-connect-proto", "cf-connect-ip")
	request.Header.Set("pq-enabled", "false")
	request.Header.Set("User-Agent", "")
	response, err := client.RoundTrip(request.WithContext(streamContext))
	if err != nil {
		cancel()
		_ = writer.Close()
		_ = reader.Close()
		_ = client.Close()
		return nil, errors.New("WARP CONNECT-IP request failed")
	}
	if response.StatusCode != http.StatusOK || response.Body == nil {
		cancel()
		_ = writer.Close()
		if response.Body != nil {
			_ = response.Body.Close()
		}
		_ = client.Close()
		return nil, errors.New("WARP CONNECT-IP refused")
	}
	if !stopCancel() && ctx.Err() != nil {
		cancel()
		_ = writer.Close()
		_ = response.Body.Close()
		_ = client.Close()
		return nil, ctx.Err()
	}
	source, _ := netip.ParseAddr(identity.IPv4)
	var seed [2]byte
	if _, err := rand.Read(seed[:]); err != nil {
		cancel()
		_ = writer.Close()
		_ = response.Body.Close()
		_ = client.Close()
		return nil, err
	}
	session := &Session{source: source, conn: secured, h2: client, writer: writer, body: response.Body,
		cancel: cancel, flows: make(map[uint16]*Flow), next: binary.BigEndian.Uint16(seed[:])}
	ready = true
	go session.readLoop()
	return session, nil
}

func (s *Session) Alive() bool { s.mu.Lock(); defer s.mu.Unlock(); return !s.closed }

func (s *Session) OpenUDP(host string, port int) (*Flow, error) {
	target, err := netip.ParseAddr(host)
	if err != nil || !target.Is4() || !target.IsGlobalUnicast() || port < 1 || port > 65535 {
		return nil, errors.New("unsupported WARP UDP destination")
	}
	s.mu.Lock()
	defer s.mu.Unlock()
	if s.closed || len(s.flows) >= maxFlows {
		return nil, errors.New("WARP tunnel unavailable")
	}
	for count := 0; count < 32768; count++ {
		s.next++
		if s.next < 32768 {
			s.next = 32768
		}
		if _, exists := s.flows[s.next]; exists {
			continue
		}
		flow := &Flow{session: s, target: target, sourcePort: s.next, targetPort: uint16(port), queue: make(chan []byte, 32), done: make(chan struct{})}
		s.flows[s.next] = flow
		return flow, nil
	}
	return nil, errors.New("WARP UDP ports exhausted")
}

func (s *Session) readLoop() {
	defer s.Close()
	for {
		packet, err := readPacket(s.body)
		if err != nil {
			return
		}
		parsed, err := parseUDP(packet)
		if err != nil || parsed.target != s.source {
			continue
		}
		s.mu.Lock()
		flow := s.flows[parsed.targetPort]
		if flow != nil && (flow.target != parsed.source || flow.targetPort != parsed.sourcePort) {
			flow = nil
		}
		s.mu.Unlock()
		if flow != nil {
			flow.deliver(parsed.payload)
		}
	}
}

func (f *Flow) deliver(payload []byte) {
	f.mu.Lock()
	defer f.mu.Unlock()
	if f.closed {
		return
	}
	select {
	case f.queue <- payload:
	default:
	} // bounded router queue
}

func (f *Flow) ReadDatagram() ([]byte, error) {
	select {
	case <-f.done:
		return nil, io.EOF
	case packet := <-f.queue:
		f.mu.Lock()
		defer f.mu.Unlock()
		if f.closed {
			return nil, io.EOF
		}
		return packet, nil
	}
}

func (f *Flow) WriteDatagram(payload []byte) error {
	packet, err := udpPacket(f.session.source, f.target, f.sourcePort, f.targetPort, payload, uint16(f.session.id.Add(1)))
	if err != nil {
		return err
	}
	capsule, err := encodePacket(packet)
	if err != nil {
		return err
	}
	s := f.session
	s.writeMu.Lock()
	defer s.writeMu.Unlock()
	f.mu.Lock()
	if f.closed {
		f.mu.Unlock()
		return io.ErrClosedPipe
	}
	f.sending = true
	f.mu.Unlock()
	_, err = s.writer.Write(capsule)
	f.mu.Lock()
	f.sending = false
	f.mu.Unlock()
	if err != nil {
		s.Close()
		return err
	}
	return nil
}

func (f *Flow) Close() error {
	f.mu.Lock()
	if f.closed {
		f.mu.Unlock()
		return nil
	}
	f.closed = true
	sending := f.sending
	close(f.done)
	f.mu.Unlock()
	s := f.session
	s.mu.Lock()
	if s.flows[f.sourcePort] == f {
		delete(s.flows, f.sourcePort)
	}
	s.mu.Unlock()
	// A blocked writer belongs to a shared H2 byte stream. Revoke the whole
	// stream rather than let a late packet escape after this flow closes.
	if sending {
		return s.Close()
	}
	return nil
}

func (s *Session) Close() error {
	s.mu.Lock()
	if s.closed {
		s.mu.Unlock()
		return nil
	}
	s.closed = true
	flows := make([]*Flow, 0, len(s.flows))
	for _, flow := range s.flows {
		flows = append(flows, flow)
	}
	s.flows = make(map[uint16]*Flow)
	s.mu.Unlock()
	s.cancel()
	_ = s.writer.CloseWithError(io.ErrClosedPipe)
	_ = s.body.Close()
	_ = s.h2.Close()
	_ = s.conn.Close()
	for _, flow := range flows {
		_ = flow.Close()
	}
	return nil
}
