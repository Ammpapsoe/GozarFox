package relay

import (
	"crypto/ed25519"
	"crypto/rand"
	"encoding/hex"
	"strings"
	"testing"
	"time"
)

func signedDocument(t *testing.T, raw []byte) (string, ed25519.PublicKey) {
	t.Helper()
	public, private, err := ed25519.GenerateKey(rand.Reader)
	if err != nil {
		t.Fatal(err)
	}
	message := append([]byte(signaturePrefix), raw...)
	return hex.EncodeToString(ed25519.Sign(private, message)), public
}

func TestDiscoveryAcceptsValidSignatureAndRejectsTampering(t *testing.T) {
	raw := []byte(`{"version":1,"relays":["https://gozar-guardian-a.gozarfox7.workers.dev","https://gozar-guardian-b.gozarfox7.workers.dev"]}`)
	signature, public := signedDocument(t, raw)
	if err := verifyWithKey(raw, signature, hex.EncodeToString(public)); err != nil {
		t.Fatalf("valid signature rejected: %v", err)
	}
	if err := Verify(raw, signature); err == nil {
		t.Fatal("test signature unexpectedly matched the production key")
	}
	if err := AcceptPublished(raw, signature); err == nil {
		t.Fatal("accepted a signature from an unknown key")
	}
	if err := Verify(raw, ""); err == nil {
		t.Fatal("accepted a missing signature")
	}
	if err := Verify([]byte(strings.Replace(string(raw), "guardian-a", "guardian-x", 1)), signature); err == nil {
		t.Fatal("accepted tampered bytes")
	}
}

func TestEndpointPolicyAndCacheLifetime(t *testing.T) {
	valid := []string{
		"https://gozar-guardian-a.gozarfox7.workers.dev",
		"https://gozar-guardian-b.gozarfox7.workers.dev/",
	}
	parsed, err := validSet(valid)
	if err != nil || len(parsed) != 2 || parsed[1] != strings.TrimSuffix(valid[1], "/") {
		t.Fatalf("valid endpoints rejected: %v", err)
	}
	invalid := []string{
		"http://gozar-guardian-a.gozarfox7.workers.dev",
		"https://example.org",
		"https://gozar-guardian-a.gozarfox7.workers.dev.evil.invalid",
		"https://user@gozar-guardian-a.gozarfox7.workers.dev",
		"https://gozar-guardian-a.gozarfox7.workers.dev:444",
		"https://gozar-guardian-a.gozarfox7.workers.dev/v1/guardian/pass",
		"https://gozar-guardian-a.gozarfox7.workers.dev/?next=evil",
	}
	for _, value := range invalid {
		if ValidURL(value) {
			t.Fatalf("accepted %s", value)
		}
	}
	now := time.Unix(1_800_000_000, 0)
	encoded, err := EncodeCache([]string{valid[0]}, now)
	if err != nil {
		t.Fatal(err)
	}
	if _, _, err := DecodeCache(encoded, now.Add(CacheTTL-time.Second)); err != nil {
		t.Fatalf("fresh cache rejected: %v", err)
	}
	if _, _, err := DecodeCache(encoded, now.Add(CacheTTL)); err == nil {
		t.Fatal("expired cache accepted")
	}
	if _, _, err := DecodeCache(encoded, now.Add(-6*time.Minute)); err == nil {
		t.Fatal("future cache accepted")
	}
	if _, _, err := DecodeCache(strings.Replace(encoded, "guardian-a", "guardian-x.evil", 1), now); err == nil {
		t.Fatal("tampered cache accepted")
	}
}

func TestInvalidPublishedDocumentKeepsPreviousEndpoints(t *testing.T) {
	previous := []string{"https://gozar-guardian-c.gozarfox7.workers.dev"}
	restore := ReplaceForTest(previous)
	defer restore()
	if err := AcceptPublished([]byte(`{"version":1,"relays":["https://example.org"]}`), "00"); err == nil {
		t.Fatal("accepted an invalid document")
	}
	actual := Endpoints()
	if len(actual) != 1 || actual[0] != previous[0] {
		t.Fatal("invalid refresh replaced the previous verified list")
	}
}
