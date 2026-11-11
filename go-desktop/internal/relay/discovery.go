package relay

import (
	"bytes"
	"context"
	"crypto/ed25519"
	"encoding/hex"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"net/http"
	"net/url"
	"strings"
	"sync"
	"time"
)

const KeyHex = "5a74ff31ae5dff9923384ae85eb403cd26967c0f63fe78cf03e5471788aec446"
const CacheTTL = 24 * time.Hour
const MaxDocumentBytes = 16 * 1024

const signaturePrefix = "GOZAR-RELAY-ENDPOINTS-V1\n"
const documentURL = "https://raw.githubusercontent.com/Ammpapsoe/GozarFox/main/packaging/relay-endpoints.json"
const signatureURL = documentURL + ".sig"

var state = struct {
	sync.RWMutex
	endpoints []string
	fetchedAt time.Time
}{endpoints: []string{
	"https://gozar-guardian-a.gozarfox7.workers.dev",
	"https://gozar-guardian-b.gozarfox7.workers.dev",
	"https://gozar-guardian-c.gozarfox7.workers.dev",
}}

type document struct {
	Version int      `json:"version"`
	Relays  []string `json:"relays"`
}

type cachedDocument struct {
	Version   int      `json:"version"`
	FetchedAt int64    `json:"fetched_at"`
	Relays    []string `json:"relays"`
}

func Endpoints() []string {
	state.RLock()
	defer state.RUnlock()
	return append([]string(nil), state.endpoints...)
}

func ValidURL(raw string) bool {
	if raw == "" || len(raw) > 255 || raw != strings.TrimSpace(raw) {
		return false
	}
	parsed, err := url.Parse(raw)
	if err != nil || parsed.Scheme != "https" || parsed.User != nil || parsed.RawQuery != "" || parsed.ForceQuery || parsed.Fragment != "" || parsed.Port() != "" {
		return false
	}
	if parsed.EscapedPath() != "" && parsed.EscapedPath() != "/" {
		return false
	}
	host := strings.ToLower(parsed.Hostname())
	const suffix = ".gozarfox7.workers.dev"
	if !strings.HasSuffix(host, suffix) || len(host) <= len(suffix) {
		return false
	}
	label := strings.TrimSuffix(host, suffix)
	if label == "" || strings.Contains(label, ".") || label[0] == '-' || label[len(label)-1] == '-' {
		return false
	}
	for _, char := range label {
		if (char < 'a' || char > 'z') && (char < '0' || char > '9') && char != '-' {
			return false
		}
	}
	return true
}

func normalize(raw string) string { return strings.TrimSuffix(raw, "/") }

func validSet(values []string) ([]string, error) {
	if len(values) < 1 || len(values) > 3 {
		return nil, errors.New("relay count is invalid")
	}
	seen := make(map[string]bool, len(values))
	out := make([]string, 0, len(values))
	for _, value := range values {
		if !ValidURL(value) {
			return nil, errors.New("relay URL is invalid")
		}
		value = normalize(value)
		if seen[value] {
			return nil, errors.New("relay URL is duplicated")
		}
		seen[value] = true
		out = append(out, value)
	}
	return out, nil
}

func Verify(raw []byte, signatureText string) error {
	return verifyWithKey(raw, signatureText, KeyHex)
}

func verifyWithKey(raw []byte, signatureText, keyHex string) error {
	if len(raw) == 0 || len(raw) > MaxDocumentBytes {
		return errors.New("relay document size is invalid")
	}
	signature, err := hex.DecodeString(strings.TrimSpace(signatureText))
	if err != nil || len(signature) != ed25519.SignatureSize {
		return errors.New("relay signature is invalid")
	}
	public, err := hex.DecodeString(keyHex)
	if err != nil || len(public) != ed25519.PublicKeySize {
		return errors.New("relay public key is invalid")
	}
	message := append([]byte(signaturePrefix), raw...)
	if !ed25519.Verify(ed25519.PublicKey(public), message, signature) {
		return errors.New("relay signature does not match")
	}
	return nil
}

func Parse(raw []byte) ([]string, error) {
	if len(raw) == 0 || len(raw) > MaxDocumentBytes {
		return nil, errors.New("relay document size is invalid")
	}
	decoder := json.NewDecoder(bytes.NewReader(raw))
	decoder.DisallowUnknownFields()
	var parsed document
	if err := decoder.Decode(&parsed); err != nil || parsed.Version != 1 {
		return nil, errors.New("relay document is invalid")
	}
	if decoder.Decode(&struct{}{}) != io.EOF {
		return nil, errors.New("relay document has trailing data")
	}
	return validSet(parsed.Relays)
}

func AcceptPublished(raw []byte, signatureText string) error {
	if err := Verify(raw, signatureText); err != nil {
		return err
	}
	endpoints, err := Parse(raw)
	if err != nil {
		return err
	}
	state.Lock()
	state.endpoints = endpoints
	state.fetchedAt = time.Now()
	state.Unlock()
	return nil
}

func EncodeCache(endpoints []string, fetchedAt time.Time) (string, error) {
	valid, err := validSet(endpoints)
	if err != nil || fetchedAt.Unix() <= 0 {
		return "", errors.New("relay cache is invalid")
	}
	raw, err := json.Marshal(cachedDocument{Version: 1, FetchedAt: fetchedAt.Unix(), Relays: valid})
	if err != nil || len(raw) > MaxDocumentBytes {
		return "", errors.New("relay cache is invalid")
	}
	return string(raw), nil
}

func DecodeCache(raw string, now time.Time) ([]string, time.Time, error) {
	if raw == "" || len(raw) > MaxDocumentBytes || now.Unix() <= 0 {
		return nil, time.Time{}, errors.New("relay cache is invalid")
	}
	decoder := json.NewDecoder(strings.NewReader(raw))
	decoder.DisallowUnknownFields()
	var cached cachedDocument
	if err := decoder.Decode(&cached); err != nil || cached.Version != 1 {
		return nil, time.Time{}, errors.New("relay cache is invalid")
	}
	if decoder.Decode(&struct{}{}) != io.EOF {
		return nil, time.Time{}, errors.New("relay cache has trailing data")
	}
	fetchedAt := time.Unix(cached.FetchedAt, 0)
	if cached.FetchedAt <= 0 || fetchedAt.After(now.Add(5*time.Minute)) || now.Sub(fetchedAt) >= CacheTTL {
		return nil, time.Time{}, errors.New("relay cache is expired")
	}
	endpoints, err := validSet(cached.Relays)
	return endpoints, fetchedAt, err
}

func RestoreCache(raw string, now time.Time) bool {
	endpoints, fetchedAt, err := DecodeCache(raw, now)
	if err != nil {
		return false
	}
	state.Lock()
	state.endpoints = endpoints
	state.fetchedAt = fetchedAt
	state.Unlock()
	return true
}

func fetchText(ctx context.Context, client *http.Client, address string) ([]byte, error) {
	request, err := http.NewRequestWithContext(ctx, http.MethodGet, address, nil)
	if err != nil {
		return nil, err
	}
	request.Header.Set("Accept", "application/json")
	request.Header.Set("Cache-Control", "no-cache")
	response, err := client.Do(request)
	if err != nil {
		return nil, err
	}
	defer response.Body.Close()
	if response.StatusCode != http.StatusOK {
		return nil, fmt.Errorf("relay discovery returned HTTP %d", response.StatusCode)
	}
	raw, err := io.ReadAll(io.LimitReader(response.Body, MaxDocumentBytes+1))
	if err != nil || len(raw) > MaxDocumentBytes {
		return nil, errors.New("relay discovery response is invalid")
	}
	return raw, nil
}

func Refresh(ctx context.Context, client *http.Client, force bool) (bool, error) {
	state.RLock()
	fresh := !state.fetchedAt.IsZero() && time.Since(state.fetchedAt) >= 0 && time.Since(state.fetchedAt) < CacheTTL
	state.RUnlock()
	if fresh && !force {
		return false, nil
	}
	if client == nil {
		client = &http.Client{Timeout: 15 * time.Second, CheckRedirect: func(*http.Request, []*http.Request) error { return http.ErrUseLastResponse }}
	}
	raw, err := fetchText(ctx, client, documentURL)
	if err != nil {
		return false, err
	}
	signature, err := fetchText(ctx, client, signatureURL)
	if err != nil {
		return false, err
	}
	if err := Verify(raw, string(signature)); err != nil {
		return false, err
	}
	endpoints, err := Parse(raw)
	if err != nil {
		return false, err
	}
	state.Lock()
	state.endpoints = endpoints
	state.fetchedAt = time.Now()
	state.Unlock()
	return true, nil
}

func ReplaceForTest(endpoints []string) func() {
	state.Lock()
	previous, previousTime := state.endpoints, state.fetchedAt
	state.endpoints = append([]string(nil), endpoints...)
	state.fetchedAt = time.Time{}
	state.Unlock()
	return func() {
		state.Lock()
		state.endpoints, state.fetchedAt = previous, previousTime
		state.Unlock()
	}
}
