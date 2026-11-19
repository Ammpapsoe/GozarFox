//go:build windows

package sysproxy

import (
	"errors"
	"fmt"
	"strconv"
	"strings"
)

// The entire logical LAN setting is a single ownership unit. Windows exposes
// no compare-and-swap for the set operation, so an uncooperative writer can
// race the final check; the ownership mutex only coordinates our own processes.
// Restore and activation must run under the same per-user ownership lock.
type lanStore interface {
	ReadLAN() (LANProxySettings, error)
	WriteLAN(LANProxySettings) error
}

type lanSnapshot struct {
	Version  int              `json:"version"`
	Port     int              `json:"port"`
	Previous LANProxySettings `json:"previous"`
	Applied  LANProxySettings `json:"applied"`
}

type lanJournal interface {
	LoadLAN() (*lanSnapshot, error)
	SaveLAN(lanSnapshot) error // must be durable before returning success
	ClearLAN() error           // must be durable before returning success
}

func desiredLAN(port int) LANProxySettings {
	return LANProxySettings{Flags: 3, Server: "127.0.0.1:" + strconv.Itoa(port), Bypass: "localhost;127.*;<local>"}
}

func validLANSettings(p LANProxySettings) bool {
	for _, v := range []string{p.Server, p.Bypass, p.PAC} {
		if len(v) > 128*1024 || strings.ContainsRune(v, 0) {
			return false
		}
	}
	return true
}

func validateLANSnapshot(s lanSnapshot) error {
	if s.Version != 1 || s.Port < 1 || s.Port > 65535 || !validLANSettings(s.Previous) || s.Applied != desiredLAN(s.Port) {
		return errors.New("invalid LAN proxy backup")
	}
	return nil
}

// recoverLAN must hold the cross-process lock. It only restores a complete
// exact state known to be ours; unknown or partial state is a conflict, never
// silently overwritten. A crash between comparison and Windows set cannot be
// made atomic against an unrelated writer.
func recoverLAN(store lanStore, journal lanJournal) error {
	s, err := journal.LoadLAN()
	if err != nil {
		return fmt.Errorf("load LAN proxy backup: %w", err)
	}
	if s == nil {
		return nil
	}
	if err := validateLANSnapshot(*s); err != nil {
		return err
	}
	current, err := store.ReadLAN()
	if err != nil {
		return fmt.Errorf("read LAN proxy: %w", err)
	}
	if current != s.Previous && current != s.Applied {
		return errors.New("LAN proxy settings changed externally; backup retained")
	}
	if current != s.Previous {
		// Avoid modifying a setting already known to have changed while the
		// journal was being read. This cannot eliminate the OS-level race.
		again, err := store.ReadLAN()
		if err != nil {
			return fmt.Errorf("recheck LAN proxy: %w", err)
		}
		if again != current {
			return errors.New("LAN proxy settings changed during recovery; backup retained")
		}
		if err := store.WriteLAN(s.Previous); err != nil {
			return fmt.Errorf("restore LAN proxy: %w", err)
		}
	}
	current, err = store.ReadLAN()
	if err != nil {
		return fmt.Errorf("verify LAN proxy restore: %w", err)
	}
	if current != s.Previous {
		return errors.New("LAN proxy restore verification failed; backup retained")
	}
	if err := journal.ClearLAN(); err != nil {
		return fmt.Errorf("clear LAN proxy backup: %w", err)
	}
	return nil
}

func activateLAN(store lanStore, journal lanJournal, port int) error {
	if port < 1 || port > 65535 {
		return errors.New("invalid proxy port")
	}
	if err := recoverLAN(store, journal); err != nil {
		return err
	}
	previous, err := store.ReadLAN()
	if err != nil {
		return fmt.Errorf("read LAN proxy: %w", err)
	}
	s := lanSnapshot{Version: 1, Port: port, Previous: previous, Applied: desiredLAN(port)}
	if err := validateLANSnapshot(s); err != nil {
		return err
	}
	if err := journal.SaveLAN(s); err != nil {
		return fmt.Errorf("save LAN proxy backup: %w", err)
	}
	current, err := store.ReadLAN()
	if err != nil {
		return fmt.Errorf("recheck LAN proxy: %w", err)
	}
	if current != previous {
		return errors.New("LAN proxy settings changed before activation; backup retained")
	}
	if err := store.WriteLAN(s.Applied); err != nil {
		return fmt.Errorf("apply LAN proxy: %w", err)
	}
	current, err = store.ReadLAN()
	if err != nil {
		return fmt.Errorf("verify LAN proxy activation: %w", err)
	}
	if current != s.Applied {
		return errors.New("LAN proxy activation verification failed; backup retained")
	}
	return nil
}

type wininetLANStore struct{}

func (wininetLANStore) ReadLAN() (LANProxySettings, error) { return queryLANProxy() }
func (wininetLANStore) WriteLAN(p LANProxySettings) error  { return setLANProxy(p) }
