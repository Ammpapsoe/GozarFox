//go:build !windows && !linux && !darwin

package killswitch

func staleOS() (bool, error) { return false, nil }
