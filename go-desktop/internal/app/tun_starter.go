package app

import (
	"net/netip"

	"github.com/Ammpapsoe/GozarFox/go-desktop/internal/engine"
	"github.com/Ammpapsoe/GozarFox/go-desktop/internal/geo"
)

type tunStarter func(string, engine.Dialer, engine.Dialer, []netip.Addr, *geo.Rules, engine.DatagramDialer) (*engine.Engine, error)

// Durable machine FakeDNS is available only on Linux and Windows. Other
// platforms retain the existing non-synthetic resolver until their own durable
// machine-wide store is implemented; proxy-only modes never start TUN here.
func selectTUNStarter(platform string, managed, ordinary tunStarter) tunStarter {
	if platform == "linux" || platform == "windows" {
		return managed
	}
	return ordinary
}
