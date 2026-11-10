package fxa

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
)

func TestControlPolicyLimitsDestinations(t *testing.T) {
	allowed := []struct{ method, url string }{
		{"GET", "https://vpn.mozilla.org/api/v1/fpn/token"},
		{"POST", "https://oauth.accounts.firefox.com/v1/token"},
	}
	for _, item := range allowed {
		if !allowedControlRequest(item.method, item.url) {
			t.Fatalf("rejected %s", item.url)
		}
	}
	denied := []struct{ method, url string }{
		{"GET", "http://vpn.mozilla.org/api/v1/fpn/token"},
		{"GET", "https://vpn.mozilla.org.evil.invalid/api/v1/fpn/token"},
		{"GET", "https://vpn.mozilla.org:444/api/v1/fpn/token"},
		{"GET", "https://user@vpn.mozilla.org/api/v1/fpn/token"},
		{"GET", "https://vpn.mozilla.org/api/v1/fpn/token?next=bad"},
		{"GET", "https://vpn.mozilla.org/api/v1/fpn/token?"},
		{"POST", "https://vpn.mozilla.org/api/v1/fpn/activate"},
		{"POST", "https://vpn.mozilla.org/api/v1/fpn/token"},
		{"GET", "https://oauth.accounts.firefox.com/v1/token"},
		{"POST", "https://oauth.accounts.firefox.com/v1/account/create"},
	}
	for _, item := range denied {
		if allowedControlRequest(item.method, item.url) {
			t.Fatalf("accepted %s", item.url)
		}
	}
}

func TestSimulated451IsNotRetriedDirectOrUsedToActivate(t *testing.T) {
	old := client
	defer func() { client = old }()
	var outside atomic.Int32
	client = &http.Client{Transport: fakeTransport(func(*http.Request) (*http.Response, error) {
		outside.Add(1)
		return nil, errors.New("unexpected outside request")
	})}
	var controlCalls atomic.Int32
	control := &http.Client{Transport: fakeTransport(func(r *http.Request) (*http.Response, error) {
		controlCalls.Add(1)
		if r.Method != "GET" || r.URL.Path != "/api/v1/fpn/token" {
			t.Error("unexpected mutation")
		}
		return testResponse(451, `{}`), nil
	})}
	_, err := PassTokenThrough("synthetic-access", control)
	var apiErr *Error
	if !errors.As(err, &apiErr) || apiErr.Status != 451 || controlCalls.Load() != 1 || outside.Load() != 0 {
		t.Fatalf("did not preserve 451 without fallback: %v", err)
	}
}

func testControlServer(t *testing.T, handler http.Handler, names ...string) (*httptest.Server, *x509.CertPool) {
	t.Helper()
	if len(names) == 0 {
		names = []string{"vpn.mozilla.org", "oauth.accounts.firefox.com"}
	}
	key, err := ecdsa.GenerateKey(elliptic.P256(), rand.Reader)
	if err != nil {
		t.Fatal(err)
	}
	cert := &x509.Certificate{SerialNumber: big.NewInt(1), Subject: pkix.Name{CommonName: "synthetic control test"},
		DNSNames:  names,
		NotBefore: time.Now().Add(-time.Hour), NotAfter: time.Now().Add(time.Hour),
		KeyUsage:    x509.KeyUsageDigitalSignature | x509.KeyUsageCertSign,
		ExtKeyUsage: []x509.ExtKeyUsage{x509.ExtKeyUsageServerAuth}, IsCA: true, BasicConstraintsValid: true}
	der, err := x509.CreateCertificate(rand.Reader, cert, cert, &key.PublicKey, key)
	if err != nil {
		t.Fatal(err)
	}
	parsed, err := x509.ParseCertificate(der)
	if err != nil {
		t.Fatal(err)
	}
	roots := x509.NewCertPool()
	roots.AddCert(parsed)
	server := httptest.NewUnstartedServer(handler)
	server.TLS = &tls.Config{Certificates: []tls.Certificate{{Certificate: [][]byte{der}, PrivateKey: key}}}
	server.StartTLS()
	return server, roots
}

func TestSimulatedTunnelRenewalVerifiesTLSAndNeverFollowsRedirect(t *testing.T) {
	var requests atomic.Int32
	server, roots := testControlServer(t, http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		requests.Add(1)
		switch r.URL.Path {
		case "/api/v1/fpn/token":
			if r.Header.Get("Authorization") == "Bearer redirect-test" {
				w.Header().Set("Location", "https://untrusted.invalid/")
				w.WriteHeader(http.StatusFound)
				return
			}
			if r.Header.Get("Authorization") != "Bearer synthetic-access" {
				t.Error("bad synthetic bearer")
			}
			fmt.Fprint(w, `{"token":"fresh-synthetic-pass"}`)
		case "/v1/token":
			fmt.Fprint(w, `{"access_token":"fresh-access","refresh_token":"fresh-refresh","expires_in":3600}`)
		default:
			w.Header().Set("Location", "https://untrusted.invalid/")
			w.WriteHeader(http.StatusFound)
		}
	}))
	defer server.Close()
	var dials atomic.Int32
	newClient := func() (*http.Client, func()) {
		return NewControlClient(func(_ context.Context, host string, port int) (io.ReadWriteCloser, error) {
			if port != 443 || (host != "vpn.mozilla.org" && host != "oauth.accounts.firefox.com") {
				return nil, errors.New("unexpected target")
			}
			dials.Add(1)
			return net.Dial("tcp", server.Listener.Addr().String())
		})
	}
	control, closeIdle := newClient()
	defer closeIdle()
	control.Transport.(*http.Transport).TLSClientConfig = &tls.Config{RootCAs: roots, MinVersion: tls.VersionTLS12}
	pass, err := PassTokenThrough("synthetic-access", control)
	if err != nil || pass.Token != "fresh-synthetic-pass" {
		t.Fatalf("pass: %v", err)
	}
	tokens, err := RefreshThrough("synthetic-refresh", control)
	if err != nil || tokens.Refresh != "fresh-refresh" {
		t.Fatalf("refresh: %v", err)
	}
	if requests.Load() != 2 || dials.Load() != 2 {
		t.Fatalf("requests=%d dials=%d", requests.Load(), dials.Load())
	}
	if _, err := PassTokenThrough("redirect-test", control); err == nil || requests.Load() != 3 || dials.Load() != 3 {
		t.Fatal("redirect was followed or accepted")
	}

	// The same fake certificate is rejected by default roots. No HTTP bearer
	// reaches the server before TLS certificate validation succeeds.
	untrusted, closeUntrusted := newClient()
	defer closeUntrusted()
	if _, err := PassTokenThrough("secret-must-not-arrive", untrusted); err == nil {
		t.Fatal("accepted an untrusted test certificate")
	}
	if requests.Load() != 3 {
		t.Fatal("HTTP request reached untrusted server")
	}
}

func TestTrustedWrongHostnameIsRejectedBeforeBearer(t *testing.T) {
	var requests atomic.Int32
	server, roots := testControlServer(t, http.HandlerFunc(func(http.ResponseWriter, *http.Request) {
		requests.Add(1)
	}), "wrong-host.invalid")
	defer server.Close()
	control, closeIdle := NewControlClient(func(_ context.Context, _ string, _ int) (io.ReadWriteCloser, error) {
		return net.Dial("tcp", server.Listener.Addr().String())
	})
	defer closeIdle()
	control.Transport.(*http.Transport).TLSClientConfig = &tls.Config{RootCAs: roots}
	if _, err := PassTokenThrough("must-not-arrive", control); err == nil || requests.Load() != 0 {
		t.Fatal("wrong hostname accepted or HTTP request reached server")
	}
}

func TestSimulatedSignupUsesOAuthAccountOrigin(t *testing.T) {
	old := client
	defer func() { client = old }()
	var calls int
	client = &http.Client{Transport: fakeTransport(func(r *http.Request) (*http.Response, error) {
		calls++
		if r.URL.Host != "oauth.accounts.firefox.com" || r.URL.Path != "/v1/account/create" || r.Method != "POST" {
			t.Error("wrong signup origin")
		}
		return testResponse(200, `{"uid":"fake","sessionToken":"fake","verified":false}`), nil
	})}
	session, err := Register("person@example.invalid", "synthetic-password")
	if err != nil || !session.NeedsOTP || calls != 1 {
		t.Fatalf("synthetic signup: %v", err)
	}
}
