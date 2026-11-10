package app

import (
	"context"
	"time"

	"github.com/bardia049b-stack/Gozar1/go-desktop/internal/config"
	relaytransport "github.com/bardia049b-stack/Gozar1/go-desktop/internal/relay"
)

func (a *App) StartRelayDiscovery() {
	go a.refreshRelays()
}

func (a *App) refreshRelays() {
	ctx, cancel := context.WithTimeout(context.Background(), 35*time.Second)
	defer cancel()
	updated, err := relaytransport.Refresh(ctx, nil, true)
	if err != nil || !updated {
		return
	}
	encoded, err := relaytransport.EncodeCache(relaytransport.Endpoints(), time.Now())
	if err != nil {
		return
	}
	a.mu.Lock()
	defer a.mu.Unlock()
	next := a.cfg
	next.RelayCache = encoded
	if config.Save(next) == nil {
		a.cfg = next
	}
}
