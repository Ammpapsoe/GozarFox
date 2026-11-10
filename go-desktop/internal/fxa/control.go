package fxa

import (
	"context"
	"errors"
	"io"
	"net"
	"net/http"
	"net/url"
	"strings"
	"sync"
	"time"
)

// allowedControlRequest deliberately excludes account creation, activation,
// redirects and arbitrary destinations. Only an already-authorized session
// can renew its short-lived credentials through the Mozilla tunnel.
func allowedControlRequest(method, address string) bool {
	u, err := url.Parse(address)
	if err != nil || u.Scheme != "https" || u.User != nil || u.RawQuery != "" || u.ForceQuery || u.Fragment != "" ||
		(u.Port() != "" && u.Port() != "443") {
		return false
	}
	switch {
	case strings.EqualFold(u.Hostname(), "vpn.mozilla.org") && u.EscapedPath() == "/api/v1/fpn/token":
		return method == http.MethodGet || method == http.MethodHead
	case strings.EqualFold(u.Hostname(), "oauth.accounts.firefox.com") && u.EscapedPath() == "/v1/token":
		return method == http.MethodPost
	default:
		return false
	}
}

func controlCall(control *http.Client, method, address, body string, headers map[string]string) (*Reply, error) {
	if !allowedControlRequest(method, address) {
		return nil, errors.New("control tunnel destination is not permitted")
	}
	// Unlike the normal account flow, never invoke the separate direct-socket
	// challenge solver from a request explicitly scoped to the tunnel.
	return callWithTLSRetry(control, method, address, body, headers)
}

// RelayDial opens a byte stream through the existing authenticated Mozilla
// tunnel. It is not an HTTP proxy and never receives the inner TLS plaintext.
type RelayDial func(ctx context.Context, host string, port int) (io.ReadWriteCloser, error)

// NewControlClient gives the standard HTTP/TLS stack a net.Conn backed by the
// existing Mozilla CONNECT stream. TLS uses normal system roots and hostname
// verification; requests cannot fall back to a direct connection or a third
// party. The returned cleanup closes idle transport connections.
func NewControlClient(dial RelayDial) (*http.Client, func()) {
	transport := &http.Transport{
		Proxy: nil,
		DialContext: func(ctx context.Context, network, address string) (net.Conn, error) {
			host, port, err := net.SplitHostPort(address)
			if err != nil || port != "443" ||
				(!strings.EqualFold(host, "vpn.mozilla.org") && !strings.EqualFold(host, "oauth.accounts.firefox.com")) {
				return nil, errors.New("control tunnel host is not permitted")
			}
			if network != "tcp" && network != "tcp4" && network != "tcp6" {
				return nil, errors.New("control tunnel requires TCP")
			}
			if dial == nil {
				return nil, errors.New("control tunnel unavailable")
			}
			upstream, err := dial(ctx, host, 443)
			if err != nil {
				return nil, err
			}
			if upstream == nil {
				return nil, errors.New("control tunnel returned no stream")
			}
			if err := ctx.Err(); err != nil {
				_ = upstream.Close()
				return nil, err
			}
			client, relay := net.Pipe()
			var once sync.Once
			closeBoth := func() { once.Do(func() { _ = relay.Close(); _ = upstream.Close() }) }
			go func() { _, _ = io.Copy(upstream, relay); closeBoth() }()
			go func() { _, _ = io.Copy(relay, upstream); closeBoth() }()
			return client, nil
		},
		TLSHandshakeTimeout: 12 * time.Second,
		IdleConnTimeout:     10 * time.Second,
		DisableKeepAlives:   true,
	}
	client := &http.Client{
		Transport: transport,
		Timeout:   25 * time.Second,
		CheckRedirect: func(*http.Request, []*http.Request) error {
			return http.ErrUseLastResponse
		},
	}
	return client, transport.CloseIdleConnections
}
