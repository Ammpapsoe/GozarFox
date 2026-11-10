package app

import (
	"context"
	"encoding/base64"
	"fmt"
	"os"
	"path/filepath"
	"strings"
	"sync/atomic"
	"testing"
	"time"

	"github.com/bardia049b-stack/Gozar1/go-desktop/internal/config"
	"github.com/bardia049b-stack/Gozar1/go-desktop/internal/fxa"
)

func syntheticPass(expiry time.Time) string {
	payload := base64.RawURLEncoding.EncodeToString([]byte(fmt.Sprintf(`{"exp":%d}`, expiry.Unix())))
	return "header." + payload + ".synthetic-signature"
}

func TestEncryptedAccountBoundPassCanBootstrapAfterRestart(t *testing.T) {
	a := accountApp(t)
	now := time.Now()
	token := syntheticPass(now.Add(15 * time.Minute))
	account := config.Account{Email: "first@example.invalid", UID: "first", Access: "access", Refresh: "refresh", ExpiresAt: now.Add(time.Hour).Unix()}
	if err := a.commitAccount(account, &fxa.Pass{Token: token, Limit: 10, Remaining: 7}, 0, 0); err != nil {
		t.Fatal(err)
	}
	data, err := os.ReadFile(filepath.Join(config.Dir(), "config.json"))
	if err != nil {
		t.Fatal(err)
	}
	if strings.Contains(string(data), token) {
		t.Fatal("pass persisted as plaintext")
	}
	restarted := New("test")
	defer restarted.Close()
	pass := restarted.bootstrapPass(now)
	if pass == nil || pass.Token != token || pass.Remaining != 7 {
		t.Fatalf("same-account cache did not restore: signed=%v pass-present=%v account-pass-length=%d", restarted.cfg.SignedIn(), restarted.pass != nil, len(restarted.cfg.ActiveAccount().ProxyPass))
	}
	if restarted.bootstrapPass(now.Add(15*time.Minute)) != nil {
		t.Fatal("expired pass bootstrapped")
	}
	addAccount(t, restarted, "second@example.invalid", "second", "second", 3)
	if restarted.bootstrapPass(now) != nil {
		t.Fatal("another account inherited the old pass")
	}
	if err := restarted.SwitchAccount("uid:first"); err != nil {
		t.Fatal(err)
	}
	if restored := restarted.bootstrapPass(now); restored == nil || restored.Token != token {
		t.Fatal("saved account did not restore its own pass")
	}
}

func TestNoDirectFallbackAndValidOldPassSurvivesRenewalFailure(t *testing.T) {
	a := accountApp(t)
	addAccount(t, a, "person@example.invalid", "person", "person", 7)
	var direct atomic.Int32
	a.requestPass = func(string) (*fxa.Pass, error) { direct.Add(1); return nil, fmt.Errorf("outside") }
	a.mu.Lock()
	a.live = true
	old := syntheticPass(time.Now().Add(time.Minute))
	a.pass = &fxa.Pass{Token: old}
	a.mu.Unlock()
	if _, err := a.passThrough("access"); err == nil || direct.Load() != 0 {
		t.Fatal("tunnel failure fell back to direct")
	}
	got, err := a.currentPassWith(func(string) (*fxa.Pass, error) { return nil, fmt.Errorf("simulated renewal failure") })
	if err != nil || got != old {
		t.Fatal("valid pass discarded after temporary renewal error")
	}
	a.mu.Lock()
	a.pass = &fxa.Pass{Token: syntheticPass(time.Now().Add(-time.Second))}
	a.mu.Unlock()
	if _, err := a.currentPassWith(func(string) (*fxa.Pass, error) { return nil, fmt.Errorf("failed") }); err == nil {
		t.Fatal("expired pass accepted after renewal failure")
	}
	a.mu.Lock()
	a.live = false
	a.mu.Unlock()
}

func TestIdleExplicitPassIsRenewedAndCancelledResultIsNotSaved(t *testing.T) {
	a := accountApp(t)
	addAccount(t, a, "person@example.invalid", "person", "person", 7)
	old := syntheticPass(time.Now().Add(8 * time.Minute))
	a.mu.Lock()
	a.pass = &fxa.Pass{Token: old}
	a.live = true
	a.mu.Unlock()
	a.lastActivity.Store(time.Now().Add(-time.Hour).UnixNano())
	a.renewDelay = func(string, time.Time) time.Duration { return time.Millisecond }
	ctx, cancel := context.WithCancel(context.Background())
	var calls atomic.Int32
	a.renewPassWith(ctx, func(string) (*fxa.Pass, error) {
		calls.Add(1)
		cancel()
		return &fxa.Pass{Token: syntheticPass(time.Now().Add(15 * time.Minute))}, nil
	})
	if calls.Load() != 1 {
		t.Fatal("idle explicit pass was not renewed")
	}
	a.mu.Lock()
	defer a.mu.Unlock()
	if a.pass.Token != old {
		t.Fatal("cancelled renewal changed pass")
	}
	a.live = false
}
