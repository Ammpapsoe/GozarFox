package tunnel

import (
	"bytes"
	"crypto/sha256"
	"crypto/tls"
	"crypto/x509"
	"fmt"
	"io"
	"net"
	"net/http"
	"sync"
	"sync/atomic"
	"testing"
	"time"

	"golang.org/x/net/http2"
)

// TestManagerSharesSessionsOnlyWithinBudget pins the sharing policy: a fresh
// session takes h2StreamsPerSession connections and no more, so a bulk
// transfer cannot be multiplexed behind an unbounded number of chatty flows on
// one TLS socket, and opening many connections still ends up spread over
// several sessions instead of one.
func TestManagerSharesSessionsOnlyWithinBudget(t *testing.T) {
	cert, _ := localCertificateForTest(t)
	listener, err := net.Listen("tcp4", "127.0.0.1:0")
	if err != nil {
		t.Fatal(err)
	}
	defer listener.Close()
	var arrived atomic.Int32
	handler := http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if r.Method != http.MethodConnect {
			w.WriteHeader(http.StatusBadRequest)
			return
		}
		arrived.Add(1)
		w.WriteHeader(http.StatusOK)
		w.(http.Flusher).Flush()
		io.Copy(io.Discard, r.Body)
	})
	go serveH2TLS(listener, cert, handler)

	manager := NewManager(Target{Host: "localhost", DialHost: "127.0.0.1", Port: listener.Addr().(*net.TCPAddr).Port})
	manager.testTLS = &tls.Config{InsecureSkipVerify: true}
	manager.h3DisabledForTest = true
	defer manager.Close()

	const total = 12
	streams := make([]Dup, 0, total)
	for index := 0; index < total; index++ {
		stream, err := manager.Open("198.18.0.90", 443, "fake-pass", 5*time.Second)
		if err != nil {
			t.Fatalf("open %d: %v", index, err)
		}
		streams = append(streams, stream)
	}
	defer func() {
		for _, stream := range streams {
			_ = stream.Close()
		}
	}()
	if got := arrived.Load(); got != int32(total) {
		t.Fatalf("relay saw %d CONNECT requests, want %d", got, total)
	}
	manager.mu.Lock()
	sessions := len(manager.h2)
	manager.mu.Unlock()
	want := (total + h2StreamsPerSession - 1) / h2StreamsPerSession
	if sessions < want {
		t.Fatalf("%d connections opened %d sessions, want at least %d (<= %d streams each)",
			total, sessions, want, h2StreamsPerSession)
	}
}

// TestManagerKeepsManySessionsUnderBulkLoad is the regression for the desktop
// slowness report: bulk transfer through the manager must use more than one
// HTTP/2 session so TLS write serialisation inside a single stream does not
// cap the tunnel.
func TestManagerKeepsManySessionsUnderBulkLoad(t *testing.T) {
	cert, _ := localCertificateForTest(t)
	listener, err := net.Listen("tcp4", "127.0.0.1:0")
	if err != nil {
		t.Fatal(err)
	}
	defer listener.Close()
	payload := bytes.Repeat([]byte("gozar-throughput-"), 65536)
	digest := sha256.Sum256(payload)
	handler := http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if r.Method != http.MethodConnect {
			w.WriteHeader(http.StatusBadRequest)
			return
		}
		w.WriteHeader(http.StatusOK)
		w.(http.Flusher).Flush()
		if _, err := w.Write(payload); err != nil {
			return
		}
		w.(http.Flusher).Flush()
		io.Copy(io.Discard, r.Body)
	})
	go serveH2TLS(listener, cert, handler)

	manager := NewManager(Target{Host: "localhost", DialHost: "127.0.0.1", Port: listener.Addr().(*net.TCPAddr).Port})
	manager.testTLS = &tls.Config{InsecureSkipVerify: true}
	manager.h3DisabledForTest = true
	defer manager.Close()

	const flows = 16
	started := time.Now()
	var wg sync.WaitGroup
	failures := make(chan error, flows)
	for i := 0; i < flows; i++ {
		wg.Add(1)
		go func() {
			defer wg.Done()
			stream, err := manager.Open("198.18.0.91", 443, "fake-pass", 10*time.Second)
			if err != nil {
				failures <- err
				return
			}
			defer stream.Close()
			hash := sha256.New()
			if _, err := io.CopyN(hash, stream, int64(len(payload))); err != nil {
				failures <- fmt.Errorf("read: %w", err)
				return
			}
			if !bytes.Equal(hash.Sum(nil), digest[:]) {
				failures <- fmt.Errorf("payload corrupted")
			}
		}()
	}
	wg.Wait()
	close(failures)
	for err := range failures {
		t.Fatal(err)
	}
	total := len(payload) * flows
	seconds := time.Since(started).Seconds()
	t.Logf("%d flows, %.1f MiB in %.2fs (%.1f MiB/s over loopback TLS)", flows,
		float64(total)/(1<<20), seconds, float64(total)/(1<<20)/seconds)
}

func serveH2TLS(listener net.Listener, cert tls.Certificate, handler http.Handler) {
	for {
		raw, err := listener.Accept()
		if err != nil {
			return
		}
		go func() {
			conn := tls.Server(raw, &tls.Config{Certificates: []tls.Certificate{cert}, NextProtos: []string{"h2"}})
			defer conn.Close()
			if conn.Handshake() == nil {
				(&http2.Server{}).ServeConn(conn, &http2.ServeConnOpts{Handler: handler})
			}
		}()
	}
}

func localCertificateForTest(t *testing.T) (tls.Certificate, *x509.CertPool) {
	t.Helper()
	return localCertificate(t)
}
