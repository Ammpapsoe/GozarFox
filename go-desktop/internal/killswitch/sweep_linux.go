//go:build linux

package killswitch

import "strings"

// staleOS reports whether the nft table this app creates is still present.
func staleOS() (bool, error) {
	out, err := commandOutput("nft", "list", "table", "inet", "gozar")
	if err != nil {
		// nft exits non-zero when the table does not exist, which is the clean
		// case, so only content counts as evidence.
		return false, nil
	}
	return strings.Contains(out, "table inet gozar"), nil
}
