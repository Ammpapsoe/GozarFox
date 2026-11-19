// Package localproxy implements a loopback-only HTTP proxy for desktop modes.
package localproxy

import (
	"bufio"
	"context"
	"errors"
	"io"
	"net"
	"net/http"
	"strconv"
	"strings"
	"sync"
	"time"
)

type Dialer func(host string, port int) (io.ReadWriteCloser, error)

type Server struct {
	listener  net.Listener
	http      *http.Server
	transport *http.Transport
	dial      Dialer
	ctx       context.Context
	cancel    context.CancelFunc
	mu        sync.Mutex
	closed    bool
	active    map[net.Conn]struct{}
}

// Listen binds only IPv4 loopback. It must not expose an unauthenticated proxy
// to the local network, even if the caller supplies an arbitrary port.
func Listen(port int, dial Dialer) (*Server, error) {
	if port < 0 || port > 65535 || dial == nil {
		return nil, errors.New("invalid local proxy configuration")
	}
	listener, err := net.Listen("tcp4", net.JoinHostPort("127.0.0.1", strconv.Itoa(port)))
	if err != nil {
		return nil, err
	}
	ctx, cancel := context.WithCancel(context.Background())
	s := &Server{listener: listener, dial: dial, ctx: ctx, cancel: cancel, active: make(map[net.Conn]struct{})}
	s.transport = &http.Transport{
		Proxy:                 nil,
		DisableCompression:    true,
		DialContext:           s.dialContext,
		ResponseHeaderTimeout: 30 * time.Second,
		IdleConnTimeout:       30 * time.Second,
	}
	s.http = &http.Server{Handler: s, ReadHeaderTimeout: 15 * time.Second, MaxHeaderBytes: 32 << 10}
	go func() { _ = s.http.Serve(listener) }()
	return s, nil
}

func (s *Server) Port() int { return s.listener.Addr().(*net.TCPAddr).Port }

// Close terminates both HTTP requests and hijacked CONNECT sockets. Hijacked
// connections are not managed by http.Server.Close, so they are tracked here.
func (s *Server) Close() error {
	s.mu.Lock()
	if s.closed {
		s.mu.Unlock()
		return nil
	}
	s.closed = true
	s.cancel()
	connections := make([]net.Conn, 0, len(s.active))
	for conn := range s.active {
		connections = append(connections, conn)
	}
	s.mu.Unlock()
	// Close both halves of hijacked tunnels. Closing only the client leaves a
	// half-closed upstream stream and bridge goroutine alive if the remote peer
	// waits indefinitely; never call user-supplied stream Close under s.mu.
	for _, conn := range connections {
		_ = conn.Close()
	}
	s.transport.CloseIdleConnections()
	return s.http.Close()
}

func (s *Server) dialContext(ctx context.Context, network, address string) (net.Conn, error) {
	if network != "tcp" && network != "tcp4" && network != "tcp6" {
		return nil, errors.New("unsupported proxy network")
	}
	host, port, err := target(address, "80")
	if err != nil {
		return nil, err
	}
	type result struct {
		stream io.ReadWriteCloser
		err    error
	}
	ready := make(chan result)
	go func() {
		stream, err := s.dial(host, port)
		select {
		case ready <- result{stream, err}:
		case <-ctx.Done():
			if stream != nil {
				_ = stream.Close()
			}
		}
	}()
	select {
	case <-ctx.Done():
		return nil, ctx.Err()
	case res := <-ready:
		if res.err != nil {
			if res.stream != nil {
				_ = res.stream.Close()
			}
			return nil, res.err
		}
		if res.stream == nil {
			return nil, errors.New("empty tunnel stream")
		}
		return newStreamConn(res.stream), nil
	}
}

// streamConn adapts the tunnel stream to net.Conn. Deadlines close the stream
// because tunnel streams do not expose independent read/write deadline APIs.
type streamConn struct {
	stream                          io.ReadWriteCloser
	mu                              sync.Mutex
	closed                          bool
	readTimer, writeTimer           *time.Timer
	readGeneration, writeGeneration uint64
}

func newStreamConn(stream io.ReadWriteCloser) *streamConn { return &streamConn{stream: stream} }
func (c *streamConn) Read(p []byte) (int, error)          { return c.stream.Read(p) }
func (c *streamConn) Write(p []byte) (int, error)         { return c.stream.Write(p) }
func (c *streamConn) CloseWrite() error {
	if half, ok := c.stream.(interface{ CloseWrite() error }); ok {
		return half.CloseWrite()
	}
	return errors.New("tunnel does not support half-close")
}
func (c *streamConn) Close() error {
	c.mu.Lock()
	if c.closed {
		c.mu.Unlock()
		return nil
	}
	c.closed = true
	if c.readTimer != nil {
		c.readTimer.Stop()
	}
	if c.writeTimer != nil {
		c.writeTimer.Stop()
	}
	c.mu.Unlock()
	return c.stream.Close()
}
func (*streamConn) LocalAddr() net.Addr                  { return addr("loopback") }
func (*streamConn) RemoteAddr() net.Addr                 { return addr("tunnel") }
func (c *streamConn) SetDeadline(t time.Time) error      { return c.setDeadline(t, true, true) }
func (c *streamConn) SetReadDeadline(t time.Time) error  { return c.setDeadline(t, true, false) }
func (c *streamConn) SetWriteDeadline(t time.Time) error { return c.setDeadline(t, false, true) }
func (c *streamConn) setDeadline(t time.Time, read, write bool) error {
	c.mu.Lock()
	defer c.mu.Unlock()
	if c.closed {
		return net.ErrClosed
	}
	set := func(timer **time.Timer, generation *uint64, reading bool) {
		*generation++
		current := *generation
		if *timer != nil {
			(*timer).Stop()
			*timer = nil
		}
		if !t.IsZero() {
			*timer = time.AfterFunc(time.Until(t), func() { c.expire(reading, current) })
		}
	}
	if read {
		set(&c.readTimer, &c.readGeneration, true)
	}
	if write {
		set(&c.writeTimer, &c.writeGeneration, false)
	}
	return nil
}
func (c *streamConn) expire(read bool, generation uint64) {
	c.mu.Lock()
	if c.closed || (read && c.readGeneration != generation) || (!read && c.writeGeneration != generation) {
		c.mu.Unlock()
		return
	}
	c.closed = true
	if c.readTimer != nil {
		c.readTimer.Stop()
	}
	if c.writeTimer != nil {
		c.writeTimer.Stop()
	}
	c.mu.Unlock()
	_ = c.stream.Close()
}

type addr string

func (addr) Network() string  { return "tcp" }
func (a addr) String() string { return string(a) }

func target(authority, defaultPort string) (string, int, error) {
	if strings.ContainsAny(authority, " \t\r\n/@") {
		return "", 0, errors.New("invalid proxy authority")
	}
	host, portText, err := net.SplitHostPort(authority)
	if err != nil && defaultPort != "" {
		host, portText, err = net.SplitHostPort(net.JoinHostPort(authority, defaultPort))
	}
	if err != nil || host == "" {
		return "", 0, errors.New("invalid proxy destination")
	}
	port, err := strconv.Atoi(portText)
	if err != nil || port < 1 || port > 65535 {
		return "", 0, errors.New("invalid proxy destination port")
	}
	return host, port, nil
}

func (s *Server) ServeHTTP(w http.ResponseWriter, r *http.Request) {
	if r.Method == http.MethodConnect {
		s.connect(w, r)
		return
	}
	if r.URL == nil || r.URL.Scheme != "http" || r.URL.Host == "" || r.URL.User != nil {
		http.Error(w, "expected an absolute HTTP URL", http.StatusBadRequest)
		return
	}
	if _, _, err := target(r.URL.Host, "80"); err != nil {
		http.Error(w, "invalid destination", http.StatusBadRequest)
		return
	}
	request := r.Clone(r.Context())
	request.RequestURI = ""
	removeHopHeaders(request.Header)
	response, err := s.transport.RoundTrip(request)
	if err != nil {
		http.Error(w, "upstream unavailable", http.StatusBadGateway)
		return
	}
	defer response.Body.Close()
	removeHopHeaders(response.Header)
	for key, values := range response.Header {
		for _, value := range values {
			w.Header().Add(key, value)
		}
	}
	w.WriteHeader(response.StatusCode)
	_, _ = io.Copy(w, response.Body)
}

func removeHopHeaders(h http.Header) {
	for _, value := range h.Values("Connection") {
		for _, name := range strings.Split(value, ",") {
			h.Del(strings.TrimSpace(name))
		}
	}
	for _, key := range []string{"Connection", "Proxy-Connection", "Proxy-Authorization", "Proxy-Authenticate", "Keep-Alive", "TE", "Trailer", "Transfer-Encoding", "Upgrade"} {
		h.Del(key)
	}
}

func (s *Server) connect(w http.ResponseWriter, r *http.Request) {
	host, port, err := target(r.Host, "")
	if err != nil {
		http.Error(w, "invalid destination", http.StatusBadRequest)
		return
	}
	// The client may half-close its sending side before CONNECT completes.
	// net/http can cancel the request context on that EOF, although the
	// client is still waiting for the response. Tie this dial to server
	// shutdown and a deadline instead; never leave it running indefinitely.
	ctx, cancel := context.WithTimeout(s.ctx, 15*time.Second)
	remote, err := s.dialContext(ctx, "tcp", net.JoinHostPort(host, strconv.Itoa(port)))
	cancel()
	if err != nil {
		http.Error(w, "upstream unavailable", http.StatusBadGateway)
		return
	}
	hijacker, ok := w.(http.Hijacker)
	if !ok {
		_ = remote.Close()
		http.Error(w, "hijacking unavailable", http.StatusInternalServerError)
		return
	}
	local, buffered, err := hijacker.Hijack()
	if err != nil {
		_ = remote.Close()
		return
	}
	s.mu.Lock()
	if s.closed {
		s.mu.Unlock()
		_ = local.Close()
		_ = remote.Close()
		return
	}
	s.active[local] = struct{}{}
	s.active[remote] = struct{}{}
	s.mu.Unlock()
	defer func() {
		_ = local.Close()
		_ = remote.Close()
		s.mu.Lock()
		delete(s.active, local)
		delete(s.active, remote)
		s.mu.Unlock()
	}()
	if _, err := buffered.WriteString("HTTP/1.1 200 Connection Established\r\n\r\n"); err != nil {
		return
	}
	if err := buffered.Flush(); err != nil {
		return
	}
	bridge(local, remote, buffered.Reader)
}

func bridge(local net.Conn, remote net.Conn, initial *bufio.Reader) {
	done := make(chan error, 2)
	pump := func(dst net.Conn, src io.Reader) {
		_, err := io.Copy(dst, src)
		if err == nil {
			if half, ok := dst.(interface{ CloseWrite() error }); ok {
				err = half.CloseWrite()
			} else {
				err = errors.New("half-close unavailable")
			}
		}
		done <- err
	}
	go pump(remote, initial)
	go pump(local, remote)
	if err := <-done; err != nil {
		_ = local.Close()
		_ = remote.Close()
	}
	<-done
}
