package update

import (
	"crypto/ed25519"
	"encoding/hex"
	"os"
	"os/exec"
	"path/filepath"
	"runtime"
	"strings"
	"testing"
	"time"
)

// Exercises the actual release producer against the client parser with a
// disposable key; no release secret or network is needed.
func TestDesktopReleaseProducerMatchesVerifier(t *testing.T) {
	if runtime.GOOS != "linux" {
		t.Skip("release signing runs on Linux CI")
	}
	dir := t.TempDir()
	key := filepath.Join(dir, "test-key.pem")
	if out, err := exec.Command("openssl", "genpkey", "-algorithm", "Ed25519", "-out", key).CombinedOutput(); err != nil {
		t.Fatalf("generate fixture key: %v: %s", err, out)
	}
	der, err := exec.Command("openssl", "pkey", "-in", key, "-pubout", "-outform", "DER").Output()
	if err != nil || len(der) != 44 || hex.EncodeToString(der[:12]) != "302a300506032b6570032100" {
		t.Fatalf("invalid fixture public key: %v", err)
	}
	public := ed25519.PublicKey(append([]byte(nil), der[12:]...))
	source, err := os.ReadFile(filepath.Join("..", "..", "..", "tools", "sign-desktop-manifest.sh"))
	if err != nil {
		t.Fatal(err)
	}
	if !strings.Contains(string(source), KeyHex) {
		t.Fatal("producer and verifier do not pin the same release key")
	}
	fixtureScript := filepath.Join(dir, "sign-fixture.sh")
	if err := os.WriteFile(fixtureScript, []byte(strings.Replace(string(source), KeyHex, hex.EncodeToString(public), 1)), 0600); err != nil {
		t.Fatal(err)
	}
	names := []string{
		"Gozar-Windows-x64-Setup.exe", "Gozar-Windows-x64.zip", "Gozar-Linux-amd64.deb",
		"Gozar-Linux-amd64.tar.xz", "Gozar-1.10.4-x86_64.AppImage", "Gozar-1.10.4-x86_64.flatpak",
		"Gozar_1.10.4_amd64.snap", "Gozar-Fedora-x86_64.rpm", "Gozar-RHEL-x86_64.rpm",
		"Gozar-1.10.4-x86_64.pkg.tar.zst", "Gozar-macOS-ARM.tar.xz", "Gozar-macOS-Intel.tar.xz",
	}
	for _, name := range names {
		if err := os.WriteFile(filepath.Join(dir, name), []byte("fixture for "+name), 0600); err != nil {
			t.Fatal(err)
		}
	}
	cmd := exec.Command("bash", fixtureScript, "1.10.4", dir, "release notes")
	cmd.Env = append(os.Environ(), "GOZAR_RELEASE_KEY="+key, "GOZAR_RELEASE_TAG=v1.11.1")
	if out, err := cmd.CombinedOutput(); err != nil {
		t.Fatalf("sign fixture: %v: %s", err, out)
	}
	raw, err := os.ReadFile(filepath.Join(dir, "desktop-update.json"))
	if err != nil {
		t.Fatal(err)
	}
	signature, err := os.ReadFile(filepath.Join(dir, "desktop-update.json.sig"))
	if err != nil {
		t.Fatal(err)
	}
	verified, err := verifyWithKey(raw, string(signature), "1.10.3", "v1.11.1", time.Now(), public)
	if err != nil || !verified.Newer() || len(verified.Manifest().Artifacts) != len(names) {
		t.Fatalf("producer output rejected by consumer: %v", err)
	}
}
