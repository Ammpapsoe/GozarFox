//go:build linux

package engine

import (
	"errors"
	"io"
	"os"
)

// All privileged Linux sessions use this single OS-wide parent. Do not derive
// it from HOME, XDG_CONFIG_HOME, sudo environment variables or the UI account.
func openProductionMachineFakeDNS(direct func(string) bool) (*fakeDNS, io.Closer, error) {
	if os.Geteuid() != 0 {
		return nil, nil, errors.New("machine FakeDNS requires root")
	}
	fake, owner, err := openLinuxMachineDNSStorage("/var/lib", direct)
	if err != nil {
		return nil, nil, err
	}
	return fake, owner, nil
}
