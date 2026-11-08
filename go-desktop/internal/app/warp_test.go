package app

import (
	"context"
	"testing"

	"github.com/bardia049b-stack/Gozar1/go-desktop/internal/config"
)

func TestWarpSettingRequiresConsentAndPersists(t *testing.T) {
	root := t.TempDir()
	t.Setenv("HOME", root)
	t.Setenv("XDG_CONFIG_HOME", root)
	t.Setenv("APPDATA", root)
	current := New("test")
	defer current.Close()
	if current.State().WarpUDP {
		t.Fatal("WARP must default off")
	}
	if err := current.SetWarpUDP(true, false); err == nil {
		t.Fatal("enabled without explicit consent")
	}
	if current.State().WarpUDP || config.Load().WarpUDP {
		t.Fatal("failed consent changed setting")
	}
	if err := current.SetWarpUDP(true, true); err != nil {
		t.Fatal(err)
	}
	if !current.State().WarpUDP || !config.Load().WarpUDP {
		t.Fatal("enabled setting not persisted")
	}
	if err := current.SetWarpUDP(false, false); err != nil {
		t.Fatal(err)
	}
	if current.State().WarpUDP || config.Load().WarpUDP {
		t.Fatal("disabled setting not persisted")
	}
}

func TestCancelConnectionAttemptCancelsPendingAndActiveContexts(t *testing.T) {
	pendingCtx, pendingCancel := context.WithCancel(context.Background())
	activeCtx, activeCancel := context.WithCancel(context.Background())
	current := &App{connectCancel: pendingCancel, activeCancel: activeCancel}
	current.cancelConnectionAttempt()
	if pendingCtx.Err() == nil || activeCtx.Err() == nil {
		t.Fatal("connection contexts not cancelled")
	}
}
