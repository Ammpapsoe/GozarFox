package app

import (
	"testing"

	"github.com/bardia049b-stack/Gozar1/go-desktop/internal/fxa"
)

func TestStateReportsActualActiveLocationAndPort(t *testing.T) {
	chosen := fxa.Location{Code: "NL", Country: "Netherlands", City: "Amsterdam", Host: "relay.example", Port: 8443}
	other := fxa.Location{Code: "US", Country: "United States", Host: "other.example", Port: 443}
	current := &App{servers: []fxa.Location{other, chosen}, live: true, activeServer: chosen}
	state := current.State()
	if state.Server.Host != chosen.Host || state.Server.Port != chosen.Port || state.Server.City != chosen.City {
		t.Fatalf("unexpected live location: %+v", state.Server)
	}
	if state.Exit != "" || state.Device != "" || state.Limit != 0 {
		t.Fatalf("unavailable values must not be fabricated: %+v", state)
	}
	current.teardown()
	if current.State().Server.Host != "" {
		t.Fatalf("a previous active server must not be displayed as current after disconnect")
	}
}
