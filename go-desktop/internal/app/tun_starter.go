package app

import (
	"net/netip"

	"github.com/Ammpapsoe/GozarFox/go-desktop/internal/engine"
	"github.com/Ammpapsoe/GozarFox/go-desktop/internal/geo"
)

type tunStarter func(string, engine.Dialer, engine.Dialer, []netip.Addr, *geo.Rules, engine.DatagramDialer) (*engine.Engine, error)

// Only Windows implements the OS cache mitigation. Other platforms keep the
// real DNS path; neither proxy mode uses a TUN starter.
func selectTUNStarter(platform string, managed, ordinary tunStarter) tunStarter {
	if platform == "windows" {
		return managed
	}
	return ordinary
}
