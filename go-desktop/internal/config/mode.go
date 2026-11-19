package config

import (
	"encoding/json"
	"fmt"
)

// ConnectionMode is a single, mutually exclusive desktop connection mode.
// ProxyOnly never requests a system proxy mutation; SystemProxy owns a local
// listener and reversible user proxy settings; TUN owns a tunnel interface.
type ConnectionMode string

const (
	ModeTUN         ConnectionMode = "tun"
	ModeProxyOnly   ConnectionMode = "proxy-only"
	ModeSystemProxy ConnectionMode = "system-proxy"
)

// UnmarshalJSON makes an explicitly invalid field fail closed instead of
// letting a bad type, null or empty string fall back to the default TUN.
// A missing legacy field still leaves the zero value for Load to migrate.
func (m *ConnectionMode) UnmarshalJSON(data []byte) error {
	var value string
	if err := json.Unmarshal(data, &value); err != nil || value == "" {
		*m = ConnectionMode("<invalid>")
		return nil
	}
	*m = ConnectionMode(value)
	return nil
}

func (m ConnectionMode) Validate() error {
	switch m {
	case ModeTUN, ModeProxyOnly, ModeSystemProxy:
		return nil
	default:
		return fmt.Errorf("unsupported connection mode %q", m)
	}
}
