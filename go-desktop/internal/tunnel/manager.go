package tunnel

import (
	"context"
	"crypto/tls"
	"errors"
	"io"
	"net"
	"net/http"
	"strconv"
	"sync"
	"time"

	quic "github.com/quic-go/quic-go"
	"github.com/quic-go/quic-go/http3"
	"golang.org/x/net/http2"
)

type measure struct {
	average  float64
	samples  int
	failures int
	retry    time.Time
}

type h2Session struct {
	client *http2.ClientConn
	active int
}

type h3Session struct {
	conn   quic.Connection
	client *http3.ClientConn
}

type Manager struct {
	target    Target
	mu        sync.Mutex
	changed   *sync.Cond
	closed    bool
	h2        []*h2Session
	h2Opening int
	h3        *h3Session
	h3Opening bool
	streams   map[*trackedStream]struct{}
	count     int
	h2Score   measure
	h3Score   measure
	testTLS   *tls.Config
}

func NewManager(target Target) *Manager {
	manager := &Manager{target: target, streams: make(map[*trackedStream]struct{})}
	manager.changed = sync.NewCond(&manager.mu)
	return manager
}

func (m *Manager) Open(host string, port int, pass string, timeout time.Duration) (Dup, error) {
	if timeout <= 0 {
		timeout = 15 * time.Second
	}
	firstH3, canH3 := m.choice()
	var firstErr error
	for _, h3 := range []bool{firstH3, !firstH3} {
		if m.isClosed() {
			return nil, errors.New("tunnel is closed")
		}
		if h3 && !canH3 {
			continue
		}
		started := time.Now()
		var conn Dup
		var err error
		if h3 {
			budget := timeout
			if budget > 2500*time.Millisecond {
				budget = 2500 * time.Millisecond
			}
			conn, err = m.openH3(host, port, pass, budget)
		} else {
			conn, err = m.openH2(host, port, pass, timeout)
		}
		if err == nil {
			stream, trackErr := m.track(conn)
			if trackErr != nil {
				return nil, trackErr
			}
			m.success(h3, time.Since(started))
			return stream, nil
		}
		if isRefusal(err) {
			return nil, err
		}
		m.failed(h3)
		if firstErr == nil {
			firstErr = err
		}
	}
	if m.isClosed() {
		return nil, errors.New("tunnel is closed")
	}
	conn, err := openH1(m.target, host, port, pass, timeout)
	if err == nil {
		return m.track(conn)
	}
	if firstErr != nil {
		return nil, firstErr
	}
	return nil, err
}

func (m *Manager) isClosed() bool {
	m.mu.Lock()
	defer m.mu.Unlock()
	return m.closed
}

func (m *Manager) choice() (bool, bool) {
	m.mu.Lock()
	defer m.mu.Unlock()
	if m.closed {
		return false, false
	}
	canH3 := len(m.target.Chain) == 0 && m.target.DialHost != ""
	if !canH3 {
		return false, false
	}
	m.count++
	now := time.Now()
	h2Ready := !now.Before(m.h2Score.retry)
	h3Ready := !now.Before(m.h3Score.retry)
	switch {
	case !h2Ready && h3Ready:
		return true, true
	case h2Ready && !h3Ready:
		return false, true
	case !h2Ready && !h3Ready:
		return false, true
	case m.h2Score.samples == 0:
		return false, true
	case m.h3Score.samples == 0:
		return m.count%8 == 0, true
	}
	preferH3 := m.h3Score.average < m.h2Score.average*0.8
	if m.count%16 == 0 {
		return !preferH3, true
	}
	return preferH3, true
}

func (m *Manager) success(h3 bool, duration time.Duration) {
	m.mu.Lock()
	defer m.mu.Unlock()
	value := float64(duration.Microseconds())
	if value < 1 {
		value = 1
	}
	score := &m.h2Score
	if h3 {
		score = &m.h3Score
	}
	if score.samples == 0 {
		score.average = value
	} else {
		score.average = score.average*0.8 + value*0.2
	}
	score.samples++
	score.failures = 0
	score.retry = time.Time{}
}

func (m *Manager) failed(h3 bool) {
	m.mu.Lock()
	defer m.mu.Unlock()
	score := &m.h2Score
	if h3 {
		score = &m.h3Score
	}
	score.failures++
	delay := 10 * time.Second
	if h3 {
		delay = time.Minute
	}
	if score.failures > 1 {
		delay *= 3
	}
	if delay > 5*time.Minute {
		delay = 5 * time.Minute
	}
	score.retry = time.Now().Add(delay)
}

func (m *Manager) h2Client(timeout time.Duration) (*h2Session, error) {
	m.mu.Lock()
	for {
		if m.closed {
			m.mu.Unlock()
			return nil, errors.New("tunnel is closed")
		}
		alive := m.h2[:0]
		var best *h2Session
		for _, session := range m.h2 {
			if !session.client.CanTakeNewRequest() {
				if session.active > 0 {
					alive = append(alive, session)
				} else {
					_ = session.client.Close()
				}
				continue
			}
			alive = append(alive, session)
			if best == nil || session.active < best.active {
				best = session
			}
		}
		m.h2 = alive
		if best != nil && (best.active < 8 || len(m.h2)+m.h2Opening >= 2) {
			best.active++
			m.mu.Unlock()
			return best, nil
		}
		if len(m.h2)+m.h2Opening < 2 {
			m.h2Opening++
			m.mu.Unlock()
			raw, err := dialTLSConfigured(m.target, []string{"h2"}, timeout, m.testTLS)
			var client *http2.ClientConn
			if err == nil && raw.ConnectionState().NegotiatedProtocol != "h2" {
				raw.Close()
				err = errors.New("the proxy does not support HTTP/2")
			}
			if err == nil {
				// ReadIdleTimeout/PingTimeout turn a silently dead path into a
				// connection error instead of an indefinite hang: the transport
				// pings when idle and kills the conn when the peer stays mute.
				keepalive := &http2.Transport{
					DisableCompression: true,
					ReadIdleTimeout:    30 * time.Second,
					PingTimeout:        15 * time.Second,
				}
				client, err = keepalive.NewClientConn(raw)
				if err != nil {
					raw.Close()
				}
			}
			m.mu.Lock()
			m.h2Opening--
			m.changed.Broadcast()
			if err != nil {
				m.mu.Unlock()
				return nil, err
			}
			if m.closed {
				m.mu.Unlock()
				client.Close()
				return nil, errors.New("tunnel is closed")
			}
			session := &h2Session{client: client, active: 1}
			m.h2 = append(m.h2, session)
			m.mu.Unlock()
			return session, nil
		}
		m.changed.Wait()
	}
}

func (m *Manager) releaseH2(session *h2Session) {
	m.mu.Lock()
	if session.active > 0 {
		session.active--
	}
	m.changed.Broadcast()
	m.mu.Unlock()
}

func (m *Manager) openH2(host string, port int, pass string, timeout time.Duration) (Dup, error) {
	session, err := m.h2Client(timeout)
	if err != nil {
		return nil, err
	}
	stream, err := openStream(session.client, host, port, pass, timeout, func() { m.releaseH2(session) })
	if err != nil {
		m.releaseH2(session)
	}
	return stream, err
}

func (m *Manager) h3Client(timeout time.Duration) (*h3Session, error) {
	m.mu.Lock()
	for {
		if m.closed {
			m.mu.Unlock()
			return nil, errors.New("tunnel is closed")
		}
		if m.h3 != nil {
			select {
			case <-m.h3.conn.Context().Done():
				m.h3 = nil
			default:
				session := m.h3
				m.mu.Unlock()
				return session, nil
			}
		}
		if !m.h3Opening {
			m.h3Opening = true
			m.mu.Unlock()
			ctx, cancel := context.WithTimeout(context.Background(), timeout)
			config := &tls.Config{
				ServerName:         m.target.Host,
				NextProtos:         []string{http3.NextProtoH3},
				MinVersion:         tls.VersionTLS13,
				ClientSessionCache: sessionCache,
			}
			if m.testTLS != nil {
				config = m.testTLS.Clone()
				config.ServerName = m.target.Host
				config.NextProtos = []string{http3.NextProtoH3}
				config.MinVersion = tls.VersionTLS13
			}
			address := net.JoinHostPort(m.target.DialHost, strconv.Itoa(m.target.Port))
			conn, err := quic.DialAddr(ctx, address, config, &quic.Config{HandshakeIdleTimeout: timeout, MaxIdleTimeout: 45 * time.Second})
			cancel()
			var session *h3Session
			if err == nil {
				session = &h3Session{conn: conn, client: (&http3.Transport{DisableCompression: true}).NewClientConn(conn)}
			}
			m.mu.Lock()
			m.h3Opening = false
			m.changed.Broadcast()
			if err != nil {
				m.mu.Unlock()
				return nil, err
			}
			if m.closed {
				m.mu.Unlock()
				conn.CloseWithError(0, "")
				return nil, errors.New("tunnel is closed")
			}
			m.h3 = session
			m.mu.Unlock()
			return session, nil
		}
		m.changed.Wait()
	}
}

func (m *Manager) openH3(host string, port int, pass string, timeout time.Duration) (Dup, error) {
	session, err := m.h3Client(timeout)
	if err != nil {
		return nil, err
	}
	return openStream(session.client, host, port, pass, timeout, nil)
}

type roundTripper interface {
	RoundTrip(*http.Request) (*http.Response, error)
}

type proxyRefusal struct{ code int }

func (e *proxyRefusal) Error() string { return reason(e.code).Error() }
func isRefusal(err error) bool {
	var refused *proxyRefusal
	return errors.As(err, &refused) && (refused.code == 401 || refused.code == 403 || refused.code == 407 || refused.code == 429 || refused.code == 451)
}

func openStream(client roundTripper, host string, port int, pass string, timeout time.Duration, release func()) (Dup, error) {
	if host == "" || port < 1 || port > 65535 {
		return nil, errors.New("invalid destination")
	}
	reader, writer := io.Pipe()
	authority := net.JoinHostPort(host, strconv.Itoa(port))
	request, err := http.NewRequest(http.MethodConnect, "https://"+authority, reader)
	if err != nil {
		reader.Close()
		writer.Close()
		return nil, err
	}
	request.Host = authority
	request.Header.Set("Proxy-Authorization", "Bearer "+pass)
	request.Header.Set("User-Agent", agent)
	ctx, cancel := context.WithCancel(context.Background())
	timer := time.AfterFunc(timeout, cancel)
	response, err := client.RoundTrip(request.WithContext(ctx))
	timer.Stop()
	if err != nil {
		cancel()
		reader.CloseWithError(err)
		writer.CloseWithError(err)
		return nil, err
	}
	if response.StatusCode < 200 || response.StatusCode >= 300 {
		response.Body.Close()
		cancel()
		writer.Close()
		return nil, &proxyRefusal{code: response.StatusCode}
	}
	return &managedStream{body: response.Body, writer: writer, cancel: cancel, release: release}, nil
}

type managedStream struct {
	body    io.ReadCloser
	writer  *io.PipeWriter
	cancel  context.CancelFunc
	release func()
	once    sync.Once
}

func (s *managedStream) Read(p []byte) (int, error)  { return s.body.Read(p) }
func (s *managedStream) Write(p []byte) (int, error) { return s.writer.Write(p) }
func (s *managedStream) CloseWrite() error           { return s.writer.Close() }

func (s *managedStream) Close() error {
	var result error
	s.once.Do(func() {
		s.cancel()
		_ = s.writer.Close()
		result = s.body.Close()
		if s.release != nil {
			s.release()
		}
	})
	return result
}

type trackedStream struct {
	inner   Dup
	manager *Manager
	once    sync.Once
}

func (m *Manager) track(inner Dup) (Dup, error) {
	m.mu.Lock()
	if m.closed {
		m.mu.Unlock()
		inner.Close()
		return nil, errors.New("tunnel is closed")
	}
	stream := &trackedStream{inner: inner, manager: m}
	m.streams[stream] = struct{}{}
	m.mu.Unlock()
	return stream, nil
}

func (s *trackedStream) Read(p []byte) (int, error)  { return s.inner.Read(p) }
func (s *trackedStream) Write(p []byte) (int, error) { return s.inner.Write(p) }
func (s *trackedStream) CloseWrite() error {
	if writer, ok := s.inner.(interface{ CloseWrite() error }); ok {
		return writer.CloseWrite()
	}
	return errors.New("half-close unavailable")
}
func (s *trackedStream) Close() error {
	var err error
	s.once.Do(func() {
		err = s.inner.Close()
		s.manager.mu.Lock()
		delete(s.manager.streams, s)
		s.manager.mu.Unlock()
	})
	return err
}

func (m *Manager) Close() error {
	m.mu.Lock()
	if m.closed {
		m.mu.Unlock()
		return nil
	}
	m.closed = true
	sessions := m.h2
	h3 := m.h3
	streams := make([]*trackedStream, 0, len(m.streams))
	for stream := range m.streams {
		streams = append(streams, stream)
	}
	m.h2, m.h3 = nil, nil
	m.changed.Broadcast()
	m.mu.Unlock()
	for _, stream := range streams {
		_ = stream.Close()
	}
	for _, session := range sessions {
		_ = session.client.Close()
	}
	if h3 != nil {
		_ = h3.conn.CloseWithError(0, "")
	}
	return nil
}
