package tunnel

import (
	"bufio"
	"context"
	"crypto/tls"
	"errors"
	"fmt"
	"io"
	"net"
	"net/http"
	"strconv"
	"strings"
	"sync"
	"time"

	"golang.org/x/net/http2"
)

const agent = "Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:149.0) Gecko/20100101 Firefox/149.0"

type Target struct {
	Host     string
	Port     int
	DialHost string
	Chain    []Hop
}

type Dup interface {
	io.ReadWriteCloser
}

var (
	h2Mu       sync.Mutex
	h2DownTill time.Time
)

func Open(target Target, host string, port int, pass string, timeout time.Duration) (Dup, error) {
	var firstErr error
	if !h2Broken() {
		conn, err := openH2(target, host, port, pass, timeout)
		if err == nil {
			return conn, nil
		}
		firstErr = err
		markH2Broken()
	}
	conn, err := openH1(target, host, port, pass, timeout)
	if err == nil {
		return conn, nil
	}
	if firstErr != nil {
		return nil, firstErr
	}
	return nil, err
}

func h2Broken() bool {
	h2Mu.Lock()
	defer h2Mu.Unlock()
	return time.Now().Before(h2DownTill)
}

func markH2Broken() {
	h2Mu.Lock()
	h2DownTill = time.Now().Add(10 * time.Minute)
	h2Mu.Unlock()
}

func dialTLS(target Target, protocols []string, timeout time.Duration) (*tls.Conn, error) {
	return dialTLSConfigured(target, protocols, timeout, nil)
}

// sessionCache lets resumed handshakes skip the full round-trip dance:
// reconnects after a network blip or an idle reap cost one RTT less.
var sessionCache = tls.NewLRUClientSessionCache(128)

func dialTLSConfigured(target Target, protocols []string, timeout time.Duration, override *tls.Config) (*tls.Conn, error) {
	raw, err := dialChain(target, timeout)
	if err != nil {
		return nil, err
	}
	config := &tls.Config{ClientSessionCache: sessionCache}
	if override != nil {
		config = override.Clone()
	}
	config.ServerName = target.Host
	config.NextProtos = protocols
	config.MinVersion = tls.VersionTLS12
	conn := tls.Client(raw, config)
	conn.SetDeadline(time.Now().Add(timeout))
	if err := conn.Handshake(); err != nil {
		raw.Close()
		return nil, err
	}
	conn.SetDeadline(time.Time{})
	return conn, nil
}

func openH1(target Target, host string, port int, pass string, timeout time.Duration) (Dup, error) {
	conn, err := dialTLS(target, []string{"http/1.1"}, timeout)
	if err != nil {
		return nil, err
	}
	authority := net.JoinHostPort(host, strconv.Itoa(port))
	request := "CONNECT " + authority + " HTTP/1.1\r\n" +
		"Host: " + authority + "\r\n" +
		"Proxy-Authorization: Bearer " + pass + "\r\n" +
		"User-Agent: " + agent + "\r\n\r\n"
	if _, err := conn.Write([]byte(request)); err != nil {
		conn.Close()
		return nil, err
	}
	reader := bufio.NewReader(conn)
	conn.SetDeadline(time.Now().Add(timeout))
	status, err := reader.ReadString('\n')
	if err != nil {
		conn.Close()
		return nil, err
	}
	code := 0
	if fields := strings.Fields(status); len(fields) >= 2 {
		code, _ = strconv.Atoi(fields[1])
	}
	for {
		line, err := reader.ReadString('\n')
		if err != nil {
			conn.Close()
			return nil, err
		}
		if line == "\r\n" || line == "\n" {
			break
		}
	}
	conn.SetDeadline(time.Time{})
	if code != 200 {
		conn.Close()
		return nil, reason(code)
	}
	return &stream{conn: conn, reader: reader}, nil
}

type stream struct {
	conn   *tls.Conn
	reader *bufio.Reader
}

func (s *stream) Read(p []byte) (int, error)  { return s.reader.Read(p) }
func (s *stream) Write(p []byte) (int, error) { return s.conn.Write(p) }
func (s *stream) CloseWrite() error           { return s.conn.CloseWrite() }
func (s *stream) Close() error                { return s.conn.Close() }

func reason(code int) error {
	switch code {
	case 401, 403:
		return errors.New("Mozilla refused the proxy pass; sign in again")
	case 407:
		return errors.New("Mozilla needs a proxy password")
	case 429:
		return errors.New("too many connections; wait a minute")
	case 0:
		return errors.New("the proxy did not answer")
	}
	return fmt.Errorf("the proxy answered %d", code)
}

func openH2(target Target, host string, port int, pass string, timeout time.Duration) (Dup, error) {
	conn, err := dialTLS(target, []string{"h2"}, timeout)
	if err != nil {
		return nil, err
	}
	if conn.ConnectionState().NegotiatedProtocol != "h2" {
		conn.Close()
		return nil, errors.New("the proxy does not support HTTP/2")
	}
	return connectH2(conn, host, port, pass, timeout)
}

func connectH2(conn net.Conn, host string, port int, pass string, timeout time.Duration) (Dup, error) {
	conn.SetDeadline(time.Now().Add(timeout))
	transport := &http2.Transport{DisableCompression: true}
	client, err := transport.NewClientConn(conn)
	if err != nil {
		conn.Close()
		return nil, err
	}
	body := newWriteBuffer(writeBufferSize)
	authority := net.JoinHostPort(host, strconv.Itoa(port))
	request, err := http.NewRequest(http.MethodConnect, "https://"+authority, body)
	if err != nil {
		body.CloseWithError(err)
		client.Close()
		return nil, err
	}
	request.Host = authority
	request.Header.Set("Proxy-Authorization", "Bearer "+pass)
	request.Header.Set("User-Agent", agent)
	ctx, cancel := context.WithCancel(request.Context())
	response, err := client.RoundTrip(request.WithContext(ctx))
	if err != nil {
		cancel()
		body.CloseWithError(err)
		client.Close()
		return nil, err
	}
	if response.StatusCode < 200 || response.StatusCode >= 300 {
		response.Body.Close()
		cancel()
		body.Close()
		client.Close()
		return nil, reason(response.StatusCode)
	}
	conn.SetDeadline(time.Time{})
	return &h2Stream{body: response.Body, writer: body, client: client, cancel: cancel}, nil
}

type h2Stream struct {
	body   io.ReadCloser
	writer *writeBuffer
	client *http2.ClientConn
	cancel context.CancelFunc
	once   sync.Once
}

func (s *h2Stream) Read(p []byte) (int, error) {
	return s.body.Read(p)
}

func (s *h2Stream) Write(p []byte) (int, error) {
	return s.writer.Write(p)
}

func (s *h2Stream) CloseWrite() error { return s.writer.Close() }

func (s *h2Stream) Close() error {
	var err error
	s.once.Do(func() {
		s.writer.Close()
		s.body.Close()
		s.cancel()
		err = s.client.Close()
	})
	return err
}

func Probe(target Target, pass string, timeout time.Duration) (string, error) {
	return probe(func() (Dup, error) { return Open(target, "ifconfig.me", 80, pass, timeout) })
}

func (m *Manager) Probe(pass string, timeout time.Duration) (string, error) {
	return probe(func() (Dup, error) { return m.Open("ifconfig.me", 80, pass, timeout) })
}

func probe(open func() (Dup, error)) (string, error) {
	conn, err := open()
	if err != nil {
		return "", err
	}
	defer conn.Close()
	request := "GET /ip HTTP/1.1\r\nHost: ifconfig.me\r\nUser-Agent: curl/8\r\nAccept: */*\r\nConnection: close\r\n\r\n"
	if _, err := conn.Write([]byte(request)); err != nil {
		return "", err
	}
	data, err := io.ReadAll(io.LimitReader(conn, 4096))
	if err != nil && len(data) == 0 {
		return "", err
	}
	text := string(data)
	if index := strings.Index(text, "\r\n\r\n"); index >= 0 {
		text = text[index+4:]
	}
	for _, line := range strings.Split(text, "\n") {
		line = strings.TrimSpace(line)
		if line != "" {
			if len(line) > 64 {
				line = line[:64]
			}
			return line, nil
		}
	}
	return "", errors.New("the probe returned nothing")
}
