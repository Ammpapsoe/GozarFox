package relay

import (
	"bytes"
	"crypto/sha256"
	"crypto/subtle"
	"crypto/tls"
	"crypto/x509"
	"encoding/hex"
	"errors"
	"io"
	"net/http"
	"strings"
	"time"
)

var WorkerSPKISHA256Pins = []string{
	"31e940075ef2f2bc22ec290916699ab9dc0ae463e81d68023415e7e8e28f621b",
}

const maxResponseBytes = 4 * 1024 * 1024

type Reply struct {
	Code    int
	Body    []byte
	Headers http.Header
}

type CallFunc func(endpoint, path, method, access string, body []byte) (*Reply, error)

var transportCall CallFunc = Call

func RoundTrip(endpoint, path, method, access string, body []byte) (*Reply, error) {
	return transportCall(endpoint, path, method, access, body)
}

func ReplaceCallForTest(call CallFunc) func() {
	previous := transportCall
	transportCall = call
	return func() { transportCall = previous }
}

func Retry(status int) bool {
	return status == http.StatusRequestTimeout || status == http.StatusTooEarly || status == http.StatusTooManyRequests ||
		status == http.StatusUnavailableForLegalReasons || status >= 500
}

func Allowed(path, method string) bool {
	switch path {
	case "/v1/guardian/pass":
		return method == http.MethodGet || method == http.MethodHead
	case "/v1/guardian/activate":
		return method == http.MethodPost
	case "/v1/mozilla/servers":
		return method == http.MethodGet
	default:
		return false
	}
}

func pinMatches(cert *x509.Certificate, pins []string) bool {
	if cert == nil || cert.PublicKey == nil {
		return false
	}
	encoded, err := x509.MarshalPKIXPublicKey(cert.PublicKey)
	if err != nil {
		return false
	}
	actual := sha256.Sum256(encoded)
	for _, pin := range pins {
		expected, err := hex.DecodeString(pin)
		if err == nil && len(expected) == sha256.Size && subtle.ConstantTimeCompare(actual[:], expected) == 1 {
			return true
		}
	}
	return false
}

func pinnedTransport(pins []string) (*http.Transport, error) {
	if len(pins) == 0 {
		return nil, errors.New("relay pin set is empty")
	}
	for _, pin := range pins {
		decoded, err := hex.DecodeString(pin)
		if err != nil || len(decoded) != sha256.Size {
			return nil, errors.New("relay pin is invalid")
		}
	}
	return &http.Transport{
		ForceAttemptHTTP2: true,
		TLSClientConfig: &tls.Config{
			MinVersion: tls.VersionTLS12,
			VerifyConnection: func(state tls.ConnectionState) error {
				if len(state.PeerCertificates) == 0 || !pinMatches(state.PeerCertificates[0], pins) {
					return errors.New("relay public key pin mismatch")
				}
				return nil
			},
		},
		TLSHandshakeTimeout: 15 * time.Second,
		DisableKeepAlives:   true,
	}, nil
}

func Call(endpoint, path, method, access string, body []byte) (*Reply, error) {
	if !ValidURL(endpoint) || !Allowed(path, method) || len(body) > 4096 {
		return nil, errors.New("relay request is not permitted")
	}
	transport, err := pinnedTransport(WorkerSPKISHA256Pins)
	if err != nil {
		return nil, err
	}
	defer transport.CloseIdleConnections()
	client := &http.Client{
		Transport: transport,
		Timeout:   20 * time.Second,
		CheckRedirect: func(*http.Request, []*http.Request) error {
			return http.ErrUseLastResponse
		},
	}
	request, err := http.NewRequest(method, strings.TrimSuffix(endpoint, "/")+path, bytes.NewReader(body))
	if err != nil {
		return nil, err
	}
	request.Header.Set("Accept", "application/json")
	request.Header.Set("Cache-Control", "no-store")
	if access != "" {
		request.Header.Set("Authorization", "Bearer "+access)
	}
	if len(body) != 0 {
		request.Header.Set("Content-Type", "application/json")
	}
	response, err := client.Do(request)
	if err != nil {
		return nil, err
	}
	defer response.Body.Close()
	payload, err := io.ReadAll(io.LimitReader(response.Body, maxResponseBytes+1))
	if err != nil || len(payload) > maxResponseBytes {
		return nil, errors.New("relay response is too large")
	}
	response.Header.Del("Set-Cookie")
	return &Reply{Code: response.StatusCode, Body: payload, Headers: response.Header}, nil
}

func Do(path, method, access string, body []byte) (*Reply, error) {
	var lastReply *Reply
	var lastFailure error
	for _, endpoint := range Endpoints() {
		reply, err := RoundTrip(endpoint, path, method, access, body)
		if err != nil {
			lastFailure = err
			continue
		}
		if Retry(reply.Code) {
			lastReply = reply
			continue
		}
		return reply, nil
	}
	if lastReply != nil {
		return lastReply, nil
	}
	if lastFailure != nil {
		return nil, lastFailure
	}
	return nil, errors.New("no verified relay is configured")
}
