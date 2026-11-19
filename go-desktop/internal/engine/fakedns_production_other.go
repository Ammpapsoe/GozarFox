//go:build !linux && !windows

package engine

import (
	"errors"
	"io"
)

func openProductionMachineFakeDNS(direct func(string) bool) (*fakeDNS, io.Closer, error) {
	return nil, nil, errors.New("durable machine FakeDNS is not supported on this platform")
}
