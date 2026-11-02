package config

import (
	"os"
	"path/filepath"
	"runtime"
	"strings"
	"testing"
)

func sandbox(t *testing.T) string {
	t.Helper()
	dir := t.TempDir()
	switch runtime.GOOS {
	case "windows":
		t.Setenv("AppData", dir)
	case "darwin":
		t.Setenv("HOME", dir)
	default:
		t.Setenv("XDG_CONFIG_HOME", dir)
	}
	return filepath.Join(Dir(), "config.json")
}

func TestTokensAreNotWrittenInPlaintext(t *testing.T) {
	target := sandbox(t)
	saved := Config{Email: "person@example.test", UID: "uid", Access: "access-token-value", Refresh: "refresh-token-value", FullTunnel: true, Port: 10810, Theme: "system"}
	if err := Save(saved); err != nil {
		t.Fatal(err)
	}
	raw, err := os.ReadFile(target)
	if err != nil {
		t.Fatal(err)
	}
	text := string(raw)
	for _, secret := range []string{"access-token-value", "refresh-token-value"} {
		if strings.Contains(text, secret) {
			t.Fatalf("config.json still exposes %q", secret)
		}
	}
	loaded := Load()
	if loaded.Refresh != "refresh-token-value" || loaded.Access != "access-token-value" {
		t.Fatalf("tokens did not round-trip: %+v", loaded.ActiveAccount())
	}
}

func TestLegacyPlaintextConfigKeepsWorking(t *testing.T) {
	target := sandbox(t)
	legacy := `{"email":"old@example.test","uid":"old","access":"plain-access","refresh":"plain-refresh","port":10810,"theme":"system"}`
	if err := os.WriteFile(target, []byte(legacy), 0o600); err != nil {
		t.Fatal(err)
	}
	loaded := Load()
	if loaded.Refresh != "plain-refresh" || loaded.Email != "old@example.test" {
		t.Fatalf("legacy session was dropped: %+v", loaded.ActiveAccount())
	}
	if err := Save(loaded); err != nil {
		t.Fatal(err)
	}
	raw, err := os.ReadFile(target)
	if err != nil {
		t.Fatal(err)
	}
	if strings.Contains(string(raw), "plain-refresh") {
		t.Fatal("saving did not migrate the legacy token to sealed storage")
	}
}

func TestSealingIsStableAndIdempotent(t *testing.T) {
	sandbox(t)
	once := seal("token")
	if !strings.HasPrefix(once, sealedPrefix) {
		t.Fatalf("expected a sealed payload, got %q", once)
	}
	if seal(once) != once {
		t.Fatal("sealing an already sealed value must not wrap it again")
	}
	if unseal(once) != "token" {
		t.Fatal("sealed value did not open")
	}
	if unseal(sealedPrefix+"broken") != "" {
		t.Fatal("corrupted payload must fail closed")
	}
}
