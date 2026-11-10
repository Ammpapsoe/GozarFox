package app

import (
	"context"
	"errors"
	"io"
	"net/http"
	"strings"
	"time"

	"github.com/bardia049b-stack/Gozar1/go-desktop/internal/config"
	"github.com/bardia049b-stack/Gozar1/go-desktop/internal/fxa"
)

func persistablePass(pass *fxa.Pass, now time.Time) string {
	if pass == nil || len(pass.Token) == 0 || len(pass.Token) > 65536 || strings.ContainsAny(pass.Token, "\r\n\x00") {
		return ""
	}
	expiry, ok := explicitTokenExpiry(pass.Token)
	if !ok || !expiry.After(now.Add(5*time.Second)) {
		return ""
	}
	return pass.Token
}

func cachedAccountPass(account config.Account, now time.Time) *fxa.Pass {
	if account.ID() == "" || account.Refresh == "" || persistablePass(&fxa.Pass{Token: account.ProxyPass}, now) == "" {
		return nil
	}
	return &fxa.Pass{Token: account.ProxyPass, Limit: account.Limit, Remaining: account.Remaining, Reset: account.Reset}
}

// bootstrapPass only reuses an explicitly unexpired JWT belonging to the
// active, already authenticated account. The remote relay still verifies it.
func (a *App) bootstrapPass(now time.Time) *fxa.Pass {
	a.mu.Lock()
	defer a.mu.Unlock()
	if a.cfg.ActiveAccount().ID() == "" || a.pass == nil || a.pass.Token == "" {
		return nil
	}
	expiry, ok := explicitTokenExpiry(a.pass.Token)
	if !ok || !expiry.After(now.Add(90*time.Second)) {
		return nil
	}
	copy := *a.pass
	return &copy
}

// controlClient uses a pass snapshot to avoid recursively invoking currentPass
// while it is being renewed. Its stream is bound to the current account and
// transport generation, and the standard HTTP client verifies inner TLS.
func (a *App) controlClient() (*http.Client, func(), bool) {
	a.mu.Lock()
	transport, revision := a.transport, a.sessionRevision
	live := a.live
	token := ""
	if a.pass != nil {
		token = a.pass.Token
	}
	a.mu.Unlock()
	if !live || transport == nil || token == "" {
		return nil, func() {}, false
	}
	expiry, ok := explicitTokenExpiry(token)
	if !ok || !expiry.After(time.Now().Add(5*time.Second)) {
		return nil, func() {}, false
	}
	current := func() bool {
		a.mu.Lock()
		defer a.mu.Unlock()
		return a.live && a.transport == transport && a.sessionRevision == revision &&
			a.pass != nil && a.pass.Token == token && expiry.After(time.Now().Add(5*time.Second))
	}
	client, closeIdle := fxa.NewControlClient(func(ctx context.Context, host string, port int) (io.ReadWriteCloser, error) {
		if !current() || ctx.Err() != nil {
			return nil, errors.New("control tunnel stopped")
		}
		stream, err := transport.Open(host, port, token, 12*time.Second)
		if err != nil {
			return nil, err
		}
		if !current() || ctx.Err() != nil {
			_ = stream.Close()
			return nil, errors.New("control tunnel stopped")
		}
		return stream, nil
	})
	return client, closeIdle, true
}

func (a *App) passForControl(access string) (*fxa.Pass, error) {
	a.mu.Lock()
	live := a.live
	a.mu.Unlock()
	if !live {
		return a.requestPass(access)
	}
	return a.passThrough(access)
}

// A renewal already scoped to a tunnel never changes to a direct request,
// even if a concurrent disconnect occurs.
func (a *App) passThrough(access string) (*fxa.Pass, error) {
	client, closeIdle, ok := a.controlClient()
	if !ok {
		return nil, errors.New("Mozilla control tunnel is unavailable; sign in again on a trusted connection")
	}
	defer closeIdle()
	return fxa.PassTokenThrough(access, client)
}

func (a *App) refreshForControl(refresh string) (*fxa.Tokens, error) {
	a.mu.Lock()
	live := a.live
	a.mu.Unlock()
	if !live {
		return a.refreshToken(refresh)
	}
	return a.refreshThrough(refresh)
}

func (a *App) refreshThrough(refresh string) (*fxa.Tokens, error) {
	client, closeIdle, ok := a.controlClient()
	if !ok {
		return nil, errors.New("Mozilla control tunnel is unavailable; sign in again on a trusted connection")
	}
	defer closeIdle()
	return fxa.RefreshThrough(refresh, client)
}
