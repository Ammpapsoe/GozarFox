package config

import (
	"encoding/json"
	"os"
	"path/filepath"
	"testing"
)

func TestConnectionModeLegacyMigrationAndPersistence(t *testing.T) {
	root := t.TempDir()
	t.Setenv("HOME", root)
	t.Setenv("XDG_CONFIG_HOME", root)
	t.Setenv("APPDATA", root)
	file := filepath.Join(Dir(), "config.json")
	for _, legacy := range []string{`{"full_tunnel":true}`, `{"full_tunnel":false}`, `{}`} {
		if err := os.WriteFile(file, []byte(legacy), 0o600); err != nil {
			t.Fatal(err)
		}
		if got := Load().Mode; got != ModeTUN {
			t.Fatalf("legacy %s migrated to %q", legacy, got)
		}
	}
	for _, mode := range []ConnectionMode{ModeTUN, ModeProxyOnly, ModeSystemProxy} {
		if err := Save(Config{Mode: mode}); err != nil {
			t.Fatal(err)
		}
		if got := Load().Mode; got != mode {
			t.Fatalf("saved %q but loaded %q", mode, got)
		}
		data, err := os.ReadFile(file)
		if err != nil {
			t.Fatal(err)
		}
		var encoded map[string]json.RawMessage
		if err := json.Unmarshal(data, &encoded); err != nil {
			t.Fatal(err)
		}
		if _, exists := encoded["full_tunnel"]; exists {
			t.Fatal("legacy bool still written")
		}
	}
	// New choice wins even when old software left a boolean alongside it.
	if err := os.WriteFile(file, []byte(`{"connection_mode":"proxy-only","full_tunnel":true}`), 0o600); err != nil {
		t.Fatal(err)
	}
	if got := Load().Mode; got != ModeProxyOnly {
		t.Fatalf("new mode lost in migration: %q", got)
	}
}

func TestConnectionModeUnknownCannotBeSavedOrSilentlyReinterpreted(t *testing.T) {
	root := t.TempDir()
	t.Setenv("HOME", root)
	t.Setenv("XDG_CONFIG_HOME", root)
	t.Setenv("APPDATA", root)
	file := filepath.Join(Dir(), "config.json")
	if err := os.WriteFile(file, []byte(`{"connection_mode":"future-mode"}`), 0o600); err != nil {
		t.Fatal(err)
	}
	if got := Load().Mode; got != "future-mode" {
		t.Fatalf("unknown mode silently changed: %q", got)
	}
	if err := Save(Config{Mode: "future-mode"}); err == nil {
		t.Fatal("invalid mode saved")
	}
	for _, raw := range []string{`null`, `""`, `123`, `false`, `{}`} {
		if err := os.WriteFile(file, []byte(`{"connection_mode":`+raw+`}`), 0o600); err != nil {
			t.Fatal(err)
		}
		if got := Load().Mode; got.Validate() == nil {
			t.Fatalf("invalid explicit mode %s became valid %q", raw, got)
		}
	}
}
