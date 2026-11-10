package app

import (
	"context"
	"encoding/base64"
	"encoding/json"
	"errors"
	"strings"
	"time"

	"github.com/bardia049b-stack/Gozar1/go-desktop/internal/fxa"
)

func explicitTokenExpiry(token string) (time.Time, bool) {
	parts := strings.Split(token, ".")
	if len(parts) < 2 {
		return time.Time{}, false
	}
	data, err := base64.RawURLEncoding.DecodeString(parts[1])
	if err != nil {
		return time.Time{}, false
	}
	var claims struct {
		Expires int64 `json:"exp"`
	}
	if json.Unmarshal(data, &claims) != nil || claims.Expires <= 0 || claims.Expires > 4102444800 {
		return time.Time{}, false
	}
	return time.Unix(claims.Expires, 0), true
}

func tokenExpiry(token string, now time.Time) time.Time {
	if expiry, ok := explicitTokenExpiry(token); ok {
		return expiry
	}
	return now.Add(4 * time.Minute)
}

// Renew five minutes before expiry whenever the lifetime permits it. A pass
// valid for five minutes or less cannot have a five-minute lead, so use half
// its remaining life rather than repeatedly hammering the service.
func renewalDelay(token string, now time.Time) time.Duration {
	remaining := tokenExpiry(token, now).Sub(now)
	if remaining < 0 {
		remaining = 0
	}
	delay := remaining / 2
	if remaining > 5*time.Minute+15*time.Second {
		delay = remaining - 5*time.Minute
	}
	if delay < 15*time.Second {
		return 15 * time.Second
	}
	if delay > time.Hour {
		return time.Hour
	}
	return delay
}

func (a *App) noteActivity() {
	a.lastActivity.Store(time.Now().UnixNano())
}

func (a *App) currentPass() (string, error) { return a.currentPassWith(a.passThrough) }

func (a *App) currentPassWith(request func(string) (*fxa.Pass, error)) (string, error) {
	a.passMu.Lock()
	defer a.passMu.Unlock()
	a.mu.Lock()
	pass, live := a.pass, a.live
	id, revision := a.cfg.ActiveAccount().ID(), a.sessionRevision
	token := ""
	if pass != nil {
		token = pass.Token
	}
	a.mu.Unlock()
	if token == "" {
		return "", errors.New("no Firefox proxy pass")
	}
	now := time.Now()
	expiry, known := explicitTokenExpiry(token)
	if !known || expiry.After(now.Add(2*time.Minute)) {
		return token, nil
	}
	// The engine can ask for a stream before connect() has published the
	// verified manager as live. Never switch to a direct renewal there.
	if !live {
		if expiry.After(now.Add(5 * time.Second)) {
			return token, nil
		}
		return "", errors.New("Firefox proxy pass expired")
	}
	fallback := func(err error) (string, error) {
		a.mu.Lock()
		defer a.mu.Unlock()
		if a.live && a.sessionRevision == revision && a.cfg.ActiveAccount().ID() == id &&
			a.pass != nil && a.pass.Token == token && expiry.After(time.Now().Add(5*time.Second)) {
			return token, nil
		}
		return "", err
	}
	access, err := a.accessWith(a.refreshThrough)
	if err != nil {
		return fallback(err)
	}
	fresh, err := request(access.token)
	if err != nil {
		return fallback(err)
	}
	if err := a.saveRenewedUsage(context.Background(), fresh, access); err != nil {
		return fallback(err)
	}
	a.noteActivity()
	return fresh.Token, nil
}

func (a *App) startRenewal() {
	ctx, cancel := context.WithCancel(context.Background())
	a.mu.Lock()
	if !a.live {
		a.mu.Unlock()
		cancel()
		return
	}
	old := a.renewCancel
	a.renewCancel = cancel
	a.mu.Unlock()
	if old != nil {
		old()
	}
	go a.renewPass(ctx)
}

func (a *App) renewPass(ctx context.Context) { a.renewPassWith(ctx, a.passThrough) }

func (a *App) renewPassWith(ctx context.Context, request func(string) (*fxa.Pass, error)) {
	var retry time.Duration
	lastSeen := a.lastActivity.Load()
	for {
		a.mu.Lock()
		if !a.live || a.pass == nil || a.pass.Token == "" {
			a.mu.Unlock()
			return
		}
		token := a.pass.Token
		a.mu.Unlock()
		delayFn := a.renewDelay
		if delayFn == nil {
			delayFn = renewalDelay
		}
		delay := delayFn(token, time.Now())
		if retry > 0 {
			delay = retry
		}
		timer := time.NewTimer(delay)
		select {
		case <-ctx.Done():
			timer.Stop()
			return
		case <-timer.C:
		}
		seen := a.lastActivity.Load()
		_, known := explicitTokenExpiry(token)
		// Opaque legacy tokens keep the old idle behavior; explicitly expiring
		// passes must renew while idle as well, or cold start may stop working.
		if seen == lastSeen && !known && retry == 0 {
			continue
		}
		lastSeen = seen
		a.maybeRefreshGeo(false)
		a.passMu.Lock()
		a.mu.Lock()
		stillCurrent := a.live && a.pass != nil && a.pass.Token == token
		a.mu.Unlock()
		if ctx.Err() != nil {
			a.passMu.Unlock()
			return
		}
		if !stillCurrent {
			a.passMu.Unlock()
			retry = 0
			continue
		}
		access, err := a.accessWith(a.refreshThrough)
		if err == nil {
			var fresh *fxa.Pass
			fresh, err = request(access.token)
			if err == nil {
				err = a.saveRenewedUsage(ctx, fresh, access)
			}
		}
		a.passMu.Unlock()
		if ctx.Err() != nil {
			return
		}
		if err != nil {
			if retry == 0 {
				retry = 15 * time.Second
			} else {
				retry *= 2
			}
			if retry > time.Minute {
				retry = time.Minute
			}
		} else {
			retry = 0
		}
	}
}

func (a *App) saveRenewedUsage(ctx context.Context, pass *fxa.Pass, access accountAccess) error {
	a.mu.Lock()
	defer a.mu.Unlock()
	if ctx.Err() != nil || !a.live {
		return context.Canceled
	}
	return a.saveUsageLocked(pass, access)
}
