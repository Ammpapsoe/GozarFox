package rpc

import (
	"encoding/json"
	"testing"

	"github.com/Ammpapsoe/GozarFox/go-desktop/internal/app"
	"github.com/Ammpapsoe/GozarFox/go-desktop/internal/config"
)

func TestConnectionModeRPCValidatesNewAndLegacyRequests(t *testing.T) {
	root := t.TempDir()
	t.Setenv("HOME", root)
	t.Setenv("XDG_CONFIG_HOME", root)
	t.Setenv("APPDATA", root)
	current := app.New("test")
	defer current.Close()
	server := &Server{app: current}
	call := func(params string) Response {
		return server.handle(Request{ID: 1, Method: "mode", Params: json.RawMessage(params)})
	}
	for _, mode := range []config.ConnectionMode{config.ModeProxyOnly, config.ModeSystemProxy, config.ModeTUN} {
		response := call(`{"mode":"` + string(mode) + `"}`)
		if response.Error != "" || response.State == nil || response.State.Mode != string(mode) {
			t.Fatalf("mode %q response: %#v", mode, response)
		}
	}
	if response := call(`{"tunnel":true}`); response.Error != "" || response.State.Mode != "tun" {
		t.Fatalf("legacy TUN failed: %#v", response)
	}
	for _, params := range []string{`{}`, `null`, `{"tunnel":false}`, `{"mode":"other"}`, `{"mode":"proxy-only","tunnel":true}`, `{"mode":null}`, `{"mode":5}`, `{`} {
		before := current.State().Mode
		response := call(params)
		if response.Error == "" || current.State().Mode != before {
			t.Fatalf("invalid request %q changed mode or succeeded: %#v", params, response)
		}
	}
}
