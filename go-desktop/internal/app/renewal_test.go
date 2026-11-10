package app

import (
	"context"
	"encoding/base64"
	"fmt"
	"testing"
	"time"

	"github.com/bardia049b-stack/Gozar1/go-desktop/internal/fxa"
)

func TestPassRenewalTimingAndCurrentToken(t *testing.T) {
	now := time.Unix(1800000000, 0)
	token := func(seconds int64) string {
		return "header." + base64.RawURLEncoding.EncodeToString([]byte(fmt.Sprintf(`{"exp":%d}`, seconds))) + ".signature"
	}
	if got := renewalDelay(token(now.Add(10*time.Minute).Unix()), now); got != 5*time.Minute {
		t.Fatal(got)
	}
	if got := renewalDelay(token(now.Add(3*time.Minute).Unix()), now); got != 90*time.Second {
		t.Fatal(got)
	}
	if got := renewalDelay(token(now.Add(-time.Second).Unix()), now); got != 15*time.Second {
		t.Fatal(got)
	}
	a := &App{pass: &fxa.Pass{Token: "old"}}
	if got, _ := a.currentPass(); got != "old" {
		t.Fatal(got)
	}
	a.pass = &fxa.Pass{Token: "new"}
	if got, _ := a.currentPass(); got != "new" {
		t.Fatal(got)
	}
}

func TestCancelledRenewalCannotOverwritePassAfterDisconnect(t *testing.T) {
	a := accountApp(t)
	addAccount(t, a, "sample@example.test", "one", "one", 800)
	a.mu.Lock()
	access := accountAccess{token: a.cfg.Access, id: a.cfg.ActiveAccount().ID(), revision: a.sessionRevision}
	a.live = true
	old := a.pass.Token
	a.mu.Unlock()
	ctx, cancel := context.WithCancel(context.Background())
	cancel()
	if err := a.saveRenewedUsage(ctx, &fxa.Pass{Token: "stale"}, access); err == nil {
		t.Fatal("cancelled renewal changed the session")
	}
	if got, _ := a.currentPass(); got != old {
		t.Fatal(got)
	}
	a.mu.Lock()
	a.live = false
	a.mu.Unlock()
	if err := a.saveRenewedUsage(context.Background(), &fxa.Pass{Token: "stale"}, access); err == nil {
		t.Fatal("disconnected renewal changed the session")
	}
}
