package fxa

import (
	"context"
	"crypto/tls"
	"fmt"
	"net"
	"net/http"
	"net/http/httptest"
	"net/url"
	"sync"
	"testing"
)

func TestTLSRetryIsLimitedToMozillaTokenEndpoints(t *testing.T) {
	valid := []struct{ method, address string }{
		{http.MethodPost, authBase + "/oauth/token"},
		{http.MethodGet, guardianURL + "/api/v1/fpn/token"},
		{http.MethodHead, guardianURL + "/api/v1/fpn/token"},
	}
	for _, item := range valid {
		if !mayRetryMozillaTLS12(item.method, item.address, 406) || mayRetryMozillaTLS12(item.method, item.address, 403) {
			t.Fatal("unexpected fallback policy")
		}
	}
	invalid := []struct{ method, address string }{
		{http.MethodGet, authBase + "/oauth/token"},
		{http.MethodPost, guardianURL + "/api/v1/fpn/token"},
		{http.MethodPost, authBase + "/account/login"},
		{http.MethodPost, guardianURL + "/api/v1/fpn/activate"},
		{http.MethodGet, guardianURL + "/api/v1/fpn/token?next=evil"},
		{http.MethodGet, "https://vpn.mozilla.org.evil.test/api/v1/fpn/token"},
		{http.MethodGet, "http://vpn.mozilla.org/api/v1/fpn/token"},
	}
	for _, item := range invalid {
		if mayRetryMozillaTLS12(item.method, item.address, 406) {
			t.Fatalf("unexpected fallback for %q", item.address)
		}
	}
}

func TestTLSRetryUsesVerifiedTLS12After406(t *testing.T) {
	var mu sync.Mutex
	var versions []uint16
	server := httptest.NewUnstartedServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		mu.Lock()
		versions = append(versions, r.TLS.Version)
		mu.Unlock()
		if r.TLS.Version == tls.VersionTLS13 {
			w.WriteHeader(http.StatusNotAcceptable)
			return
		}
		if r.TLS.Version != tls.VersionTLS12 || r.Method != http.MethodPost || r.URL.Path != "/v1/oauth/token" {
			w.WriteHeader(http.StatusBadRequest)
			return
		}
		fmt.Fprint(w, `{"access_token":"synthetic"}`)
	}))
	server.StartTLS()
	defer server.Close()
	primary := server.Client()
	transport := primary.Transport.(*http.Transport).Clone()
	local, err := url.Parse(server.URL)
	if err != nil {
		t.Fatal(err)
	}
	transport.TLSClientConfig = transport.TLSClientConfig.Clone()
	transport.TLSClientConfig.ServerName = local.Hostname()
	transport.DialContext = func(ctx context.Context, _, _ string) (net.Conn, error) {
		var dialer net.Dialer
		return dialer.DialContext(ctx, "tcp", server.Listener.Addr().String())
	}
	primary.Transport = transport
	defer primary.CloseIdleConnections()
	reply, err := callWithTLSRetry(primary, http.MethodPost, authBase+"/oauth/token", "{}", nil)
	if err != nil || reply.Code != 200 {
		t.Fatalf("compatible request failed: %v", err)
	}
	mu.Lock()
	defer mu.Unlock()
	if len(versions) != 2 || versions[0] != tls.VersionTLS13 || versions[1] != tls.VersionTLS12 {
		t.Fatalf("unexpected TLS sequence: %v", versions)
	}
}

func TestExistingPassNeverActivatesAnEntitlement(t *testing.T) {
	old := client
	defer func() { client = old }()
	calls := 0
	client = &http.Client{Transport: fakeTransport(func(r *http.Request) (*http.Response, error) {
		calls++
		if r.Method != http.MethodGet || r.URL.Path != "/api/v1/fpn/token" {
			t.Fatal("read-only diagnostic attempted to activate or mutate the account")
		}
		return testResponse(403, `{}`), nil
	})}
	if _, err := ExistingPassToken("synthetic"); err == nil || calls != 1 {
		t.Fatal("expected one read-only request and an authorization failure")
	}
}

func TestTLSRetryDoesNotOverrideStricterClientPolicy(t *testing.T) {
	base := &http.Client{Transport: &http.Transport{TLSClientConfig: &tls.Config{MinVersion: tls.VersionTLS13}}}
	compatible, closeIdle := tls12Client(base)
	defer closeIdle()
	if compatible != nil {
		t.Fatal("retry weakened an explicit TLS 1.3 minimum")
	}
}
