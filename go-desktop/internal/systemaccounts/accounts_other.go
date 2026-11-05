//go:build !linux && !darwin && !windows

package systemaccounts

import "errors"

func Emails() ([]string, error) {
	return nil, errors.New("listing system accounts is not supported on this platform")
}
