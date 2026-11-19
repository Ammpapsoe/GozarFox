//go:build windows

package sysproxy

import (
	"encoding/binary"
	"errors"
	"fmt"
	"unicode/utf16"

	"golang.org/x/sys/windows/registry"
)

// registryProxyStore is deliberately independent of the real Internet
// Settings key so the adapter can be tested against an isolated key. The
// caller owns the key and must arrange notification after any changes.
type registryProxyStore struct{ key registry.Key }

func (s registryProxyStore) Read(name string) (ProxyValue, error) {
	n, _, err := s.key.GetValue(name, nil)
	if errors.Is(err, registry.ErrNotExist) {
		return missing(), nil
	}
	if err != nil {
		return ProxyValue{}, err
	}
	// Bound reads: settings are short, and a corrupted value must not cause
	// an unbounded allocation or silently lose data when it is restored.
	if n < 0 || n > 1<<20 {
		return ProxyValue{}, fmt.Errorf("proxy setting %s is too large", name)
	}
	data := make([]byte, n)
	n, kind, err := s.key.GetValue(name, data)
	if err != nil {
		return ProxyValue{}, err
	}
	if n > len(data) {
		return ProxyValue{}, fmt.Errorf("proxy setting %s changed while reading", name)
	}
	data = data[:n]
	switch kind {
	case registry.DWORD:
		if len(data) != 4 {
			return ProxyValue{}, fmt.Errorf("malformed DWORD proxy setting %s", name)
		}
		return number(binary.LittleEndian.Uint32(data)), nil
	case registry.SZ, registry.EXPAND_SZ:
		if len(data) < 2 || len(data)%2 != 0 {
			return ProxyValue{}, fmt.Errorf("malformed string proxy setting %s", name)
		}
		units := make([]uint16, len(data)/2)
		for i := range units {
			units[i] = binary.LittleEndian.Uint16(data[i*2:])
		}
		if units[len(units)-1] != 0 {
			return ProxyValue{}, fmt.Errorf("unterminated proxy setting %s", name)
		}
		units = units[:len(units)-1]
		for i := 0; i < len(units); i++ {
			u := units[i]
			if u == 0 || u >= 0xdc00 && u <= 0xdfff {
				return ProxyValue{}, fmt.Errorf("malformed string proxy setting %s", name)
			}
			if u >= 0xd800 && u <= 0xdbff {
				if i+1 >= len(units) || units[i+1] < 0xdc00 || units[i+1] > 0xdfff {
					return ProxyValue{}, fmt.Errorf("malformed string proxy setting %s", name)
				}
				i++
			}
		}
		value := ProxyValue{Kind: "string", Text: string(utf16.Decode(units))}
		if kind == registry.EXPAND_SZ {
			value.Kind = "expand"
		}
		return value, nil
	default:
		return ProxyValue{}, fmt.Errorf("unsupported registry type %d for proxy setting %s", kind, name)
	}
}

func (s registryProxyStore) Write(name string, value ProxyValue) error {
	if !validValue(value) {
		return errors.New("invalid proxy setting")
	}
	switch value.Kind {
	case "missing":
		err := s.key.DeleteValue(name)
		if errors.Is(err, registry.ErrNotExist) {
			return nil
		}
		return err
	case "dword":
		return s.key.SetDWordValue(name, value.Number)
	case "string":
		return s.key.SetStringValue(name, value.Text)
	case "expand":
		return s.key.SetExpandStringValue(name, value.Text)
	default:
		return errors.New("invalid proxy setting")
	}
}
