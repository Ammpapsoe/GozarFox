package app

import (
	"context"
	"encoding/json"
	"errors"
	"io"
	"os"
	"time"

	"github.com/bardia049b-stack/Gozar1/go-desktop/internal/config"
	"github.com/bardia049b-stack/Gozar1/go-desktop/internal/tunnel"
	"github.com/bardia049b-stack/Gozar1/go-desktop/internal/warp"
)

// Direct host routes apply to every IP protocol, not only to the TCP flow
// that installed them. WARP must not coexist with those routes.
func allowSplitWithWarp(split, warpEnabled bool) bool { return split && !warpEnabled }

// SetWarpUDP persists opt-in only after an explicit Cloudflare terms consent.
// A live connection is restarted so its TUN MTU and UDP backend change together.
func (a *App) SetWarpUDP(enabled, consent bool) error {
	if enabled && !consent {
		return errors.New("Cloudflare WARP terms must be accepted")
	}
	a.connectionMu.Lock()
	defer a.connectionMu.Unlock()
	a.mu.Lock()
	if a.cfg.WarpUDP == enabled {
		a.mu.Unlock()
		return nil
	}
	wasLive := a.live
	next := a.cfg
	next.WarpUDP = enabled
	if err := config.Save(next); err != nil {
		a.mu.Unlock()
		return err
	}
	a.cfg = next
	a.mu.Unlock()
	if wasLive {
		a.teardown()
		return a.connectLocked()
	}
	return nil
}

func saveWarpDevice(identity *warp.Identity) error {
	raw, err := json.Marshal(identity)
	if err != nil {
		return err
	}
	return config.SaveWarpIdentity(raw)
}

func (a *App) startWarp(ctx context.Context, manager *tunnel.Manager) (*warp.Auto, error) {
	// The only underlay used here is an authenticated Mozilla CONNECT. No
	// registration or endpoint probe is sent directly around the VPN.
	dial := func(ctx context.Context, host string, port int) (io.ReadWriteCloser, error) {
		pass, err := a.currentPass()
		if err != nil {
			return nil, err
		}
		type result struct {
			stream io.ReadWriteCloser
			err    error
		}
		done := make(chan result, 1)
		go func() {
			stream, err := manager.Open(host, port, pass, 12*time.Second)
			done <- result{stream, err}
		}()
		select {
		case r := <-done:
			if ctx.Err() != nil {
				if r.stream != nil {
					_ = r.stream.Close()
				}
				return nil, ctx.Err()
			}
			if r.err != nil {
				return nil, r.err
			}
			return r.stream, nil
		case <-ctx.Done():
			// If the upstream open completes after cancellation, revoke it.
			go func() {
				r := <-done
				if r.stream != nil {
					_ = r.stream.Close()
				}
			}()
			return nil, ctx.Err()
		}
	}
	client := warp.HTTPClient(dial)
	defer client.CloseIdleConnections()
	identity := &warp.Identity{}
	raw, err := config.LoadWarpIdentity()
	switch {
	case errors.Is(err, os.ErrNotExist):
		registerCtx, cancel := context.WithTimeout(ctx, 60*time.Second)
		defer cancel()
		// SetWarpUDP persists true only following a terms confirmation.
		identity, err = warp.Register(registerCtx, client, true, saveWarpDevice)
		if err != nil {
			return nil, err
		}
	case err != nil:
		return nil, errors.New("could not unlock WARP device identity")
	default:
		if len(raw) == 0 || json.Unmarshal(raw, identity) != nil {
			return nil, errors.New("invalid saved WARP device identity")
		}
		if !identity.Ready() {
			enrollCtx, cancel := context.WithTimeout(ctx, 30*time.Second)
			err = identity.Enroll(enrollCtx, client, saveWarpDevice)
			cancel()
			if err != nil {
				return nil, err
			}
		}
	}
	// Auto is closed with the VPN. Its initial scan tests real UDP delivery.
	return warp.NewAuto(ctx, identity, dial)
}
