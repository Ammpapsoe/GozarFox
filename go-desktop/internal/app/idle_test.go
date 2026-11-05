package app

import (
	"context"
	"encoding/base64"
	"strconv"
	"sync/atomic"
	"testing"
	"time"

	"github.com/bardia049b-stack/Gozar1/go-desktop/internal/config"
	"github.com/bardia049b-stack/Gozar1/go-desktop/internal/fxa"
)

func TestIdleRenewalSendsNoRequests(t *testing.T) {
	root := t.TempDir()
	t.Setenv("HOME", root)
	t.Setenv("XDG_CONFIG_HOME", root)
	t.Setenv("APPDATA", root)
	var calls atomic.Int64
	a := &App{
		cfg:        config.Config{Email: "idle@example.test", UID: "idle", Access: "access"},
		pass:       &fxa.Pass{Token: "old"},
		live:       true,
		requestPass: func(string) (*fxa.Pass, error) { calls.Add(1); return &fxa.Pass{Token: "new"}, nil },
		renewDelay: func(string, time.Time) time.Duration { return 5 * time.Millisecond },
	}
	a.lastActivity.Store(time.Now().Add(-time.Hour).UnixNano())
	ctx, cancel := context.WithTimeout(context.Background(), 80*time.Millisecond)
	defer cancel()
	a.renewPass(ctx)
	if calls.Load() != 0 {
		t.Fatalf("idle renewal made %d requests", calls.Load())
	}
}

func TestActiveRenewalRefreshesAndLazyPassRenewsNearExpiry(t *testing.T) {
	root := t.TempDir()
	t.Setenv("HOME", root)
	t.Setenv("XDG_CONFIG_HOME", root)
	t.Setenv("APPDATA", root)
	var calls atomic.Int64
	a := &App{
		cfg:        config.Config{Email: "active@example.test", UID: "active", Access: "access"},
		pass:       &fxa.Pass{Token: "old"},
		live:       true,
		requestPass: func(string) (*fxa.Pass, error) { calls.Add(1); return &fxa.Pass{Token: "new"}, nil },
		renewDelay: func(string, time.Time) time.Duration { return 5 * time.Millisecond },
	}
	a.lastActivity.Store(time.Now().UnixNano())
	go func() {
		time.Sleep(20 * time.Millisecond)
		a.noteActivity()
	}()
	ctx, cancel := context.WithTimeout(context.Background(), 80*time.Millisecond)
	defer cancel()
	a.renewPass(ctx)
	if calls.Load() == 0 {
		t.Fatal("active renewal did not refresh")
	}
	a.pass = &fxa.Pass{Token: "header." + base64URL(`{"exp":` + itoa(time.Now().Add(30*time.Second).Unix()) + `}`) + ".sig"}
	calls.Store(0)
	token, err := a.currentPass()
	if err != nil || token != "new" || calls.Load() != 1 {
		t.Fatalf("lazy pass renewal token=%q err=%v calls=%d", token, err, calls.Load())
	}
}

func base64URL(s string) string { return base64.RawURLEncoding.EncodeToString([]byte(s)) }
func itoa(v int64) string { return strconv.FormatInt(v, 10) }
