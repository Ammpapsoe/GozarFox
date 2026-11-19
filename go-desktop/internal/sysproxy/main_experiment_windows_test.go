//go:build windows && proxyexperiment

package sysproxy

import (
	"os"
	"testing"
)

func TestMain(m *testing.M) {
	if len(os.Args) == 2 && os.Args[1] == "--internal-proxy-watchdog" {
		if err := RunRecoveryWatchdog(); err != nil {
			os.Exit(1)
		}
		os.Exit(0)
	}
	os.Exit(m.Run())
}
