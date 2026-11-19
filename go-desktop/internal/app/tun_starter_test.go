package app

import (
	"net/netip"
	"testing"

	"github.com/Ammpapsoe/GozarFox/go-desktop/internal/engine"
	"github.com/Ammpapsoe/GozarFox/go-desktop/internal/geo"
)

func TestConnectionModeSelectsPlatformFakeDNS(t *testing.T) {
	for _, tc := range []struct{ platform, want string }{{"windows", "managed"}, {"linux", "ordinary"}, {"darwin", "ordinary"}, {"freebsd", "ordinary"}, {"unknown", "ordinary"}} {
		t.Run(tc.platform, func(t *testing.T) {
			called := ""
			managed := func(string, engine.Dialer, engine.Dialer, []netip.Addr, *geo.Rules, engine.DatagramDialer) (*engine.Engine, error) {
				called = "managed"
				return nil, nil
			}
			ordinary := func(string, engine.Dialer, engine.Dialer, []netip.Addr, *geo.Rules, engine.DatagramDialer) (*engine.Engine, error) {
				called = "ordinary"
				return nil, nil
			}
			_, err := selectTUNStarter(tc.platform, managed, ordinary)("", nil, nil, nil, nil, nil)
			if err != nil || called != tc.want {
				t.Fatalf("selected %q, expected %q: %v", called, tc.want, err)
			}
		})
	}
}
