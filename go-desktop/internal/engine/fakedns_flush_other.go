//go:build !windows

package engine

import "errors"

func flushSyntheticDNSSystemCache() error { return errors.New("Windows DNS cache flush unavailable") }
