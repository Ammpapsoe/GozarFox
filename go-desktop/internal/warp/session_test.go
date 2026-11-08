package warp

import (
	"context"
	"crypto/ecdsa"
	"crypto/elliptic"
	"crypto/rand"
	"crypto/tls"
	"crypto/x509"
	"encoding/base64"
	"encoding/json"
	"io"
	"math/big"
	"net"
	"net/http"
	"net/http/httptest"
	"net/netip"
	"strings"
	"sync/atomic"
	"testing"
	"time"
)

type roundTripFunc func(*http.Request) (*http.Response, error)

func (f roundTripFunc) RoundTrip(r *http.Request) (*http.Response, error) { return f(r) }
func response(status int, body string) *http.Response {
	return &http.Response{StatusCode: status, Header: make(http.Header), Body: io.NopCloser(strings.NewReader(body))}
}

func testIdentity(t *testing.T, server *ecdsa.PrivateKey) *Identity {
	t.Helper()
	key, err := ecdsa.GenerateKey(elliptic.P256(), rand.Reader)
	if err != nil {
		t.Fatal(err)
	}
	der, err := x509.MarshalPKCS8PrivateKey(key)
	if err != nil {
		t.Fatal(err)
	}
	pub, err := x509.MarshalPKIXPublicKey(&server.PublicKey)
	if err != nil {
		t.Fatal(err)
	}
	return &Identity{ID: "fake-device", Token: "fake-token", PrivateKey: base64.StdEncoding.EncodeToString(der),
		ServerKey: base64.StdEncoding.EncodeToString(pub), IPv4: "172.16.0.2"}
}

func TestRegistrationRequiresConsentAndPersistsBeforeEnrollment(t *testing.T) {
	server, _ := ecdsa.GenerateKey(elliptic.P256(), rand.Reader)
	pub, _ := x509.MarshalPKIXPublicKey(&server.PublicKey)
	var calls atomic.Int32
	saves := 0
	client := &http.Client{Transport: roundTripFunc(func(req *http.Request) (*http.Response, error) {
		calls.Add(1)
		if req.URL.Host != "api.cloudflareclient.com" {
			t.Fatal("wrong registration destination")
		}
		switch req.Method {
		case "POST":
			var body map[string]any
			if err := json.NewDecoder(req.Body).Decode(&body); err != nil {
				t.Fatal(err)
			}
			if body["tos"] == "" || body["tunnel_type"] != "wireguard" {
				t.Fatal("registration body missing")
			}
			return response(200, `{"id":"fake-id","token":"fake-token"}`), nil
		case "PATCH":
			if saves != 1 || req.Header.Get("Authorization") != "Bearer fake-token" {
				t.Fatal("device was not securely persisted before enrollment")
			}
			body := `{"config":{"peers":[{"public_key":"` + base64.StdEncoding.EncodeToString(pub) + `"}],"interface":{"addresses":{"v4":"172.16.0.2"}}}}`
			return response(200, body), nil
		default:
			t.Fatal("unexpected registration request")
			return nil, nil
		}
	})}
	persist := func(i *Identity) error { saves++; return nil }
	if _, err := Register(context.Background(), client, false, persist); err == nil || calls.Load() != 0 {
		t.Fatal("registration did not require explicit consent")
	}
	identity, err := Register(context.Background(), client, true, persist)
	if err != nil || identity == nil || !identity.Ready() || calls.Load() != 2 || saves != 2 {
		t.Fatalf("registration: %v", err)
	}
}

func newH2Echo(t *testing.T) (*httptest.Server, *Identity) {
	t.Helper()
	serverKey, err := ecdsa.GenerateKey(elliptic.P256(), rand.Reader)
	if err != nil {
		t.Fatal(err)
	}
	template := &x509.Certificate{SerialNumber: big.NewInt(1), NotBefore: time.Now().Add(-time.Hour), NotAfter: time.Now().Add(time.Hour), KeyUsage: x509.KeyUsageDigitalSignature}
	cert, err := x509.CreateCertificate(rand.Reader, template, template, &serverKey.PublicKey, serverKey)
	if err != nil {
		t.Fatal(err)
	}
	handler := http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if r.Method != http.MethodConnect || r.Host != connectAuthority || r.Header.Get("cf-connect-proto") != "cf-connect-ip" {
			w.WriteHeader(http.StatusBadRequest)
			return
		}
		w.WriteHeader(http.StatusOK)
		w.(http.Flusher).Flush()
		for {
			packet, err := readPacket(r.Body)
			if err != nil {
				return
			}
			parsed, err := parseUDP(packet)
			if err != nil {
				return
			}
			payload := append([]byte{}, parsed.payload...)
			if parsed.targetPort == 53 && len(payload) >= 12 {
				payload[2] |= 0x80
			}
			reply, err := udpPacket(parsed.target, parsed.source, parsed.targetPort, parsed.sourcePort, payload, 42)
			if err != nil {
				return
			}
			capsule, _ := encodePacket(reply)
			if _, err := w.Write(capsule); err != nil {
				return
			}
			w.(http.Flusher).Flush()
		}
	})
	server := httptest.NewUnstartedServer(handler)
	server.EnableHTTP2 = true
	server.TLS = &tls.Config{Certificates: []tls.Certificate{{Certificate: [][]byte{cert}, PrivateKey: serverKey}}, ClientAuth: tls.RequestClientCert}
	server.StartTLS()
	return server, testIdentity(t, serverKey)
}

func TestAuthenticatedConnectIPAndUdpRoundTrip(t *testing.T) {
	server, identity := newH2Echo(t)
	defer server.Close()
	dial := func(ctx context.Context, host string, port int) (io.ReadWriteCloser, error) {
		return (&net.Dialer{}).DialContext(ctx, "tcp", server.Listener.Addr().String())
	}
	ctx, cancel := context.WithTimeout(context.Background(), 8*time.Second)
	defer cancel()
	session, err := openSession(ctx, identity, dial, "162.159.198.3", 443)
	if err != nil {
		t.Fatal(err)
	}
	defer session.Close()
	if err := verifyUDP(ctx, session); err != nil {
		t.Fatalf("UDP probe: %v", err)
	}
	flow, err := session.OpenUDP("203.0.113.3", 1234)
	if err != nil {
		t.Fatal(err)
	}
	defer flow.Close()
	if err := flow.WriteDatagram([]byte("payload")); err != nil {
		t.Fatal(err)
	}
	got, err := flow.ReadDatagram()
	if err != nil || string(got) != "payload" {
		t.Fatalf("UDP echo: %v", err)
	}
	if _, err := session.OpenUDP("::1", 1234); err == nil {
		t.Fatal("IPv6 unexpectedly allowed")
	}
	if !identity.Ready() || !netip.MustParseAddr(identity.IPv4).Is4() {
		t.Fatal("invalid identity")
	}
}

func TestWrongPinnedKeyIsRejected(t *testing.T) {
	server, identity := newH2Echo(t)
	defer server.Close()
	unrelated, _ := ecdsa.GenerateKey(elliptic.P256(), rand.Reader)
	pub, _ := x509.MarshalPKIXPublicKey(&unrelated.PublicKey)
	identity.ServerKey = base64.StdEncoding.EncodeToString(pub)
	dial := func(ctx context.Context, host string, port int) (io.ReadWriteCloser, error) {
		return (&net.Dialer{}).DialContext(ctx, "tcp", server.Listener.Addr().String())
	}
	ctx, cancel := context.WithTimeout(context.Background(), 5*time.Second)
	defer cancel()
	if _, err := openSession(ctx, identity, dial, "162.159.198.3", 443); err == nil {
		t.Fatal("wrong server pin accepted")
	}
}
