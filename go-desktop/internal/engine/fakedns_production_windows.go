//go:build windows

package engine

import "io"

func openProductionMachineFakeDNS(direct func(string) bool) (*fakeDNS, io.Closer, error) {
	fake, owner, err := openProductionWindowsMachineDNS(direct)
	if err != nil {
		return nil, nil, err
	}
	return fake, owner, nil
}
