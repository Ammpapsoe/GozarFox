package update

import (
	"bytes"
	"crypto/ed25519"
	"crypto/rand"
	"encoding/base64"
	"encoding/json"
	"strings"
	"testing"
	"time"
)

var fixedNow = time.Unix(1793100000, 0)

func fixture() Manifest {
	return Manifest{
		Version: "1.10.5", ReleaseTag: "v1.11.1", IssuedAt: fixedNow.Unix(), Notes: "verified release",
		Artifacts: []Artifact{{
			ID: "linux-amd64-deb", OS: "linux", Arch: "amd64", Kind: "deb",
			Name: "Gozar-Linux-amd64.deb", SHA256: strings.Repeat("a", 64), Size: 100,
			URL: "https://github.com/Ammpapsoe/GozarFox/releases/download/v1.11.1/Gozar-Linux-amd64.deb",
		}},
	}
}

func signed(t *testing.T, m Manifest) ([]byte, string, ed25519.PublicKey) {
	t.Helper()
	public, private, err := ed25519.GenerateKey(rand.Reader)
	if err != nil {
		t.Fatal(err)
	}
	raw, err := json.Marshal(m)
	if err != nil {
		t.Fatal(err)
	}
	return raw, base64.StdEncoding.EncodeToString(ed25519.Sign(private, raw)), public
}

func verifyFixture(t *testing.T, m Manifest) (*Verified, error) {
	t.Helper()
	raw, sig, pub := signed(t, m)
	return verifyWithKey(raw, sig, "1.10.4", "v1.11.1", fixedNow, pub)
}

func TestValidManifestAndDefensiveCopies(t *testing.T) {
	verified, err := verifyFixture(t, fixture())
	if err != nil || !verified.Newer() {
		t.Fatalf("valid signed manifest rejected: %v", err)
	}
	copy := verified.Manifest()
	copy.Artifacts[0].URL = "https://evil.example/changed"
	if strings.Contains(verified.Manifest().Artifacts[0].URL, "evil.example") {
		t.Fatal("verified artifact was mutated through a returned copy")
	}
}

func TestAuthenticityBeforeCurrentAndRejectTampering(t *testing.T) {
	m := fixture()
	m.Version = "1.10.4"
	raw, sig, pub := signed(t, m)
	v, err := verifyWithKey(raw, sig, "1.10.4", "v1.11.1", fixedNow, pub)
	if err != nil || v.Newer() {
		t.Fatalf("authenticated current version: %v", err)
	}
	tampered := append(append([]byte(nil), raw...), ' ')
	if _, err = verifyWithKey(tampered, sig, "1.10.4", "v1.11.1", fixedNow, pub); err == nil {
		t.Fatal("accepted altered bytes")
	}
	if _, err = verifyWithKey(raw, "not a signature", "1.10.4", "v1.11.1", fixedNow, pub); err == nil {
		t.Fatal("accepted an unsigned current result")
	}
	other, _, _ := ed25519.GenerateKey(rand.Reader)
	if _, err = verifyWithKey(raw, sig, "1.10.4", "v1.11.1", fixedNow, other); err == nil {
		t.Fatal("accepted a different key")
	}
}

func TestRejectUnsafeSignedFields(t *testing.T) {
	cases := map[string]func(*Manifest){
		"old version":        func(m *Manifest) { m.Version = "1.10.3" },
		"invalid version":    func(m *Manifest) { m.Version = "1.10.5;bad" },
		"wrong release tag":  func(m *Manifest) { m.ReleaseTag = "v1.10.4" },
		"future timestamp":   func(m *Manifest) { m.IssuedAt = fixedNow.Unix() + 24*60*60 + 1 },
		"no artifacts":       func(m *Manifest) { m.Artifacts = nil },
		"bad hash":           func(m *Manifest) { m.Artifacts[0].SHA256 = strings.Repeat("Z", 64) },
		"empty size":         func(m *Manifest) { m.Artifacts[0].Size = 0 },
		"oversized artifact": func(m *Manifest) { m.Artifacts[0].Size = maxArtifactSize + 1 },
		"wrong platform":     func(m *Manifest) { m.Artifacts[0].OS = "other" },
		"bad architecture":   func(m *Manifest) { m.Artifacts[0].Arch = "unknown" },
		"unsafe filename":    func(m *Manifest) { m.Artifacts[0].Name = "../app.deb" },
		"wrong host": func(m *Manifest) {
			m.Artifacts[0].URL = strings.Replace(m.Artifacts[0].URL, "github.com", "evil.example", 1)
		},
		"wrong path tag": func(m *Manifest) { m.Artifacts[0].URL = strings.Replace(m.Artifacts[0].URL, "v1.11.1", "v1.10.4", 1) },
		"encoded path": func(m *Manifest) {
			m.Artifacts[0].URL = strings.Replace(m.Artifacts[0].URL, "Gozar-Linux", "%47ozar-Linux", 1)
		},
		"query": func(m *Manifest) { m.Artifacts[0].URL += "?token=1" },
		"userinfo": func(m *Manifest) {
			m.Artifacts[0].URL = strings.Replace(m.Artifacts[0].URL, "github.com", "user@github.com", 1)
		},
		"duplicate artifact": func(m *Manifest) { m.Artifacts = append(m.Artifacts, m.Artifacts[0]) },
	}
	for name, mutate := range cases {
		t.Run(name, func(t *testing.T) {
			m := fixture()
			mutate(&m)
			if _, err := verifyFixture(t, m); err == nil {
				t.Fatal("unsafe signed manifest was accepted")
			}
		})
	}
}

func TestRejectAmbiguousJSONDespiteValidSignature(t *testing.T) {
	public, private, _ := ed25519.GenerateKey(rand.Reader)
	original, _ := json.Marshal(fixture())
	cases := [][]byte{
		bytes.Replace(original, []byte(`"version":"1.10.5"`), []byte(`"version":"1.10.5","version":"9.9.9"`), 1),
		bytes.Replace(original, []byte(`"version":"1.10.5"`), []byte(`"Version":"1.10.5"`), 1),
		bytes.Replace(original, []byte(`"notes":"verified release"`), []byte(`"notes":null`), 1),
		bytes.Replace(original, []byte(`"size":100`), []byte(`"size":"100"`), 1),
		append(append([]byte(nil), original...), []byte(` {}`)...),
	}
	for index, raw := range cases {
		if bytes.Equal(raw, original) {
			t.Fatalf("fixture replacement %d did not apply", index)
		}
		sig := base64.StdEncoding.EncodeToString(ed25519.Sign(private, raw))
		if _, err := verifyWithKey(raw, sig, "1.10.4", "v1.11.1", fixedNow, public); err == nil {
			t.Fatalf("accepted ambiguous signed JSON case %d", index)
		}
	}
}

func TestBoundsAndReleaseBinding(t *testing.T) {
	raw, sig, pub := signed(t, fixture())
	if _, err := verifyWithKey(raw, sig, "1.10.4", "v1.11.0", fixedNow, pub); err == nil {
		t.Fatal("accepted a manifest from the wrong release")
	}
	if _, err := verifyWithKey(raw, sig, "nonsense", "v1.11.1", fixedNow, pub); err == nil {
		t.Fatal("accepted malformed installed version")
	}
	if _, err := verifyWithKey(bytes.Repeat([]byte("x"), maxManifestBytes+1), sig, "1.10.4", "v1.11.1", fixedNow, pub); err == nil {
		t.Fatal("accepted oversized manifest")
	}
}
