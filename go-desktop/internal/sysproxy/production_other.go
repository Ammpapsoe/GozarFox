//go:build !windows

package sysproxy

import (
	"context"
	"errors"
	"io"
)

func StartSystemProxy(context.Context, int) (io.Closer, error) {
	return nil, errors.New("safe system proxy mode is available only on Windows")
}

func RunRecoveryWatchdog() error {
	return errors.New("system proxy recovery watchdog is available only on Windows")
}
