package dnsproxy

import (
	"bytes"
	"context"
	"crypto/tls"
	"crypto/x509"
	"encoding/binary"
	"errors"
	"io"
	"log"
	"net"
	"net/http"
	"net/http/httptest"
	"strings"
	"sync"
	"sync/atomic"
	"testing"
	"time"

	"golang.org/x/net/dns/dnsmessage"
)

func query(t *testing.T, id uint16, name string, kind dnsmessage.Type) []byte {
	t.Helper()
	domain, err := dnsmessage.NewName(name)
	if err != nil {
		t.Fatal(err)
	}
	message := dnsmessage.Message{
		Header:    dnsmessage.Header{ID: id, RecursionDesired: true},
		Questions: []dnsmessage.Question{{Name: domain, Type: kind, Class: dnsmessage.ClassINET}},
	}
	wire, err := message.Pack()
	if err != nil {
		t.Fatal(err)
	}
	return wire
}

func answerFor(t *testing.T, wire []byte) []byte {
	t.Helper()
	var q dnsmessage.Message
	if err := q.Unpack(wire); err != nil {
		t.Fatal(err)
	}
	message := dnsmessage.Message{
		Header:    dnsmessage.Header{ID: q.ID, Response: true, RecursionDesired: q.RecursionDesired, RecursionAvailable: true},
		Questions: q.Questions,
		Answers: []dnsmessage.Resource{{
			Header: dnsmessage.ResourceHeader{Name: q.Questions[0].Name, Type: dnsmessage.TypeA, Class: dnsmessage.ClassINET, TTL: 27},
			Body:   &dnsmessage.AResource{A: [4]byte{192, 0, 2, 42}},
		}},
	}
	response, err := message.Pack()
	if err != nil {
		t.Fatal(err)
	}
	return response
}

func setup(t *testing.T, handler http.Handler) (*Resolver, *httptest.Server, *[]string) {
	t.Helper()
	server := httptest.NewUnstartedServer(handler)
	server.Config.ErrorLog = log.New(io.Discard, "", 0)
	server.StartTLS()
	var mu sync.Mutex
	calls := &[]string{}
	r := New(func(host string, port int) (io.ReadWriteCloser, error) {
		mu.Lock()
		*calls = append(*calls, net.JoinHostPort(host, stringPort(port)))
		mu.Unlock()
		return net.DialTimeout("tcp", server.Listener.Addr().String(), time.Second)
	})
	roots := x509.NewCertPool()
	roots.AddCert(server.Certificate())
	r.transport.TLSClientConfig = &tls.Config{RootCAs: roots, MinVersion: tls.VersionTLS12}
	r.servers = []string{"https://example.com/dns-query", "https://fallback.example.com/dns-query"}
	t.Cleanup(func() {
		r.Close()
		server.Close()
	})
	return r, server, calls
}

func stringPort(port int) string {
	if port == 443 {
		return "443"
	}
	return "unexpected"
}

func TestResolveUsesTLSOverTheProvidedDialer(t *testing.T) {
	var requests atomic.Int32
	r, _, calls := setup(t, http.HandlerFunc(func(w http.ResponseWriter, req *http.Request) {
		if req.TLS == nil || req.Method != http.MethodPost || req.URL.Path != "/dns-query" ||
			req.Header.Get("Content-Type") != "application/dns-message" || req.Header.Get("Accept") != "application/dns-message" {
			t.Errorf("unexpected DNS-over-HTTPS request")
		}
		body, _ := io.ReadAll(req.Body)
		requests.Add(1)
		w.Header().Set("Content-Type", "application/dns-message")
		_, _ = w.Write(answerFor(t, body))
	}))
	for index, id := range []uint16{0x1234, 0x5678} {
		name := []string{"example.org.", "other.example.org."}[index]
		wire := query(t, id, name, dnsmessage.TypeA)
		got, err := r.Resolve(wire)
		if err != nil {
			t.Fatal(err)
		}
		q, _ := readQuery(wire)
		if err := checkAnswer(q, got); err != nil {
			t.Fatal(err)
		}
	}
	if requests.Load() != 2 {
		t.Fatalf("wanted two uncached queries, got %d", requests.Load())
	}
	if len(*calls) == 0 {
		t.Fatal("the provided tunnel dialer was not called")
	}
	for _, call := range *calls {
		if call != "example.com:443" {
			t.Fatalf("unexpected connection through dialer: %s", call)
		}
	}
}

func TestFallbackOnInvalidDoHResponses(t *testing.T) {
	for _, kind := range []string{"status", "type", "id", "question", "oversize", "redirect", "truncated"} {
		t.Run(kind, func(t *testing.T) {
			var primary, fallback atomic.Int32
			r, _, _ := setup(t, http.HandlerFunc(func(w http.ResponseWriter, req *http.Request) {
				body, _ := io.ReadAll(req.Body)
				if req.Host == "fallback.example.com" {
					fallback.Add(1)
					w.Header().Set("Content-Type", "application/dns-message; charset=binary")
					_, _ = w.Write(answerFor(t, body))
					return
				}
				primary.Add(1)
				switch kind {
				case "status":
					w.WriteHeader(http.StatusServiceUnavailable)
				case "type":
					w.Header().Set("Content-Type", "text/html")
					_, _ = w.Write(answerFor(t, body))
				case "id":
					response := answerFor(t, body)
					response[0] ^= 1
					w.Header().Set("Content-Type", "application/dns-message")
					_, _ = w.Write(response)
				case "question":
					other := query(t, binary.BigEndian.Uint16(body), "other.example.", dnsmessage.TypeA)
					w.Header().Set("Content-Type", "application/dns-message")
					_, _ = w.Write(answerFor(t, other))
				case "oversize":
					w.Header().Set("Content-Type", "application/dns-message")
					_, _ = w.Write(bytes.Repeat([]byte{1}, maxMessageSize+1))
				case "redirect":
					http.Redirect(w, req, "https://unexpected.example.net/", http.StatusFound)
				case "truncated":
					w.Header().Set("Content-Type", "application/dns-message")
					_, _ = w.Write([]byte{1, 2, 3})
				}
			}))
			wire := query(t, 100, "example.org.", dnsmessage.TypeA)
			got, err := r.Resolve(wire)
			if err != nil {
				t.Fatal(err)
			}
			if !bytes.Equal(got, answerFor(t, wire)) || primary.Load() != 1 || fallback.Load() != 1 {
				t.Fatalf("fallback failed: primary=%d fallback=%d", primary.Load(), fallback.Load())
			}
		})
	}
}

func TestTLSCertificateIsVerified(t *testing.T) {
	r, _, _ := setup(t, http.HandlerFunc(func(w http.ResponseWriter, req *http.Request) {
		t.Error("request reached a server with an untrusted certificate")
	}))
	r.transport.TLSClientConfig = &tls.Config{RootCAs: x509.NewCertPool(), MinVersion: tls.VersionTLS12}
	if _, err := r.Resolve(query(t, 1, "example.org.", dnsmessage.TypeA)); err == nil {
		t.Fatal("untrusted certificate was accepted")
	}
}

func TestTimeoutFallsBack(t *testing.T) {
	r, _, _ := setup(t, http.HandlerFunc(func(w http.ResponseWriter, req *http.Request) {
		if req.Host == "example.com" {
			_, _ = io.Copy(io.Discard, req.Body)
			select {
			case <-req.Context().Done():
			case <-time.After(2 * time.Second):
			}
			return
		}
		body, _ := io.ReadAll(req.Body)
		w.Header().Set("Content-Type", "application/dns-message")
		_, _ = w.Write(answerFor(t, body))
	}))
	r.timeout = 300 * time.Millisecond
	started := time.Now()
	wire := query(t, 9, "example.org.", dnsmessage.TypeA)
	if _, err := r.Resolve(wire); err != nil {
		t.Fatal(err)
	}
	if time.Since(started) > 3*time.Second {
		t.Fatal("DNS timeout did not bound the request")
	}
}

func TestCancelClosesLateTunnelStream(t *testing.T) {
	release := make(chan struct{})
	peer := make(chan net.Conn, 1)
	r := New(func(string, int) (io.ReadWriteCloser, error) {
		<-release
		local, remote := net.Pipe()
		peer <- remote
		return local, nil
	})
	defer r.Close()
	ctx, cancel := context.WithCancel(context.Background())
	cancel()
	if _, err := r.dial(ctx, "tcp", "example.com:443"); !errors.Is(err, context.Canceled) {
		t.Fatalf("expected cancellation, got %v", err)
	}
	close(release)
	remote := <-peer
	defer remote.Close()
	_ = remote.SetReadDeadline(time.Now().Add(time.Second))
	if _, err := remote.Read(make([]byte, 1)); !errors.Is(err, io.EOF) {
		t.Fatalf("late stream was not closed: %v", err)
	}
}

func TestRejectsInvalidQueriesAndAnswers(t *testing.T) {
	valid := query(t, 1, "example.org.", dnsmessage.TypeA)
	if _, err := readQuery([]byte{1, 2}); err == nil {
		t.Fatal("accepted a short query")
	}
	response := answerFor(t, valid)
	if _, err := readQuery(response); err == nil {
		t.Fatal("accepted a response as a query")
	}
	question, _ := readQuery(valid)
	if err := checkAnswer(question, valid); err == nil {
		t.Fatal("accepted a query as a response")
	}
	response[0] ^= 1
	if err := checkAnswer(question, response); err == nil {
		t.Fatal("accepted a mismatched transaction")
	}
	if Failure([]byte{1}) != nil {
		t.Fatal("invalid query produced a failure response")
	}
	var failure dnsmessage.Message
	if err := failure.Unpack(Failure(valid)); err != nil || !failure.Response || failure.ID != 1 || failure.RCode != dnsmessage.RCodeServerFailure {
		t.Fatal("invalid SERVFAIL response")
	}
}

func TestDialRejectsOtherPorts(t *testing.T) {
	r := New(func(host string, port int) (io.ReadWriteCloser, error) {
		t.Errorf("dialed %s:%d", host, port)
		return nil, errors.New("unexpected")
	})
	defer r.Close()
	if _, err := r.dial(context.Background(), "tcp", "example.com:53"); err == nil || !strings.Contains(err.Error(), "invalid") {
		t.Fatalf("unexpected result: %v", err)
	}
}

func TestStreamDeadlineCanBeCleared(t *testing.T) {
	local, remote := net.Pipe()
	conn := &streamConn{stream: local}
	defer conn.Close()
	defer remote.Close()
	if err := conn.SetReadDeadline(time.Now().Add(30 * time.Millisecond)); err != nil {
		t.Fatal(err)
	}
	if err := conn.SetReadDeadline(time.Time{}); err != nil {
		t.Fatal(err)
	}
	time.Sleep(60 * time.Millisecond)
	go func() { _, _ = remote.Write([]byte{42}) }()
	buf := make([]byte, 1)
	if _, err := conn.Read(buf); err != nil || buf[0] != 42 {
		t.Fatalf("cleared deadline closed the connection: %v", err)
	}
	if err := conn.SetReadDeadline(time.Now().Add(30 * time.Millisecond)); err != nil {
		t.Fatal(err)
	}
	if _, err := conn.Read(buf); err == nil {
		t.Fatal("deadline did not close the blocked stream")
	}
}

func TestLimitUDPUsesEDNSAndSignalsTCPFallback(t *testing.T) {
	base := query(t, 64, "example.org.", dnsmessage.TypeTXT)
	var request dnsmessage.Message
	if err := request.Unpack(base); err != nil {
		t.Fatal(err)
	}
	answer := dnsmessage.Message{
		Header:    dnsmessage.Header{ID: request.ID, Response: true, RecursionAvailable: true},
		Questions: request.Questions,
	}
	for i := 0; i < 4; i++ {
		answer.Answers = append(answer.Answers, dnsmessage.Resource{
			Header: dnsmessage.ResourceHeader{Name: request.Questions[0].Name, Type: dnsmessage.TypeTXT, Class: dnsmessage.ClassINET, TTL: 30},
			Body:   &dnsmessage.TXTResource{TXT: []string{strings.Repeat("a", 190)}},
		})
	}
	large, err := answer.Pack()
	if err != nil || len(large) <= 512 || len(large) >= 1232 {
		t.Fatalf("test answer length %d, err %v", len(large), err)
	}
	short := LimitUDP(base, large)
	var truncated dnsmessage.Message
	if err := truncated.Unpack(short); err != nil || len(short) > 512 || !truncated.Truncated || truncated.ID != 64 || len(truncated.Questions) != 1 {
		t.Fatalf("bad truncated response: length %d, err %v", len(short), err)
	}
	root, _ := dnsmessage.NewName(".")
	request.Additionals = []dnsmessage.Resource{{
		Header: dnsmessage.ResourceHeader{Name: root, Type: dnsmessage.TypeOPT, Class: dnsmessage.Class(1232)},
		Body:   &dnsmessage.OPTResource{},
	}}
	edns, err := request.Pack()
	if err != nil {
		t.Fatal(err)
	}
	if got := LimitUDP(edns, large); !bytes.Equal(got, large) {
		t.Fatal("EDNS-capable query lost a response within its size limit")
	}
	request.Additionals[0].Header.Class = dnsmessage.Class(600)
	edns, err = request.Pack()
	if err != nil {
		t.Fatal(err)
	}
	short = LimitUDP(edns, large)
	if err := truncated.Unpack(short); err != nil || len(short) > 600 || !truncated.Truncated {
		t.Fatalf("EDNS limit ignored: length %d, err %v", len(short), err)
	}
	request.Additionals[0].Header.Class = dnsmessage.Class(4096)
	edns, err = request.Pack()
	if err != nil {
		t.Fatal(err)
	}
	answer.Answers = append(answer.Answers, answer.Answers...)
	large, err = answer.Pack()
	if err != nil || len(large) <= 1232 {
		t.Fatalf("test answer length %d, err %v", len(large), err)
	}
	short = LimitUDP(edns, large)
	if err := truncated.Unpack(short); err != nil || len(short) > 1232 || !truncated.Truncated {
		t.Fatalf("safe UDP cap ignored: length %d, err %v", len(short), err)
	}
}
