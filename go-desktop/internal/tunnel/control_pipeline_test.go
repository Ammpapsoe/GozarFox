package tunnel

import (
	"context"
	"crypto/ecdsa"
	"crypto/elliptic"
	"crypto/rand"
	"crypto/tls"
	"crypto/x509"
	"crypto/x509/pkix"
	"errors"
	"fmt"
	"io"
	"math/big"
	"net"
	"net/http"
	"net/http/httptest"
	"sync/atomic"
	"testing"
	"time"

	"github.com/bardia049b-stack/Gozar1/go-desktop/internal/fxa"
	"golang.org/x/net/http2"
)

// This exercises the actual desktop Manager, HTTP/2 CONNECT stream, and
// nested TLS. All endpoints, credentials and certificates are synthetic.
func TestControlRenewalThroughAuthenticatedH2Relay(t *testing.T) {
	originKey, err := ecdsa.GenerateKey(elliptic.P256(), rand.Reader)
	if err != nil {
		t.Fatal(err)
	}
	originCert := &x509.Certificate{SerialNumber: big.NewInt(42), Subject: pkix.Name{CommonName: "synthetic origin"},
		DNSNames:  []string{"vpn.mozilla.org", "oauth.accounts.firefox.com"},
		NotBefore: time.Now().Add(-time.Hour), NotAfter: time.Now().Add(time.Hour),
		IsCA: true, BasicConstraintsValid: true, KeyUsage: x509.KeyUsageDigitalSignature | x509.KeyUsageCertSign,
		ExtKeyUsage: []x509.ExtKeyUsage{x509.ExtKeyUsageServerAuth}}
	der, err := x509.CreateCertificate(rand.Reader, originCert, originCert, &originKey.PublicKey, originKey)
	if err != nil {
		t.Fatal(err)
	}
	parsed, err := x509.ParseCertificate(der)
	if err != nil {
		t.Fatal(err)
	}
	originRoots := x509.NewCertPool()
	originRoots.AddCert(parsed)
	var originRequests atomic.Int32
	origin := httptest.NewUnstartedServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		originRequests.Add(1)
		switch {
		case r.Method == "GET" && r.Host == "vpn.mozilla.org" && r.URL.Path == "/api/v1/fpn/token":
			if r.Header.Get("X-Simulated-Direct") == "yes" || r.Header.Get("Authorization") == "Bearer simulated-block" {
				w.WriteHeader(http.StatusUnavailableForLegalReasons)
				return
			}
			if r.Header.Get("Authorization") != "Bearer synthetic-access" {
				t.Error("bad synthetic access token")
			}
			fmt.Fprint(w, `{"token":"new-synthetic-pass"}`)
		case r.Method == "POST" && r.Host == "oauth.accounts.firefox.com" && r.URL.Path == "/v1/token":
			fmt.Fprint(w, `{"access_token":"new-access","refresh_token":"new-refresh","expires_in":3600}`)
		default:
			w.WriteHeader(http.StatusBadRequest)
		}
	}))
	origin.TLS = &tls.Config{Certificates: []tls.Certificate{{Certificate: [][]byte{der}, PrivateKey: originKey}}}
	origin.StartTLS()
	defer origin.Close()
	// Model a direct control path that returns 451 before the same account
	// successfully renews through the authenticated H2 relay below.
	directTransport := &http.Transport{
		TLSClientConfig: &tls.Config{RootCAs: originRoots},
		DialContext: func(ctx context.Context, _, _ string) (net.Conn, error) {
			var dialer net.Dialer
			return dialer.DialContext(ctx, "tcp", origin.Listener.Addr().String())
		},
	}
	defer directTransport.CloseIdleConnections()
	direct := &http.Client{Transport: directTransport, Timeout: 4 * time.Second}
	request, err := http.NewRequest(http.MethodGet, "https://vpn.mozilla.org/api/v1/fpn/token", nil)
	if err != nil {
		t.Fatal(err)
	}
	request.Header.Set("X-Simulated-Direct", "yes")
	response, err := direct.Do(request)
	if err != nil {
		t.Fatal(err)
	}
	_ = response.Body.Close()
	if response.StatusCode != 451 {
		t.Fatal("direct block simulation did not return 451")
	}

	relayCert, relayRoots := localCertificate(t)
	listener, err := net.Listen("tcp", "127.0.0.1:0")
	if err != nil {
		t.Fatal(err)
	}
	defer listener.Close()
	var relayRequests atomic.Int32
	handler := http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if r.Method != "CONNECT" || r.Header.Get("Proxy-Authorization") != "Bearer old-synthetic-pass" ||
			(r.Host != "vpn.mozilla.org:443" && r.Host != "oauth.accounts.firefox.com:443") {
			w.WriteHeader(http.StatusForbidden)
			return
		}
		relayRequests.Add(1)
		upstream, err := net.DialTimeout("tcp", origin.Listener.Addr().String(), 2*time.Second)
		if err != nil {
			w.WriteHeader(http.StatusBadGateway)
			return
		}
		defer upstream.Close()
		w.WriteHeader(http.StatusOK)
		w.(http.Flusher).Flush()
		go func() {
			_, _ = io.Copy(upstream, r.Body)
			if tcp, ok := upstream.(*net.TCPConn); ok {
				_ = tcp.CloseWrite()
			}
		}()
		buffer := make([]byte, 8192)
		for {
			n, readErr := upstream.Read(buffer)
			if n > 0 {
				if _, err := w.Write(buffer[:n]); err != nil {
					return
				}
				w.(http.Flusher).Flush()
			}
			if readErr != nil {
				return
			}
		}
	})
	go func() {
		for {
			raw, err := listener.Accept()
			if err != nil {
				return
			}
			go func() {
				conn := tls.Server(raw, &tls.Config{Certificates: []tls.Certificate{relayCert}, NextProtos: []string{"h2"}})
				defer conn.Close()
				if conn.Handshake() == nil {
					(&http2.Server{}).ServeConn(conn, &http2.ServeConnOpts{Handler: handler})
				}
			}()
		}
	}()
	manager := NewManager(Target{Host: "localhost", DialHost: "127.0.0.1", Port: listener.Addr().(*net.TCPAddr).Port})
	manager.testTLS = &tls.Config{RootCAs: relayRoots}
	defer manager.Close()
	control, closeIdle := fxa.NewControlClient(func(_ context.Context, host string, port int) (io.ReadWriteCloser, error) {
		return manager.Open(host, port, "old-synthetic-pass", 4*time.Second)
	})
	defer closeIdle()
	control.Transport.(*http.Transport).TLSClientConfig = &tls.Config{RootCAs: originRoots}
	pass, err := fxa.PassTokenThrough("synthetic-access", control)
	if err != nil || pass.Token != "new-synthetic-pass" {
		t.Fatalf("pass renewal failed: %v", err)
	}
	tokens, err := fxa.RefreshThrough("synthetic-refresh", control)
	if err != nil || tokens.Refresh != "new-refresh" {
		t.Fatalf("OAuth renewal failed: %v", err)
	}
	_, err = fxa.PassTokenThrough("simulated-block", control)
	var apiErr *fxa.Error
	if !errors.As(err, &apiErr) || apiErr.Status != 451 {
		t.Fatalf("lost in-tunnel 451: %v", err)
	}
	if relayRequests.Load() != 3 || originRequests.Load() != 4 {
		t.Fatal("unexpected retry or destination")
	}
	_ = manager.Close()
	if _, err := fxa.PassTokenThrough("synthetic-access", control); err == nil {
		t.Fatal("renewal succeeded after tunnel closed")
	}
	if originRequests.Load() != 4 {
		t.Fatal("request escaped a closed tunnel")
	}
}
