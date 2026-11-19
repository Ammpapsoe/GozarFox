package dnsproxy

import (
	"bytes"
	"context"
	"crypto/tls"
	"errors"
	"fmt"
	"io"
	"mime"
	"net"
	"net/http"
	"strings"
	"sync"
	"time"

	"golang.org/x/net/dns/dnsmessage"
)

const Address = "1.1.1.1"
const maxMessageSize = 65535

var servers = []string{
	"https://dns.google/dns-query",
	"https://mozilla.cloudflare-dns.com/dns-query",
}

type Resolver struct {
	Dialer    func(host string, port int) (io.ReadWriteCloser, error)
	ctx       context.Context
	cancel    context.CancelFunc
	transport *http.Transport
	client    *http.Client
	servers   []string
	timeout   time.Duration
	cache     *responseCache
}

func New(dial func(host string, port int) (io.ReadWriteCloser, error)) *Resolver {
	ctx, cancel := context.WithCancel(context.Background())
	r := &Resolver{
		Dialer:  dial,
		ctx:     ctx,
		cancel:  cancel,
		servers: append([]string(nil), servers...),
		timeout: 8 * time.Second,
		cache:   newResponseCache(),
	}
	r.transport = &http.Transport{
		DialContext:           r.dial,
		TLSClientConfig:       &tls.Config{MinVersion: tls.VersionTLS12},
		TLSHandshakeTimeout:   8 * time.Second,
		ResponseHeaderTimeout: 8 * time.Second,
		IdleConnTimeout:       30 * time.Second,
		MaxIdleConns:          16,
		MaxIdleConnsPerHost:   8,
		MaxConnsPerHost:       8,
		DisableCompression:    true,
	}
	r.client = &http.Client{
		Transport: r.transport,
		CheckRedirect: func(*http.Request, []*http.Request) error {
			return http.ErrUseLastResponse
		},
	}
	return r
}

func (r *Resolver) Resolve(query []byte) ([]byte, error) {
	question, err := readQuery(query)
	if err != nil {
		return nil, err
	}
	if err := r.ctx.Err(); err != nil {
		return nil, err
	}
	if answer, ok := r.cache.get(query); ok {
		return answer, nil
	}
	var last error
	for _, server := range r.servers {
		answer, err := r.exchange(server, query)
		if err == nil {
			err = checkAnswer(question, answer)
		}
		if err == nil {
			r.cache.put(query, answer)
			return answer, nil
		}
		last = err
		if r.ctx.Err() != nil {
			return nil, r.ctx.Err()
		}
	}
	if last == nil {
		last = errors.New("no DNS-over-HTTPS server")
	}
	return nil, last
}

func (r *Resolver) exchange(server string, query []byte) ([]byte, error) {
	ctx, cancel := context.WithTimeout(r.ctx, r.timeout)
	defer cancel()
	request, err := http.NewRequestWithContext(ctx, http.MethodPost, server, bytes.NewReader(query))
	if err != nil {
		return nil, err
	}
	request.Header.Set("Content-Type", "application/dns-message")
	request.Header.Set("Accept", "application/dns-message")
	response, err := r.client.Do(request)
	if err != nil {
		return nil, err
	}
	defer response.Body.Close()
	if response.StatusCode != http.StatusOK {
		return nil, fmt.Errorf("DNS-over-HTTPS returned %d", response.StatusCode)
	}
	kind, _, err := mime.ParseMediaType(response.Header.Get("Content-Type"))
	if err != nil || !strings.EqualFold(kind, "application/dns-message") {
		return nil, errors.New("DNS-over-HTTPS returned an unexpected content type")
	}
	if response.ContentLength > maxMessageSize {
		return nil, errors.New("DNS-over-HTTPS answer is too large")
	}
	answer, err := io.ReadAll(io.LimitReader(response.Body, maxMessageSize+1))
	if err != nil {
		return nil, err
	}
	if len(answer) > maxMessageSize {
		return nil, errors.New("DNS-over-HTTPS answer is too large")
	}
	return answer, nil
}

func (r *Resolver) dial(ctx context.Context, network, address string) (net.Conn, error) {
	host, port, err := net.SplitHostPort(address)
	if err != nil || port != "443" || (network != "tcp" && network != "tcp4" && network != "tcp6") || r.Dialer == nil {
		return nil, errors.New("invalid DNS-over-HTTPS connection")
	}
	dialCtx, cancel := context.WithTimeout(ctx, r.timeout)
	stop := context.AfterFunc(r.ctx, cancel)
	defer stop()
	defer cancel()
	type result struct {
		stream io.ReadWriteCloser
		err    error
	}
	ready := make(chan result)
	go func() {
		stream, err := r.Dialer(host, 443)
		select {
		case ready <- result{stream: stream, err: err}:
		case <-dialCtx.Done():
			if stream != nil {
				stream.Close()
			}
		}
	}()
	select {
	case <-dialCtx.Done():
		return nil, dialCtx.Err()
	case out := <-ready:
		if out.err != nil {
			if out.stream != nil {
				out.stream.Close()
			}
			return nil, out.err
		}
		if out.stream == nil {
			return nil, errors.New("the tunnel returned an empty connection")
		}
		return &streamConn{stream: out.stream, address: address}, nil
	}
}

func (r *Resolver) Close() {
	r.cancel()
	r.cache.clear()
	r.transport.CloseIdleConnections()
}

func readQuery(query []byte) (dnsmessage.Message, error) {
	var message dnsmessage.Message
	if len(query) < 12 || len(query) > maxMessageSize {
		return message, errors.New("invalid DNS query length")
	}
	if err := message.Unpack(query); err != nil || message.Response || len(message.Questions) == 0 {
		return message, errors.New("invalid DNS query")
	}
	return message, nil
}

func checkAnswer(query dnsmessage.Message, answer []byte) error {
	var message dnsmessage.Message
	if len(answer) < 12 || len(answer) > maxMessageSize {
		return errors.New("invalid DNS answer length")
	}
	if err := message.Unpack(answer); err != nil || !message.Response || message.ID != query.ID ||
		message.OpCode != query.OpCode || len(message.Questions) != len(query.Questions) {
		return errors.New("DNS answer does not match the query")
	}
	for i, q := range query.Questions {
		a := message.Questions[i]
		if !strings.EqualFold(q.Name.String(), a.Name.String()) || q.Type != a.Type || q.Class != a.Class {
			return errors.New("DNS answer does not match the question")
		}
	}
	return nil
}

func LimitUDP(query, answer []byte) []byte {
	message, err := readQuery(query)
	if err != nil {
		return nil
	}
	limit := 512
	for _, record := range message.Additionals {
		if record.Header.Type == dnsmessage.TypeOPT {
			if size := int(record.Header.Class); size > limit {
				limit = size
			}
			break
		}
	}
	if limit > 1232 {
		limit = 1232
	}
	if len(answer) <= limit {
		return answer
	}
	var response dnsmessage.Message
	if err := response.Unpack(answer); err != nil || !response.Response {
		return nil
	}
	response.Truncated = true
	response.Answers = nil
	response.Authorities = nil
	response.Additionals = nil
	wire, err := response.Pack()
	if err != nil {
		return nil
	}
	if len(wire) > limit {
		response.Questions = nil
		wire, err = response.Pack()
		if err != nil {
			return nil
		}
	}
	return wire
}

func Failure(query []byte) []byte {
	question, err := readQuery(query)
	if err != nil {
		return nil
	}
	answer := dnsmessage.Message{
		Header: dnsmessage.Header{
			ID:                 question.ID,
			Response:           true,
			OpCode:             question.OpCode,
			RecursionDesired:   question.RecursionDesired,
			RecursionAvailable: true,
			RCode:              dnsmessage.RCodeServerFailure,
		},
		Questions: question.Questions,
	}
	wire, _ := answer.Pack()
	return wire
}

type streamConn struct {
	stream   io.ReadWriteCloser
	address  string
	mu       sync.Mutex
	closed   bool
	readEnd  *time.Timer
	writeEnd *time.Timer
	readSeq  uint64
	writeSeq uint64
}

func (c *streamConn) Read(p []byte) (int, error)  { return c.stream.Read(p) }
func (c *streamConn) Write(p []byte) (int, error) { return c.stream.Write(p) }

func (c *streamConn) Close() error {
	c.mu.Lock()
	if c.closed {
		c.mu.Unlock()
		return nil
	}
	c.closed = true
	if c.readEnd != nil {
		c.readEnd.Stop()
	}
	if c.writeEnd != nil {
		c.writeEnd.Stop()
	}
	c.mu.Unlock()
	return c.stream.Close()
}

func (c *streamConn) LocalAddr() net.Addr  { return streamAddr("tunnel") }
func (c *streamConn) RemoteAddr() net.Addr { return streamAddr(c.address) }

func (c *streamConn) SetDeadline(t time.Time) error {
	return c.setDeadline(t, true, true)
}

func (c *streamConn) SetReadDeadline(t time.Time) error {
	return c.setDeadline(t, true, false)
}

func (c *streamConn) SetWriteDeadline(t time.Time) error {
	return c.setDeadline(t, false, true)
}

func (c *streamConn) setDeadline(t time.Time, read, write bool) error {
	c.mu.Lock()
	defer c.mu.Unlock()
	if c.closed {
		return net.ErrClosed
	}
	set := func(timer **time.Timer, seq *uint64, reading bool) {
		*seq = *seq + 1
		current := *seq
		if *timer != nil {
			(*timer).Stop()
			*timer = nil
		}
		if !t.IsZero() {
			*timer = time.AfterFunc(time.Until(t), func() { c.expire(reading, current) })
		}
	}
	if read {
		set(&c.readEnd, &c.readSeq, true)
	}
	if write {
		set(&c.writeEnd, &c.writeSeq, false)
	}
	return nil
}

func (c *streamConn) expire(read bool, seq uint64) {
	c.mu.Lock()
	if c.closed || (read && c.readSeq != seq) || (!read && c.writeSeq != seq) {
		c.mu.Unlock()
		return
	}
	c.closed = true
	if c.readEnd != nil {
		c.readEnd.Stop()
	}
	if c.writeEnd != nil {
		c.writeEnd.Stop()
	}
	c.mu.Unlock()
	_ = c.stream.Close()
}

type streamAddr string

func (streamAddr) Network() string  { return "tcp" }
func (a streamAddr) String() string { return string(a) }
