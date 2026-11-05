//go:build !linux && !darwin && !windows

package killswitch

import (
	"fmt"
	"runtime"
)

func enableOS(Config) error {
	return fmt.Errorf("kill switch is not supported on %s", runtime.GOOS)
}

func disableOS() error { return nil }
