package config

import (
	"bytes"
	"os"
	"path/filepath"
	"runtime"
	"testing"
)

func TestWarpIdentityEncryptedAndReplaceable(t *testing.T) {
	sandbox(t)
	secret := []byte(`{"token":"private-token","private_key":"private-device-key"}`)
	if _, err := LoadWarpIdentity(); !os.IsNotExist(err) {
		t.Fatalf("missing identity: %v", err)
	}
	if err := SaveWarpIdentity(secret); err != nil {
		t.Fatal(err)
	}
	path := filepath.Join(Dir(), warpIdentityFile)
	stored, err := os.ReadFile(path)
	if err != nil {
		t.Fatal(err)
	}
	if bytes.Contains(stored, []byte("private-token")) || bytes.Contains(stored, []byte("private-device-key")) {
		t.Fatal("identity stored in plaintext")
	}
	if runtime.GOOS != "windows" {
		stat, err := os.Stat(path)
		if err != nil {
			t.Fatal(err)
		}
		if stat.Mode().Perm() != 0o600 {
			t.Fatalf("identity permissions %v", stat.Mode())
		}
	}
	loaded, err := LoadWarpIdentity()
	if err != nil || !bytes.Equal(loaded, secret) {
		t.Fatal("identity did not round trip")
	}
	replacement := []byte(`{"token":"replacement"}`)
	if err := SaveWarpIdentity(replacement); err != nil {
		t.Fatal(err)
	}
	loaded, err = LoadWarpIdentity()
	if err != nil || !bytes.Equal(loaded, replacement) {
		t.Fatal("replacement did not round trip")
	}
}

func TestWarpIdentityRejectsCorruptionAndPlaintext(t *testing.T) {
	sandbox(t)
	if err := SaveWarpIdentity(nil); err == nil {
		t.Fatal("empty identity accepted")
	}
	path := filepath.Join(Dir(), warpIdentityFile)
	for _, invalid := range []string{`{"token":"plaintext"}`, sealedPrefix + "broken!"} {
		if err := os.WriteFile(path, []byte(invalid), 0o600); err != nil {
			t.Fatal(err)
		}
		if _, err := LoadWarpIdentity(); err == nil {
			t.Fatal("invalid identity accepted")
		}
	}
}
