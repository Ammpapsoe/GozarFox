package killswitch

import (
	"net/netip"
	"strings"
	"testing"
)

func TestRequiresInterface(t *testing.T) {
	if err := Enable(Config{}); err == nil {
		t.Fatal("kill switch accepted an empty interface")
	}
}

func TestProxyAndDirectRules(t *testing.T) {
	cfg := Config{Interface: "gozar0", Proxy: []netip.Addr{netip.MustParseAddr("198.51.100.7")}, AllowDirect: true}
	v4, _ := proxyLists(cfg)
	if v4 != "198.51.100.7" {
		t.Fatalf("proxy list = %q", v4)
	}
	ir4, ir6 := irPrefixes()
	if !strings.Contains(ir4, "/") || !strings.Contains(ir6, ":") {
		t.Fatal("Iranian prefix lists are incomplete")
	}
}
