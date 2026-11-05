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

func tokenExpiry(token string, now time.Time) time.Time {
	expiry := now.Add(4 * time.Minute)
	parts := strings.Split(token, ".")
	if len(parts) >= 2 {
		if data, err := base64.RawURLEncoding.DecodeString(parts[1]); err == nil {
			var claims struct {
				Expires int64 `json:"exp"`
			}
			if json.Unmarshal(data, &claims) == nil && claims.Expires > 0 && claims.Expires <= 4102444800 {
				expiry = time.Unix(claims.Expires, 0)
			}
		}
	}
	return expiry
}

func renewalDelay(token string, now time.Time) time.Duration {
	delay := tokenExpiry(token, now).Sub(now) - 2*time.Minute
	if delay < 15*time.Second {
		return 15 * time.Second
	}
	if delay > 4*time.Minute {
		return 4 * time.Minute
	}
	return delay
}

func (a *App) noteActivity() {
	a.lastActivity.Store(time.Now().UnixNano())
}

func (a *App) currentPass() (string, error) {
	a.passMu.Lock()
	defer a.passMu.Unlock()
	a.mu.Lock()
	pass := a.pass
	a.mu.Unlock()
	if pass == nil || pass.Token == "" {
		return "", errors.New("no Firefox proxy pass")
	}
	now := time.Now()
	if tokenExpiry(pass.Token, now).After(now.Add(2 * time.Minute)) {
		return pass.Token, nil
	}
	access, err := a.access()
	if err != nil {
		return "", err
	}
	fresh, err := a.requestPass(access.token)
	if err != nil {
		return "", err
	}
	if err := a.saveRenewedUsage(context.Background(), fresh, access); err != nil {
		return "", err
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

func (a *App) renewPass(ctx context.Context) {
	var retry time.Duration
	lastSeen := a.lastActivity.Load()
	for {
		token, err := a.currentPass()
		if err != nil {
			return
		}
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
		if seen == lastSeen {
			retry = 0
			continue
		}
		lastSeen = seen
		a.maybeRefreshGeo(false)
		access, err := a.access()
		if err == nil {
			var passErr error
			pass, passErr := a.requestPass(access.token)
			err = passErr
			if err == nil {
				err = a.saveRenewedUsage(ctx, pass, access)
			}
		}
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
