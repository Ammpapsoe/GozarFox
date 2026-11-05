//go:build linux

package killswitch

import (
	"net/netip"
	"strings"
	"testing"
)

func TestLinuxRulesBlockByDefault(t *testing.T) {
	rules := linuxRules(Config{Interface: "gozar0", Proxy: []netip.Addr{netip.MustParseAddr("198.51.100.7")}, AllowDirect: true})
	for _, want := range []string{"policy drop", "oifname \"gozar0\" accept", "198.51.100.7", "@ir4", "counter drop"} {
		if !strings.Contains(rules, want) {
			t.Fatalf("rules missing %q", want)
		}
	}
}
