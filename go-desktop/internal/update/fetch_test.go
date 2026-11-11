package update

import (
	"bytes"
	"context"
	"encoding/json"
	"io"
	"net/http"
	"net/url"
	"strings"
	"testing"
)

type transportFunc func(*http.Request) (*http.Response, error)

func (f transportFunc) RoundTrip(req *http.Request) (*http.Response, error) { return f(req) }

func testClient(responses map[string][]byte) *http.Client {
	return &http.Client{Transport: transportFunc(func(req *http.Request) (*http.Response, error) {
		body, ok := responses[req.URL.String()]
		status := http.StatusOK
		if !ok {
			status = http.StatusNotFound
		}
		return &http.Response{StatusCode: status, Body: io.NopCloser(bytes.NewReader(body)), Header: make(http.Header), Request: req, ContentLength: int64(len(body))}, nil
	})}
}

func releaseFixture(tag string) releaseMetadata {
	base := "https://github.com/Ammpapsoe/GozarFox/releases/download/" + tag + "/"
	return releaseMetadata{Tag: tag, Assets: []releaseAsset{
		{Name: "desktop-update.json", URL: base + "desktop-update.json"},
		{Name: "desktop-update.json.sig", URL: base + "desktop-update.json.sig"},
	}}
}

func TestCheckAuthenticatesLatestRelease(t *testing.T) {
	raw, signature, public := signed(t, fixture())
	release := releaseFixture("v1.11.1")
	metadata, _ := json.Marshal(release)
	client := testClient(map[string][]byte{
		latestURL: metadata, release.Assets[0].URL: raw, release.Assets[1].URL: []byte(signature),
	})
	verified, err := checkWithKey(context.Background(), client, "1.10.4", fixedNow, public)
	if err != nil || !verified.Newer() || verified.Manifest().Version != "1.10.5" {
		t.Fatalf("signed latest release rejected: %v", err)
	}
}

func TestCheckFailsClosedForMissingDuplicateAndForgedAssets(t *testing.T) {
	raw, signature, public := signed(t, fixture())
	for _, name := range []string{"missing", "duplicate", "wrong host", "forged", "oversized"} {
		t.Run(name, func(t *testing.T) {
			release := releaseFixture("v1.11.1")
			switch name {
			case "missing":
				release.Assets = release.Assets[:1]
			case "duplicate":
				release.Assets = append(release.Assets, release.Assets[0])
			case "wrong host":
				release.Assets[0].URL = strings.Replace(release.Assets[0].URL, "github.com", "attacker.example", 1)
			}
			metadata, _ := json.Marshal(release)
			fixtureRelease := releaseFixture("v1.11.1")
			data := map[string][]byte{latestURL: metadata, fixtureRelease.Assets[0].URL: raw, fixtureRelease.Assets[1].URL: []byte(signature)}
			if name == "forged" {
				data[fixtureRelease.Assets[0].URL] = append(append([]byte(nil), raw...), ' ')
			}
			if name == "oversized" {
				data[latestURL] = bytes.Repeat([]byte("x"), releaseJSONLimit+1)
			}
			if _, err := checkWithKey(context.Background(), testClient(data), "1.10.4", fixedNow, public); err == nil {
				t.Fatal("untrusted update was accepted")
			}
		})
	}
}

func TestOnlyKnownHTTPSRedirectHostsAreAllowed(t *testing.T) {
	for _, entry := range []struct {
		address string
		allowed bool
	}{
		{"https://github.com/some/path", true},
		{"https://release-assets.githubusercontent.com/signed/path?x=1", true},
		{"http://github.com/path", false},
		{"https://user@github.com/path", false},
		{"https://github.com:443/path", false},
		{"https://github.com.evil.example/path", false},
		{"https://evil.example/path", false},
	} {
		u, err := url.Parse(entry.address)
		if err != nil || allowedRedirect(u) != entry.allowed {
			t.Errorf("redirect decision for %q", entry.address)
		}
	}
}
