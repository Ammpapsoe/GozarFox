package tunnel

import (
	"bytes"
	"context"
	"crypto/tls"
	"crypto/x509"
	"errors"
	"fmt"
	"io"
	"net"
	"net/http"
	"os"
	"os/exec"
	"runtime"
	"strings"
	"sync"
	"testing"
	"time"

	"github.com/bardia049b-stack/Gozar1/go-desktop/internal/masque"
	"golang.org/x/net/http2"
)

func TestUDPPathEncodingAndValidation(t *testing.T) {
	valid := []struct {
		host string
		port int
		want string
	}{
		{"Example.COM", 53, "/.well-known/masque/udp/example.com/53/"},
		{"192.0.2.1", 65535, "/.well-known/masque/udp/192.0.2.1/65535/"},
		{"2001:db8::1", 443, "/.well-known/masque/udp/2001%3Adb8%3A%3A1/443/"},
		{"[2001:db8::1]", 443, "/.well-known/masque/udp/2001%3Adb8%3A%3A1/443/"},
	}
	for _, tc := range valid {
		got, err := udpPath(tc.host, tc.port)
		if err != nil || got != tc.want {
			t.Errorf("udpPath(%q, %d) = %q, %v; want %q", tc.host, tc.port, got, err, tc.want)
		}
	}
	for _, host := range []string{"", "a/b", "a?b", "-bad.test", "a..b", "bad\r\nheader", "fe80::1%eth0", "[2001:db8::1", "é.example"} {
		if path, err := udpPath(host, 53); err == nil {
			t.Errorf("accepted invalid host %q as %q", host, path)
		}
	}
	for _, port := range []int{0, -1, 65536} {
		if _, err := udpPath("example.com", port); err == nil {
			t.Errorf("accepted port %d", port)
		}
	}
}

func TestUDPResponseMustExplicitlyConfirmCapsules(t *testing.T) {
	response := func(status int, headers http.Header, length int64) *http.Response {
		return &http.Response{StatusCode: status, Header: headers, ContentLength: length, Body: io.NopCloser(strings.NewReader(""))}
	}
	good := http.Header{"Capsule-Protocol": {"?1"}}
	if err := validUDPResponse(response(200, good, -1)); err != nil {
		t.Fatal(err)
	}
	cases := []*http.Response{
		nil, response(403, good, -1), response(204, good, -1), response(205, good, -1), response(206, good, -1),
		response(200, http.Header{}, -1), response(200, http.Header{"Capsule-Protocol": {"?0"}}, -1),
		response(200, http.Header{"Capsule-Protocol": {"?1", "?1"}}, -1),
		response(200, http.Header{"Capsule-Protocol": {"?1, ?1"}}, -1),
		response(200, http.Header{"Capsule-Protocol": {"?1;foo"}}, -1),
		response(200, http.Header{"Capsule-Protocol": {"?1"}, "Content-Length": {"0"}}, -1),
		response(200, http.Header{"Capsule-Protocol": {"?1"}, "Content-Type": {"text/plain"}}, -1),
		response(200, http.Header{"Capsule-Protocol": {"?1"}, "Content-Encoding": {"gzip"}}, -1),
		response(200, http.Header{"Capsule-Protocol": {"?1"}}, 0),
	}
	for index, value := range cases {
		if err := validUDPResponse(value); err == nil {
			t.Errorf("response case %d accepted", index)
		}
	}
}

type roundTripFunc func(*http.Request) (*http.Response, error)

func (f roundTripFunc) RoundTrip(r *http.Request) (*http.Response, error) { return f(r) }

func TestUDPRequestUsesProxyAuthorityAndAuthenticatedTargetPath(t *testing.T) {
	var observed *http.Request
	client := roundTripFunc(func(r *http.Request) (*http.Response, error) {
		observed = r
		return &http.Response{StatusCode: 200, Header: http.Header{"Capsule-Protocol": {"?1"}}, ContentLength: -1, Body: io.NopCloser(strings.NewReader(""))}, nil
	})
	released := 0
	stream, err := openUDPStream(client, "proxy.example:443", "/.well-known/masque/udp/2001%3Adb8%3A%3A1/53/", "fake-pass", time.Second, func() { released++ })
	if err != nil {
		t.Fatal(err)
	}
	if observed.Method != "CONNECT" || observed.Host != "proxy.example:443" || observed.URL.EscapedPath() != "/.well-known/masque/udp/2001%3Adb8%3A%3A1/53/" || observed.Header.Get(":protocol") != "connect-udp" || observed.Header.Get("Capsule-Protocol") != "?1" || observed.Header.Get("Proxy-Authorization") != "Bearer fake-pass" {
		t.Fatal("extended CONNECT request is missing the expected authority, path or authentication")
	}
	_ = stream.Close()
	_ = stream.Close()
	if released != 1 || !errors.Is(observed.Context().Err(), context.Canceled) {
		t.Fatalf("stream did not release and cancel exactly once: released=%d context=%v", released, observed.Context().Err())
	}
}

// x/net/http2 only enables extended CONNECT on its server at process startup
// with GODEBUG=http2xconnect=1. Run a synthetic server in a subprocess so test
// results do not depend on the parent process's global environment.
func udpTestChild(t *testing.T, enabled bool) bool {
	t.Helper()
	mode := "disabled"
	setting := "http2xconnect=0"
	if enabled {
		mode, setting = "enabled", "http2xconnect=1"
	}
	if os.Getenv("GOZAR_UDP_TEST_CHILD") == mode {
		return true
	}
	var env []string
	for _, item := range os.Environ() {
		key, _, _ := strings.Cut(item, "=")
		if !strings.EqualFold(key, "GODEBUG") && !strings.EqualFold(key, "GOZAR_UDP_TEST_CHILD") {
			env = append(env, item)
		}
	}
	var options []string
	for _, part := range strings.Split(os.Getenv("GODEBUG"), ",") {
		if part != "" && !strings.HasPrefix(part, "http2xconnect=") {
			options = append(options, part)
		}
	}
	options = append(options, setting)
	env = append(env, "GODEBUG="+strings.Join(options, ","), "GOZAR_UDP_TEST_CHILD="+mode)
	cmd := exec.Command(os.Args[0], "-test.run=^"+t.Name()+"$", "-test.timeout=120s")
	cmd.Env = env
	output, err := cmd.CombinedOutput()
	if err != nil {
		t.Fatalf("synthetic HTTP/2 subprocess: %v\n%s", err, output)
	}
	return false
}

func udpSyntheticServer(t *testing.T, handler http.Handler) (int, *x509.CertPool) {
	t.Helper()
	cert, roots := localCertificate(t)
	listener, err := net.Listen("tcp", "127.0.0.1:0")
	if err != nil {
		t.Fatal(err)
	}
	var wg sync.WaitGroup
	wg.Add(1)
	go func() {
		defer wg.Done()
		for {
			raw, err := listener.Accept()
			if err != nil {
				return
			}
			wg.Add(1)
			go func() {
				defer wg.Done()
				conn := tls.Server(raw, &tls.Config{Certificates: []tls.Certificate{cert}, NextProtos: []string{"h2"}, MinVersion: tls.VersionTLS12})
				defer conn.Close()
				if conn.Handshake() == nil {
					(&http2.Server{}).ServeConn(conn, &http2.ServeConnOpts{Handler: handler})
				}
			}()
		}
	}()
	t.Cleanup(func() { _ = listener.Close(); wg.Wait() })
	return listener.Addr().(*net.TCPAddr).Port, roots
}

func newUDPTestManager(port int, roots *x509.CertPool) *Manager {
	manager := NewManager(Target{Host: "localhost", DialHost: "127.0.0.1", Port: port})
	manager.testTLS = &tls.Config{RootCAs: roots}
	return manager
}

func TestUDPHTTP2SyntheticRoundTrip(t *testing.T) {
	if !udpTestChild(t, true) {
		return
	}
	var mu sync.Mutex
	var peers []string
	handler := http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if r.ProtoMajor != 2 || r.Method != "CONNECT" || r.Header.Get(":protocol") != "connect-udp" || r.Header.Get("Capsule-Protocol") != "?1" || !strings.HasPrefix(r.Host, "localhost:") {
			w.WriteHeader(400)
			return
		}
		if r.Header.Get("Proxy-Authorization") != "Bearer fake-pass" {
			w.WriteHeader(403)
			return
		}
		if strings.Contains(r.RequestURI, "missing.example") {
			w.WriteHeader(200)
			w.(http.Flusher).Flush()
			<-r.Context().Done()
			return
		}
		if r.RequestURI != "/.well-known/masque/udp/2001%3Adb8%3A%3A1/53/" {
			w.WriteHeader(400)
			return
		}
		mu.Lock()
		peers = append(peers, r.RemoteAddr)
		mu.Unlock()
		w.Header().Set("Capsule-Protocol", "?1")
		w.WriteHeader(200)
		w.(http.Flusher).Flush()
		for {
			payload, err := masque.ReadDatagram(r.Body)
			if err != nil {
				return
			}
			// Unknown capsules and nonzero contexts must be skipped.
			wire, err := masque.EncodeDatagram(payload)
			if err != nil {
				return
			}
			prefix := []byte{0x3f, 0x02, 0xde, 0xad, 0x00, 0x02, 0x01, 'x'}
			if _, err := w.Write(append(prefix, wire...)); err != nil {
				return
			}
			w.(http.Flusher).Flush()
		}
	})
	port, roots := udpSyntheticServer(t, handler)
	manager := newUDPTestManager(port, roots)
	defer manager.Close()
	payloads := [][]byte{nil, []byte("hello udp"), bytes.Repeat([]byte{0x6b}, masque.MaxUDPPayload)}
	for attempt := 0; attempt < 2; attempt++ {
		stream, err := manager.OpenUDP("2001:db8::1", 53, "fake-pass", 5*time.Second)
		if err != nil {
			t.Fatal(err)
		}
		for _, payload := range payloads {
			if err := stream.WriteDatagram(payload); err != nil {
				t.Fatal(err)
			}
			got, err := stream.ReadDatagram()
			if err != nil || !bytes.Equal(got, payload) {
				t.Fatalf("datagram round trip: got %d bytes, err %v; want %d bytes", len(got), err, len(payload))
			}
		}
		_ = stream.Close()
	}
	mu.Lock()
	if len(peers) != 2 || peers[0] != peers[1] {
		mu.Unlock()
		t.Fatal("extended CONNECT streams did not reuse the authenticated HTTP/2 session")
	}
	mu.Unlock()
	if stream, err := manager.OpenUDP("missing.example", 53, "fake-pass", 5*time.Second); err == nil {
		_ = stream.Close()
		t.Fatal("accepted missing Capsule-Protocol response")
	}
	if stream, err := manager.OpenUDP("2001:db8::1", 53, "wrong-pass", 5*time.Second); err == nil {
		_ = stream.Close()
		t.Fatal("accepted an unauthorized response")
	}
	untrusted := NewManager(Target{Host: "localhost", DialHost: "127.0.0.1", Port: port})
	defer untrusted.Close()
	if stream, err := untrusted.OpenUDP("example.com", 53, "fake-pass", 5*time.Second); err == nil {
		_ = stream.Close()
		t.Fatal("accepted an untrusted TLS certificate")
	}
}

func TestUDPHTTP2RejectsPeerWithoutExtendedConnect(t *testing.T) {
	if !udpTestChild(t, false) {
		return
	}
	requests := make(chan struct{}, 1)
	port, roots := udpSyntheticServer(t, http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		requests <- struct{}{}
		w.WriteHeader(200)
	}))
	manager := newUDPTestManager(port, roots)
	defer manager.Close()
	if stream, err := manager.OpenUDP("example.com", 53, "fake-pass", 5*time.Second); err == nil {
		_ = stream.Close()
		t.Fatal("accepted peer without SETTINGS_ENABLE_CONNECT_PROTOCOL")
	}
	select {
	case <-requests:
		t.Fatal("sent extended CONNECT to a peer that did not advertise support")
	default:
	}
}

func TestUDPOpenCloseFiftyRaces(t *testing.T) {
	if !udpTestChild(t, true) {
		return
	}
	type gate struct{ entered, resume chan struct{} }
	gates := make(chan gate, 1)
	port, roots := udpSyntheticServer(t, http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		g := <-gates
		close(g.entered)
		select {
		case <-g.resume:
		case <-r.Context().Done():
			return
		}
		w.Header().Set("Capsule-Protocol", "?1")
		w.WriteHeader(200)
		w.(http.Flusher).Flush()
	}))
	for iteration := 0; iteration < 50; iteration++ {
		g := gate{entered: make(chan struct{}), resume: make(chan struct{})}
		gates <- g
		manager := newUDPTestManager(port, roots)
		type outcome struct {
			stream *UDPStream
			err    error
		}
		done := make(chan outcome, 1)
		go func() {
			stream, err := manager.OpenUDP("example.com", 53, "fake-pass", 5*time.Second)
			done <- outcome{stream, err}
		}()
		select {
		case <-g.entered:
		case <-time.After(10 * time.Second):
			manager.Close()
			close(g.resume)
			t.Fatalf("iteration %d: request never reached the proxy", iteration)
		}
		if err := manager.Close(); err != nil {
			t.Fatal(err)
		}
		close(g.resume)
		select {
		case result := <-done:
			if result.stream != nil {
				_ = result.stream.Close()
				t.Fatalf("iteration %d: UDP stream escaped a closed manager", iteration)
			}
			if result.err == nil {
				t.Fatalf("iteration %d: closed manager reported success", iteration)
			}
		case <-time.After(10 * time.Second):
			t.Fatalf("iteration %d: blocked open did not stop", iteration)
		}
		manager.mu.Lock()
		left := len(manager.h2) + len(manager.streams)
		manager.mu.Unlock()
		if left != 0 {
			t.Fatalf("iteration %d: %d sessions or streams survived close", iteration, left)
		}
	}
}

type yieldingStream struct {
	mu     sync.Mutex
	wire   []byte
	closed bool
}

func (s *yieldingStream) Read([]byte) (int, error) { return 0, io.EOF }
func (s *yieldingStream) Write(p []byte) (int, error) {
	for i, b := range p {
		s.mu.Lock()
		if s.closed {
			s.mu.Unlock()
			return i, io.ErrClosedPipe
		}
		s.wire = append(s.wire, b)
		s.mu.Unlock()
		runtime.Gosched()
	}
	return len(p), nil
}
func (s *yieldingStream) Close() error { s.mu.Lock(); s.closed = true; s.mu.Unlock(); return nil }

type blockedPipe struct {
	reader  *io.PipeReader
	writer  *io.PipeWriter
	entered chan struct{}
	once    sync.Once
}

func (p *blockedPipe) signal() {
	select {
	case p.entered <- struct{}{}:
	default:
	}
}
func (p *blockedPipe) Read(buf []byte) (int, error) {
	p.signal()
	return p.reader.Read(buf)
}
func (p *blockedPipe) Write(buf []byte) (int, error) {
	p.signal()
	return p.writer.Write(buf)
}
func (p *blockedPipe) Close() error {
	p.once.Do(func() { _ = p.reader.Close(); _ = p.writer.Close() })
	return nil
}

func TestUDPStreamFiftyWriteAndCloseRaces(t *testing.T) {
	for iteration := 0; iteration < 50; iteration++ {
		underlying := &yieldingStream{}
		stream := &UDPStream{stream: underlying}
		start := make(chan struct{})
		done := make(chan error, 2)
		for _, value := range []byte{'a', 'b'} {
			go func(b byte) {
				<-start
				done <- stream.WriteDatagram(bytes.Repeat([]byte{b}, 64))
			}(value)
		}
		close(start)
		for n := 0; n < 2; n++ {
			if err := <-done; err != nil {
				t.Fatalf("iteration %d: %v", iteration, err)
			}
		}
		underlying.mu.Lock()
		wire := append([]byte(nil), underlying.wire...)
		underlying.mu.Unlock()
		reader := bytes.NewReader(wire)
		seen := map[byte]bool{}
		for n := 0; n < 2; n++ {
			payload, err := masque.ReadDatagram(reader)
			if err != nil || len(payload) != 64 || !bytes.Equal(payload, bytes.Repeat(payload[:1], 64)) {
				t.Fatalf("iteration %d: interleaved datagram, error %v", iteration, err)
			}
			seen[payload[0]] = true
		}
		if !seen['a'] || !seen['b'] || reader.Len() != 0 {
			t.Fatalf("iteration %d: incomplete datagrams", iteration)
		}
		for _, read := range []bool{true, false} {
			r, w := io.Pipe()
			entered := make(chan struct{}, 1)
			blocked := &UDPStream{stream: &blockedPipe{reader: r, writer: w, entered: entered}}
			finished := make(chan error, 1)
			go func() {
				if read {
					_, err := blocked.ReadDatagram()
					finished <- err
				} else {
					finished <- blocked.WriteDatagram([]byte("x"))
				}
			}()
			select {
			case <-entered:
			case <-time.After(2 * time.Second):
				t.Fatalf("iteration %d: I/O did not reach the pipe", iteration)
			}
			_ = blocked.Close()
			select {
			case err := <-finished:
				if err == nil {
					t.Fatal("closed stream reported successful blocked I/O")
				}
			case <-time.After(2 * time.Second):
				t.Fatalf("iteration %d: close did not unblock I/O", iteration)
			}
		}
		_ = stream.Close()
	}
}

func Example_udpPath() {
	path, _ := udpPath("2001:db8::1", 53)
	fmt.Println(path)
	// Output: /.well-known/masque/udp/2001%3Adb8%3A%3A1/53/
}
