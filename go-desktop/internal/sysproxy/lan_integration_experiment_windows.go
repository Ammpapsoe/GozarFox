//go:build windows && proxyexperiment

package sysproxy

import (
	"context"
	"io"
)

// Experimental test hooks only. The setter has no ownership protection and
// must never be called on a user's machine. These symbols do not exist in the
// default production build.
func ReadExperimentalLANSettings() (LANProxySettings, error) { return queryLANProxy() }
func WriteExperimentalLANSettings(p LANProxySettings) error  { return setLANProxy(p) }

// PrepareExperimentalLANRecovery simulates state left after both the owner and
// watchdog have died. Tests must confine this to disposable Windows runners.
func PrepareExperimentalLANRecovery(port int) error {
	b, err := openWindowsBackend()
	if err != nil {
		return err
	}
	defer b.Close()
	return b.lock(context.Background(), func(bool) error {
		return activateLAN(wininetLANStore{}, lanRegistryJournal{b.journal}, port)
	})
}

func RecoverExperimentalLAN() error {
	b, err := openWindowsBackend()
	if err != nil {
		return err
	}
	defer b.Close()
	return b.lock(context.Background(), func(bool) error { return recoverLAN(wininetLANStore{}, lanRegistryJournal{b.journal}) })
}

// Experimental compatibility wrappers for disposable-runner tests.
func StartExperimentalLANSession(ctx context.Context, port int) (io.Closer, error) {
	return StartSystemProxy(ctx, port)
}
func RunExperimentalLANWatchdog() error { return RunRecoveryWatchdog() }
