//go:build windows && proxyexperiment

package sysproxy

import "context"

// Experimental test hooks only. The setter has no ownership protection and
// must never be called on a user's machine. These symbols do not exist in the
// default production build.
func ReadExperimentalLANSettings() (LANProxySettings, error) { return queryLANProxy() }
func WriteExperimentalLANSettings(p LANProxySettings) error  { return setExperimentalLANProxy(p) }
func RecoverExperimentalLAN() error {
	b, err := openWindowsBackend()
	if err != nil {
		return err
	}
	defer b.Close()
	return b.lock(context.Background(), func(bool) error { return recoverLAN(experimentalLANStore{}, experimentalLANJournal{b.journal}) })
}
