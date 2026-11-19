package app

import (
	"context"
	"errors"
	"io"
	"time"

	"github.com/Ammpapsoe/GozarFox/go-desktop/internal/config"
	"github.com/Ammpapsoe/GozarFox/go-desktop/internal/localproxy"
	"github.com/Ammpapsoe/GozarFox/go-desktop/internal/tunnel"
)

// startLocalProxy takes ownership of the verified relay, even on error. It is
// called with connectionMu held and never touches system proxy or TUN APIs.
func (a *App) startLocalProxy(ctx context.Context, dial localproxy.Dialer, chosen pathCandidate, transport *tunnel.Manager, token string) error {
	a.mu.Lock()
	port := a.cfg.Port
	a.mu.Unlock()
	server, err := localproxy.Listen(port, dial)
	if err != nil {
		if transport != nil {
			transport.Close()
		}
		return err
	}
	a.mu.Lock()
	systemMode := a.cfg.Mode == config.ModeSystemProxy
	a.mu.Unlock()
	var systemSession io.Closer
	if systemMode {
		if a.systemProxyStart == nil {
			_ = server.Close()
			if transport != nil {
				transport.Close()
			}
			return errors.New("system proxy mode is not implemented yet")
		}
		systemSession, err = a.systemProxyStart(ctx, server.Port())
		if err == nil && systemSession == nil {
			err = errors.New("system proxy session was not created")
		}
		if err != nil {
			if systemSession != nil {
				err = errors.Join(err, systemSession.Close())
			}
			_ = server.Close()
			if transport != nil {
				transport.Close()
			}
			a.mu.Lock()
			a.systemProxyFault = err
			a.mu.Unlock()
			return err
		}
	}
	if err := ctx.Err(); err != nil {
		if systemSession != nil {
			if closeErr := systemSession.Close(); closeErr != nil {
				a.mu.Lock()
				a.systemProxyFault = closeErr
				a.mu.Unlock()
				err = errors.Join(err, closeErr)
			}
		}
		_ = server.Close()
		if transport != nil {
			transport.Close()
		}
		return err
	}
	a.mu.Lock()
	a.systemProxy = systemSession
	a.proxy = server
	a.transport = transport
	a.activeServer = chosen.place
	a.live = true
	a.since = time.Now().Unix()
	a.lastActivity.Store(time.Now().UnixNano())
	a.geoRefresh.Store(time.Now().UnixNano())
	if a.cfg.Recents && chosen.target.Host != "" {
		a.cfg.LastServer = chosen.target.Host
		_ = config.Save(a.cfg)
	}
	a.stage = ""
	a.mu.Unlock()
	go a.watchLocalProxy(server)
	if transport != nil {
		a.startRenewal()
		go a.refreshServersThroughTunnel()
		go a.maybeRefreshGeo(true)
		// The probe is advisory; never delay connect or let a previous probe
		// overwrite the state of a subsequent connection.
		go func() {
			ip, err := transport.Probe(token, 12*time.Second)
			if err != nil {
				return
			}
			a.mu.Lock()
			if a.live && a.proxy == server && a.transport == transport {
				a.exit = ip
			}
			a.mu.Unlock()
		}()
	}
	return nil
}

func (a *App) watchLocalProxy(server *localproxy.Server) {
	err, ok := <-server.Failures()
	if !ok || err == nil {
		return
	}
	a.connectionMu.Lock()
	defer a.connectionMu.Unlock()
	a.mu.Lock()
	active := a.proxy == server
	a.mu.Unlock()
	if !active {
		return
	}
	err = errors.Join(err, a.teardown())
	a.mu.Lock()
	a.fault = "The local proxy stopped: " + err.Error()
	a.mu.Unlock()
}
