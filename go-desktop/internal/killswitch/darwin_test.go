//go:build darwin

package killswitch

import (
	"net/netip"
	"strings"
	"testing"
)

func TestDarwinRulesBlockByDefault(t *testing.T) {
	rules := darwinRules(Config{Interface: "utun9", Proxy: []netip.Addr{netip.MustParseAddr("198.51.100.7")}, AllowDirect: true})
	for _, want := range []string{"pass out quick on utun9 all", "198.51.100.7", "<gozar_ir4>", "block drop out quick all"} {
		if !strings.Contains(rules, want) {
			t.Fatalf("rules missing %q", want)
		}
	}
}
