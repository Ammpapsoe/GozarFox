package sysproxy

import (
	"errors"
	"fmt"
	"strconv"
)

// ProxyValue preserves whether a setting was absent, its type and its value.
// Registry types other than these must be rejected by the platform adapter.
type ProxyValue struct {
	Kind   string `json:"kind"`
	Text   string `json:"text,omitempty"`
	Number uint32 `json:"number,omitempty"`
}

type proxyStore interface {
	Read(string) (ProxyValue, error)
	Write(string, ProxyValue) error
}

type ProxySnapshot struct {
	Version  int                   `json:"version"`
	Port     int                   `json:"port"`
	Previous map[string]ProxyValue `json:"previous"`
}

var proxyNames = []string{"ProxyEnable", "ProxyServer", "ProxyOverride", "AutoConfigURL", "AutoDetect"}

func missing() ProxyValue        { return ProxyValue{Kind: "missing"} }
func number(v uint32) ProxyValue { return ProxyValue{Kind: "dword", Number: v} }
func text(v string) ProxyValue   { return ProxyValue{Kind: "string", Text: v} }

func desired(port int) map[string]ProxyValue {
	return map[string]ProxyValue{
		"ProxyEnable":   number(1),
		"ProxyServer":   text("127.0.0.1:" + strconv.Itoa(port)),
		"ProxyOverride": text("localhost;127.*;<local>"),
		"AutoConfigURL": missing(),
		"AutoDetect":    number(0),
	}
}

func validValue(v ProxyValue) bool {
	switch v.Kind {
	case "missing":
		return v.Text == "" && v.Number == 0
	case "dword":
		return v.Text == ""
	case "string", "expand":
		return v.Number == 0
	default:
		return false
	}
}

func captureProxy(store proxyStore, port int) (ProxySnapshot, error) {
	if port < 1 || port > 65535 {
		return ProxySnapshot{}, errors.New("invalid proxy port")
	}
	snapshot := ProxySnapshot{Version: 1, Port: port, Previous: make(map[string]ProxyValue, len(proxyNames))}
	for _, name := range proxyNames {
		value, err := store.Read(name)
		if err != nil {
			return ProxySnapshot{}, err
		}
		if !validValue(value) {
			return ProxySnapshot{}, fmt.Errorf("unsupported proxy setting %s", name)
		}
		snapshot.Previous[name] = value
	}
	return snapshot, nil
}

func validateSnapshot(snapshot ProxySnapshot) error {
	if snapshot.Version != 1 || snapshot.Port < 1 || snapshot.Port > 65535 || len(snapshot.Previous) != len(proxyNames) {
		return errors.New("invalid proxy settings backup")
	}
	for _, name := range proxyNames {
		value, ok := snapshot.Previous[name]
		if !ok || !validValue(value) {
			return errors.New("invalid proxy settings backup")
		}
	}
	return nil
}

// applyProxy is called only after a durable backup was saved. Disable the old
// proxy before changing its target, and enable the new one as the final step.
// If a write fails, attempt rollback; the durable backup remains for recovery.
func applyProxy(store proxyStore, snapshot ProxySnapshot) error {
	if err := validateSnapshot(snapshot); err != nil {
		return err
	}
	want := desired(snapshot.Port)
	// Recheck after the journal is durable: the user or another application
	// may have changed a setting since capture. This is not atomic against
	// non-cooperating writers, so it reduces but cannot eliminate the race.
	for _, name := range proxyNames {
		current, err := store.Read(name)
		if err != nil {
			return err
		}
		if current != snapshot.Previous[name] {
			return fmt.Errorf("proxy setting %s changed before activation", name)
		}
	}
	currentEnable, err := store.Read("ProxyEnable")
	if err != nil {
		return err
	}
	if currentEnable != snapshot.Previous["ProxyEnable"] {
		return errors.New("proxy setting ProxyEnable changed before activation")
	}
	if err := store.Write("ProxyEnable", number(0)); err != nil {
		return err
	}
	for _, name := range []string{"ProxyServer", "ProxyOverride", "AutoConfigURL", "AutoDetect", "ProxyEnable"} {
		expected := snapshot.Previous[name]
		if name == "ProxyEnable" {
			expected = number(0)
		}
		current, err := store.Read(name)
		if err == nil && current != expected {
			err = fmt.Errorf("proxy setting %s changed during activation", name)
		}
		if err == nil {
			err = store.Write(name, want[name])
		}
		if err != nil {
			if rollback := restoreProxy(store, snapshot); rollback != nil {
				return fmt.Errorf("apply proxy: %v; rollback: %w", err, rollback)
			}
			return err
		}
	}
	return nil
}

// restoreProxy recognises both the final state and a partial application. It
// refuses to overwrite a value that differs from both our value and the saved
// one: another application or the user may have edited the proxy meanwhile.
func restoreProxy(store proxyStore, snapshot ProxySnapshot) error {
	if err := validateSnapshot(snapshot); err != nil {
		return err
	}
	want := desired(snapshot.Port)
	current := make(map[string]ProxyValue, len(proxyNames))
	for _, name := range proxyNames {
		value, err := store.Read(name)
		if err != nil {
			return err
		}
		previous := snapshot.Previous[name]
		if value != previous && value != want[name] && !(name == "ProxyEnable" && value == number(0)) {
			return fmt.Errorf("proxy setting %s changed externally", name)
		}
		current[name] = value
	}
	// Never temporarily route through a target that is being restored.
	if current["ProxyEnable"] != number(0) {
		if err := store.Write("ProxyEnable", number(0)); err != nil {
			return err
		}
	}
	for _, name := range []string{"ProxyServer", "ProxyOverride", "AutoConfigURL", "AutoDetect", "ProxyEnable"} {
		if err := store.Write(name, snapshot.Previous[name]); err != nil {
			return err
		}
	}
	return nil
}
