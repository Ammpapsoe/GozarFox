package update

import (
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
	"time"
)

const latestURL = "https://api.github.com/repos/Ammpapsoe/GozarFox/releases/latest"
const releaseJSONLimit = 1 << 20

var updateHTTPClient = &http.Client{
	Timeout: 15 * time.Minute,
	CheckRedirect: func(req *http.Request, via []*http.Request) error {
		if len(via) >= 5 || !allowedRedirect(req.URL) {
			return errors.New("untrusted update redirect")
		}
		return nil
	},
}

func allowedRedirect(u *url.URL) bool {
	if u == nil || u.Scheme != "https" || u.User != nil || !strings.EqualFold(u.Host, u.Hostname()) {
		return false
	}
	switch strings.ToLower(u.Hostname()) {
	case "github.com", "api.github.com", "release-assets.githubusercontent.com",
		"objects.githubusercontent.com", "github-releases.githubusercontent.com",
		"github-production-release-asset-2e65be.s3.amazonaws.com":
		return true
	default:
		return false
	}
}

type releaseAsset struct {
	Name string `json:"name"`
	URL  string `json:"browser_download_url"`
}

type releaseMetadata struct {
	Tag    string         `json:"tag_name"`
	Assets []releaseAsset `json:"assets"`
}

// Check fetches the latest release metadata and verifies its desktop manifest.
// An unavailable or unsigned release is an error, never a current result.
func Check(ctx context.Context, installed string) (*Verified, error) {
	key, err := hex.DecodeString(KeyHex)
	if err != nil {
		return nil, err
	}
	ctx, cancel := context.WithTimeout(ctx, 30*time.Second)
	defer cancel()
	return checkWithKey(ctx, updateHTTPClient, installed, time.Now(), key)
}

func checkWithKey(ctx context.Context, client *http.Client, installed string, now time.Time, key ed25519.PublicKey) (*Verified, error) {
	body, err := getLimited(ctx, client, latestURL, releaseJSONLimit, true)
	if err != nil {
		return nil, err
	}
	var release releaseMetadata
	if err := json.Unmarshal(body, &release); err != nil || !tagPattern.MatchString(release.Tag) || len(release.Assets) > 500 {
		return nil, errors.New("invalid GitHub release metadata")
	}
	urls := map[string]string{}
	for _, asset := range release.Assets {
		if asset.Name != "desktop-update.json" && asset.Name != "desktop-update.json.sig" {
			continue
		}
		if _, duplicate := urls[asset.Name]; duplicate || !trustedArtifactURL(asset.URL, release.Tag, asset.Name) {
			return nil, errors.New("invalid update metadata asset")
		}
		urls[asset.Name] = asset.URL
	}
	if len(urls) != 2 {
		return nil, errors.New("signed desktop manifest missing")
	}
	manifest, err := getLimited(ctx, client, urls["desktop-update.json"], maxManifestBytes, false)
	if err != nil {
		return nil, err
	}
	signature, err := getLimited(ctx, client, urls["desktop-update.json.sig"], 1024, false)
	if err != nil {
		return nil, err
	}
	return verifyWithKey(manifest, string(signature), installed, release.Tag, now, key)
}

func getLimited(ctx context.Context, client *http.Client, address string, limit int64, api bool) ([]byte, error) {
	req, err := http.NewRequestWithContext(ctx, http.MethodGet, address, nil)
	if err != nil {
		return nil, err
	}
	req.Header.Set("User-Agent", "Gozar desktop updater")
	if api {
		req.Header.Set("Accept", "application/vnd.github+json")
	}
	resp, err := client.Do(req)
	if err != nil {
		return nil, err
	}
	defer resp.Body.Close()
	if resp.StatusCode != http.StatusOK {
		return nil, fmt.Errorf("update server returned HTTP %d", resp.StatusCode)
	}
	body, err := io.ReadAll(io.LimitReader(resp.Body, limit+1))
	if err != nil {
		return nil, err
	}
	if int64(len(body)) > limit {
		return nil, errors.New("update response exceeds limit")
	}
	return body, nil
}
