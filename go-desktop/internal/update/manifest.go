// Package update verifies desktop release metadata independently of GitHub.
// It does not itself install packages or claim that a rollback has succeeded.
package update

import (
	"bytes"
	"crypto/ed25519"
	"encoding/base64"
	"encoding/hex"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"net/url"
	"regexp"
	"strconv"
	"strings"
	"time"
)

const KeyHex = "14cf2b6901d20a703c241674b8d2f1cdbb3fe87cba85529af82f004115575903"
const maxManifestBytes = 65536
const maxArtifactSize = 512 * 1024 * 1024

var (
	versionPattern = regexp.MustCompile(`^[0-9]{1,4}(\.[0-9]{1,4}){2}$`)
	tagPattern     = regexp.MustCompile(`^v[0-9]{1,4}(\.[0-9]{1,4}){1,3}(-desktop)?$`)
	namePattern    = regexp.MustCompile(`^[A-Za-z0-9][A-Za-z0-9._-]{0,199}$`)
	idPattern      = regexp.MustCompile(`^[a-z0-9]+(-[a-z0-9]+)*$`)
	hashPattern    = regexp.MustCompile(`^[0-9a-f]{64}$`)
)

type Artifact struct {
	ID     string `json:"id"`
	OS     string `json:"os"`
	Arch   string `json:"arch"`
	Kind   string `json:"kind"`
	Name   string `json:"name"`
	SHA256 string `json:"sha256"`
	Size   int64  `json:"size"`
	URL    string `json:"url"`
}

type Manifest struct {
	Version    string     `json:"version"`
	ReleaseTag string     `json:"release_tag"`
	IssuedAt   int64      `json:"issued_at"`
	Notes      string     `json:"notes"`
	Artifacts  []Artifact `json:"artifacts"`
}

// Verified is created only after signature, schema, version and URL checks.
// Its fields are private so callers cannot mutate an accepted artifact in place.
type Verified struct {
	manifest Manifest
	newer    bool
}

func (v *Verified) Newer() bool { return v != nil && v.newer }

// Manifest returns a defensive copy; changing it does not change Verified.
func (v *Verified) Manifest() Manifest {
	if v == nil {
		return Manifest{}
	}
	out := v.manifest
	out.Artifacts = append([]Artifact(nil), out.Artifacts...)
	return out
}

// Verify authenticates the exact bytes before decoding them. The signed tag
// must also match the release from which the manifest was obtained.
func Verify(raw []byte, signature string, installed string, releaseTag string) (*Verified, error) {
	key, err := hex.DecodeString(KeyHex)
	if err != nil {
		return nil, err
	}
	return verifyWithKey(raw, signature, installed, releaseTag, time.Now(), key)
}

func verifyWithKey(raw []byte, signature string, installed string, releaseTag string, now time.Time, key ed25519.PublicKey) (*Verified, error) {
	if len(raw) == 0 || len(raw) > maxManifestBytes {
		return nil, errors.New("manifest size outside limit")
	}
	sig, err := base64.StdEncoding.Strict().DecodeString(strings.TrimSpace(signature))
	if err != nil || len(sig) != ed25519.SignatureSize || len(key) != ed25519.PublicKeySize || !ed25519.Verify(key, raw, sig) {
		return nil, errors.New("invalid release signature")
	}
	if err := rejectDuplicateKeys(raw); err != nil {
		return nil, fmt.Errorf("invalid manifest: %w", err)
	}
	if err := exactSchema(raw); err != nil {
		return nil, fmt.Errorf("invalid manifest: %w", err)
	}
	decoder := json.NewDecoder(bytes.NewReader(raw))
	decoder.DisallowUnknownFields()
	var m Manifest
	if err := decoder.Decode(&m); err != nil {
		return nil, fmt.Errorf("invalid manifest: %w", err)
	}
	if !versionPattern.MatchString(m.Version) || !versionPattern.MatchString(installed) {
		return nil, errors.New("invalid version")
	}
	if !tagPattern.MatchString(m.ReleaseTag) || m.ReleaseTag != releaseTag {
		return nil, errors.New("manifest does not match release tag")
	}
	if m.IssuedAt <= 0 || m.IssuedAt > now.Unix()+24*60*60 {
		return nil, errors.New("invalid manifest timestamp")
	}
	if len(m.Artifacts) == 0 || len(m.Artifacts) > 64 {
		return nil, errors.New("invalid artifact count")
	}
	ids, names := make(map[string]bool), make(map[string]bool)
	for _, artifact := range m.Artifacts {
		if !idPattern.MatchString(artifact.ID) || len(artifact.ID) > 100 || ids[artifact.ID] ||
			!namePattern.MatchString(artifact.Name) || names[artifact.Name] ||
			!hashPattern.MatchString(artifact.SHA256) || artifact.Size <= 0 || artifact.Size > maxArtifactSize ||
			!validPlatform(artifact.OS, artifact.Arch, artifact.Kind) ||
			!trustedArtifactURL(artifact.URL, m.ReleaseTag, artifact.Name) {
			return nil, errors.New("invalid or duplicate artifact")
		}
		ids[artifact.ID], names[artifact.Name] = true, true
	}
	order := compareVersions(m.Version, installed)
	if order < 0 {
		return nil, errors.New("release is older than installed version")
	}
	return &Verified{manifest: m, newer: order > 0}, nil
}

func validPlatform(osName, arch, kind string) bool {
	if arch != "amd64" && arch != "arm64" {
		return false
	}
	switch osName {
	case "windows":
		return kind == "exe" || kind == "zip"
	case "darwin":
		return kind == "tar.xz"
	case "linux":
		switch kind {
		case "deb", "tar.xz", "appimage", "flatpak", "snap", "rpm", "pkg.tar.zst":
			return true
		}
	}
	return false
}

func trustedArtifactURL(raw, tag, name string) bool {
	u, err := url.Parse(raw)
	if err != nil || u.Scheme != "https" || !strings.EqualFold(u.Host, "github.com") ||
		u.User != nil || u.RawQuery != "" || u.Fragment != "" || u.Opaque != "" {
		return false
	}
	path := "/Ammpapsoe/GozarFox/releases/download/" + tag + "/" + name
	return u.Path == path && u.EscapedPath() == path
}

func compareVersions(a, b string) int {
	left, right := strings.Split(a, "."), strings.Split(b, ".")
	for i := range left {
		x, _ := strconv.Atoi(left[i])
		y, _ := strconv.Atoi(right[i])
		if x < y {
			return -1
		}
		if x > y {
			return 1
		}
	}
	return 0
}

// Match field names exactly: encoding/json otherwise accepts case variants and
// null values for strings, which can make different clients read different data.
func exactSchema(raw []byte) error {
	var root map[string]json.RawMessage
	if err := json.Unmarshal(raw, &root); err != nil || !exactFields(root, "version", "release_tag", "issued_at", "notes", "artifacts") {
		return errors.New("invalid manifest fields")
	}
	var artifacts []json.RawMessage
	if err := json.Unmarshal(root["artifacts"], &artifacts); err != nil {
		return errors.New("invalid artifacts")
	}
	for _, rawArtifact := range artifacts {
		var item map[string]json.RawMessage
		if err := json.Unmarshal(rawArtifact, &item); err != nil || !exactFields(item, "id", "os", "arch", "kind", "name", "sha256", "size", "url") {
			return errors.New("invalid artifact fields")
		}
	}
	return nil
}

func exactFields(fields map[string]json.RawMessage, names ...string) bool {
	if len(fields) != len(names) {
		return false
	}
	for _, name := range names {
		value, ok := fields[name]
		if !ok || bytes.Equal(bytes.TrimSpace(value), []byte("null")) {
			return false
		}
	}
	return true
}

// json.Unmarshal accepts duplicate object keys; reject them at every nesting
// level to prevent different parsers from interpreting a signed file differently.
func rejectDuplicateKeys(raw []byte) error {
	decoder := json.NewDecoder(bytes.NewReader(raw))
	decoder.UseNumber()
	if err := walkJSON(decoder, 0); err != nil {
		return err
	}
	if _, err := decoder.Token(); err != io.EOF {
		if err == nil {
			return errors.New("trailing JSON data")
		}
		return err
	}
	return nil
}

func walkJSON(decoder *json.Decoder, depth int) error {
	if depth > 16 {
		return errors.New("JSON nesting too deep")
	}
	token, err := decoder.Token()
	if err != nil {
		return err
	}
	delim, ok := token.(json.Delim)
	if !ok {
		return nil
	}
	switch delim {
	case '{':
		seen := make(map[string]bool)
		for decoder.More() {
			field, err := decoder.Token()
			if err != nil {
				return err
			}
			key, ok := field.(string)
			if !ok || seen[key] {
				return errors.New("duplicate or invalid JSON field")
			}
			seen[key] = true
			if err := walkJSON(decoder, depth+1); err != nil {
				return err
			}
		}
		end, err := decoder.Token()
		if err != nil || end != json.Delim('}') {
			return errors.New("unterminated JSON object")
		}
	case '[':
		for decoder.More() {
			if err := walkJSON(decoder, depth+1); err != nil {
				return err
			}
		}
		end, err := decoder.Token()
		if err != nil || end != json.Delim(']') {
			return errors.New("unterminated JSON array")
		}
	default:
		return errors.New("invalid JSON delimiter")
	}
	return nil
}
