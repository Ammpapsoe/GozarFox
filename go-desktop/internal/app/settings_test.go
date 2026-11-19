package app

import (
	"os"
	"path/filepath"
	"reflect"
	"testing"

	"github.com/Ammpapsoe/GozarFox/go-desktop/internal/config"
)

func TestSettingsPersistAndRejectInvalidChangesWithoutConnecting(t *testing.T) {
	root := t.TempDir()
	t.Setenv("HOME", root)
	t.Setenv("XDG_CONFIG_HOME", root)
	t.Setenv("APPDATA", root)
	current := New("test")
	defer current.Close()
	if err := current.SetTheme("light"); err != nil {
		t.Fatal(err)
	}
	if err := current.SetRecents(true); err != nil {
		t.Fatal(err)
	}
	if err := current.SetChain([]string{"127.0.0.1:1080", "http://second.example:8080"}); err != nil {
		t.Fatal(err)
	}
	state := current.State()
	if state.Theme != "light" || !state.Recents || !reflect.DeepEqual(state.Chain, []string{"socks5://127.0.0.1:1080", "http://second.example:8080"}) {
		t.Fatalf("wrong state: %#v", state)
	}
	loaded := config.Load()
	if loaded.Theme != state.Theme || !loaded.Recents || !reflect.DeepEqual(loaded.Chain, state.Chain) {
		t.Fatal("settings did not persist")
	}
	if err := current.SetTheme("purple"); err == nil {
		t.Fatal("accepted unknown theme")
	}
	if err := current.SetChain([]string{"http://alice:password@example.org:80"}); err == nil {
		t.Fatal("accepted a proxy with credentials")
	}
	if !reflect.DeepEqual(current.State().Chain, state.Chain) {
		t.Fatal("invalid chain changed the active setting")
	}
	current.mu.Lock()
	current.live = true
	current.mu.Unlock()
	if err := current.SetChain([]string{}); err == nil {
		t.Fatal("changed proxy chain while connected")
	}
	current.mu.Lock()
	current.live = false
	current.cfg.LastServer = "recent.example"
	current.mu.Unlock()
	if err := current.SetRecents(false); err != nil {
		t.Fatal(err)
	}
	if current.State().LastServer != "" || config.Load().LastServer != "" {
		t.Fatal("turning off recents should forget the last server")
	}
}

func TestOldInactiveNetworkFieldsDoNotTurnOnDuringMigration(t *testing.T) {
	root := t.TempDir()
	t.Setenv("HOME", root)
	t.Setenv("XDG_CONFIG_HOME", root)
	t.Setenv("APPDATA", root)
	old := []byte(`{"language":"fa","full_tunnel":true,"bypass_lan":true,"chain":["socks5://127.0.0.1:1080"]}`)
	if err := os.WriteFile(filepath.Join(config.Dir(), "config.json"), old, 0o600); err != nil {
		t.Fatal(err)
	}
	loaded := config.Load()
	if loaded.Language != "fa" || loaded.BypassLAN || len(loaded.Chain) != 0 || loaded.SettingsVersion != 1 {
		t.Fatalf("unsafe migration: %#v", loaded)
	}
}
