package relay

import (
	"context"
	"crypto/ecdsa"
	"crypto/elliptic"
	"crypto/rand"
	"crypto/sha256"
	"crypto/tls"
	"crypto/x509"
	"crypto/x509/pkix"
	"encoding/hex"
	"fmt"
	"math/big"
	"net"
	"net/http"
	"net/http/httptest"
	"sync/atomic"
	"testing"
	"time"
)

func TestRelayPolicy(t *testing.T) {
	if !Allowed("/v1/guardian/pass", http.MethodGet) || !Allowed("/v1/guardian/activate", http.MethodPost) || !Allowed("/v1/mozilla/servers", http.MethodGet) {
		t.Fatal("required route rejected")
	}
	for _, item := range [][2]string{{"/v1/guardian/pass", http.MethodPost}, {"/v1/proxy", http.MethodGet}, {"/api/v1/fpn/token", http.MethodGet}} {
		if Allowed(item[0], item[1]) {
			t.Fatalf("accepted %s %s", item[1], item[0])
		}
	}
	for _, status := range []int{408, 425, 429, 451, 500, 502, 503} {
		if !Retry(status) {
			t.Fatalf("retry policy rejected %d", status)
		}
	}
	for _, status := range []int{200, 400, 401, 403, 404} {
		if Retry(status) {
			t.Fatalf("retry policy accepted %d", status)
		}
	}
}

func TestTLSPinAndCAFailureCloseBeforeAuthorizationIsSent(t *testing.T) {
	const host = "gozar-guardian-a.gozarfox7.workers.dev"
	key, err := ecdsa.GenerateKey(elliptic.P256(), rand.Reader)
	if err != nil {
		t.Fatal(err)
	}
	template := &x509.Certificate{
		SerialNumber: big.NewInt(1),
		Subject: pkix.Name{CommonName: host},
		DNSNames: []string{host},
		NotBefore: time.Now().Add(-time.Hour),
		NotAfter: time.Now().Add(time.Hour),
		KeyUsage: x509.KeyUsageDigitalSignature | x509.KeyUsageCertSign,
		ExtKeyUsage: []x509.ExtKeyUsage{x509.ExtKeyUsageServerAuth},
		IsCA: true,
		BasicConstraintsValid: true,
	}
	der, err := x509.CreateCertificate(rand.Reader, template, template, &key.PublicKey, key)
	if err != nil {
		t.Fatal(err)
	}
	certificate, err := x509.ParseCertificate(der)
	if err != nil {
		t.Fatal(err)
	}
	roots := x509.NewCertPool()
	roots.AddCert(certificate)
	var requests atomic.Int32
	server := httptest.NewUnstartedServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		requests.Add(1)
		if r.Header.Get("Authorization") != "Bearer synthetic" {
			t.Error("authorization was not preserved")
		}
		fmt.Fprint(w, `{"token":"synthetic"}`)
	}))
	server.TLS = &tls.Config{Certificates: []tls.Certificate{{Certificate: [][]byte{der}, PrivateKey: key}}, MinVersion: tls.VersionTLS12}
	server.StartTLS()
	defer server.Close()
	encoded, err := x509.MarshalPKIXPublicKey(&key.PublicKey)
	if err != nil {
		t.Fatal(err)
	}
	digest := sha256.Sum256(encoded)
	pin := hex.EncodeToString(digest[:])
	newClient := func(pins []string, trusted *x509.CertPool) *http.Client {
		transport, err := pinnedTransport(pins)
		if err != nil {
			t.Fatal(err)
		}
		transport.TLSClientConfig.RootCAs = trusted
		transport.TLSClientConfig.ServerName = host
		transport.DialContext = func(ctx context.Context, _, _ string) (net.Conn, error) {
			var dialer net.Dialer
			return dialer.DialContext(ctx, "tcp", server.Listener.Addr().String())
		}
		return &http.Client{Transport: transport, Timeout: 5 * time.Second}
	}
	request := func(client *http.Client) error {
		req, err := http.NewRequest(http.MethodGet, "https://"+host+"/v1/guardian/pass", nil)
		if err != nil {
			t.Fatal(err)
		}
		req.Header.Set("Authorization", "Bearer synthetic")
		response, err := client.Do(req)
		if err != nil {
			return err
		}
		response.Body.Close()
		return nil
	}
	if err := request(newClient([]string{pin}, roots)); err != nil || requests.Load() != 1 {
		t.Fatalf("valid pin rejected: %v", err)
	}
	if err := request(newClient([]string{hex.EncodeToString(make([]byte, 32))}, roots)); err == nil || requests.Load() != 1 {
		t.Fatal("wrong pin reached the HTTP handler")
	}
	if err := request(newClient([]string{pin}, x509.NewCertPool())); err == nil || requests.Load() != 1 {
		t.Fatal("untrusted chain reached the HTTP handler")
	}
}
