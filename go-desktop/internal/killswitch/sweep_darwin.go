//go:build darwin

package killswitch

import "strings"

// staleOS reports whether the pf anchor still holds rules from an earlier run.
func staleOS() (bool, error) {
	out, err := commandOutput("pfctl", "-a", "gozar", "-s", "rules")
	if err != nil {
		return false, nil
	}
	return strings.TrimSpace(out) != "", nil
}
