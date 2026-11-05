//go:build windows

package killswitch

import (
	"net/netip"
	"strings"
	"testing"
)

func TestWindowsRulesBlockByDefault(t *testing.T) {
	script := windowsEnableScript(Config{Interface: "Gozar", Proxy: []netip.Addr{netip.MustParseAddr("198.51.100.7")}, AllowDirect: true})
	for _, want := range []string{"Gozar-Tunnel", "Gozar-Proxy", "Gozar-IR-0", "DefaultOutboundAction Block"} {
		if !strings.Contains(script, want) {
			t.Fatalf("script missing %q", want)
		}
	}
	if !strings.Contains(windowsDisableScript(), "DefaultOutboundAction") {
		t.Fatal("disable script must restore the outbound policy")
	}
}
