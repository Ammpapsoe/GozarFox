package app

import (
	"errors"
	"sync"

	"github.com/Ammpapsoe/GozarFox/go-desktop/internal/config"
)

// connectionPlan is deliberately declarative: selecting a mode does not
// itself acquire a listener, alter system settings, or create a TUN device.
type connectionPlan struct {
	localProxy  bool
	systemProxy bool
	tun         bool
}

func planForMode(mode config.ConnectionMode) (connectionPlan, error) {
	switch mode {
	case config.ModeProxyOnly:
		return connectionPlan{localProxy: true}, nil
	case config.ModeSystemProxy:
		return connectionPlan{localProxy: true, systemProxy: true}, nil
	case config.ModeTUN:
		return connectionPlan{tun: true}, nil
	default:
		return connectionPlan{}, mode.Validate()
	}
}

// modeOwnership prevents two app instances in this process from starting
// incompatible modes. It is a process-local guard, NOT a Windows-wide lock.
// Cross-process and crash recovery must be in place before system proxy mode
// is enabled. The exact lease is required to release ownership, so a stale
// teardown cannot release a newer owner's resources.
type modeOwnership struct {
	mu     sync.Mutex
	active *modeLease
}

type modeLease struct {
	owner *modeOwnership
	mode  config.ConnectionMode
}

func (o *modeOwnership) acquire(mode config.ConnectionMode) (*modeLease, error) {
	if _, err := planForMode(mode); err != nil {
		return nil, err
	}
	o.mu.Lock()
	defer o.mu.Unlock()
	if o.active != nil {
		return nil, errors.New("another connection mode is already active")
	}
	lease := &modeLease{owner: o, mode: mode}
	o.active = lease
	return lease, nil
}

func (l *modeLease) release() {
	if l == nil || l.owner == nil {
		return
	}
	l.owner.mu.Lock()
	defer l.owner.mu.Unlock()
	if l.owner.active == l {
		l.owner.active = nil
	}
}

var processModeOwnership modeOwnership
