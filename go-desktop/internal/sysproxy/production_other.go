//go:build !windows || !proxyexperiment

package sysproxy

import (
	"context"
	"errors"
	"io"
)

func StartSystemProxy(context.Context, int) (io.Closer, error) {
	return nil, errors.New("system proxy mode is disabled until safe platform recovery is implemented")
}

func RunRecoveryWatchdog() error {
	return errors.New("system proxy recovery watchdog is not enabled")
}
