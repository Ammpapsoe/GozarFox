package tunnel

import (
	"crypto/tls"
	"crypto/x509"
	"net"
	"net/http"
	"sync"
	"testing"
	"time"

	"golang.org/x/net/http2"
)

func TestLatencyMeasuresLocalListener(t *testing.T) {
	listener, err := net.Listen("tcp4", "127.0.0.1:0")
	if err != nil {
		t.Fatal(err)
	}
	defer listener.Close()
	go func() {
		for {
			conn, err := listener.Accept()
			if err != nil {
				return
			}
			conn.Close()
		}
	}()
	port := listener.Addr().(*net.TCPAddr).Port
	average, jitter, err := Latency("127.0.0.1", port, 3, time.Second)
	if err != nil {
		t.Fatal(err)
	}
	// A successful loopback dial can take less than the monotonic clock's
	// resolution (notably on Windows), in which case zero is valid.
	if average < 0 || jitter < 0 {
		t.Fatalf("average=%s jitter=%s", average, jitter)
	}
}

func verifyProxy(t *testing.T, handler http.Handler) (Target, *x509.CertPool) {
	t.Helper()
	cert, roots := localCertificate(t)
	listener, err := net.Listen("tcp4", "127.0.0.1:0")
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() { listener.Close() })
	go func() {
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
	}()
	return Target{Host: "localhost", Port: listener.Addr().(*net.TCPAddr).Port}, roots
}

func TestVerifyFallsBackToTheSecondTarget(t *testing.T) {
	var mu sync.Mutex
	hosts := make([]string, 0, 2)
	target, roots := verifyProxy(t, http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		mu.Lock()
		hosts = append(hosts, r.Host)
		mu.Unlock()
		if r.Host == "www.mozilla.org:443" {
			w.WriteHeader(http.StatusInternalServerError)
			return
		}
		w.WriteHeader(http.StatusOK)
		w.(http.Flusher).Flush()
	}))
	manager := NewManager(target)
	manager.testTLS = &tls.Config{RootCAs: roots}
	defer manager.Close()
	if err := manager.Verify("pass", 3*time.Second); err != nil {
		t.Fatal(err)
	}
	mu.Lock()
	defer mu.Unlock()
	if len(hosts) != 2 || hosts[0] != "www.mozilla.org:443" || hosts[1] != "www.cloudflare.com:443" {
		t.Fatalf("unexpected verification targets: %v", hosts)
	}
}

func TestVerifyStopsImmediatelyOnProxyRefusal(t *testing.T) {
	var mu sync.Mutex
	hits := 0
	target, roots := verifyProxy(t, http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		mu.Lock()
		hits++
		mu.Unlock()
		w.WriteHeader(http.StatusProxyAuthRequired)
	}))
	manager := NewManager(target)
	manager.testTLS = &tls.Config{RootCAs: roots}
	defer manager.Close()
	err := manager.Verify("pass", 3*time.Second)
	if err == nil || !IsRefusal(err) {
		t.Fatalf("expected a proxy refusal, got %v", err)
	}
	mu.Lock()
	defer mu.Unlock()
	if hits != 1 {
		t.Fatalf("a refused pass must not probe the second target, hits=%d", hits)
	}
}

func TestVerifyFailsWhenBothTargetsFail(t *testing.T) {
	target, roots := verifyProxy(t, http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		w.WriteHeader(http.StatusInternalServerError)
	}))
	manager := NewManager(target)
	manager.testTLS = &tls.Config{RootCAs: roots}
	defer manager.Close()
	if err := manager.Verify("pass", 3*time.Second); err == nil {
		t.Fatal("verification should fail when both targets fail")
	}
}
