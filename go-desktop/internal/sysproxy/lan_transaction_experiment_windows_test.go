//go:build windows && proxyexperiment

package sysproxy

import (
	"errors"
	"reflect"
	"testing"
)

type memoryLANStore struct {
	value    LANProxySettings
	writes   int
	writeErr error
}

func (m *memoryLANStore) ReadLAN() (LANProxySettings, error) { return m.value, nil }
func (m *memoryLANStore) WriteLAN(p LANProxySettings) error {
	m.writes++
	m.value = p
	return m.writeErr
}

type memoryLANJournal struct {
	value    *lanSnapshot
	saveErr  error
	clearErr error
}

func (m *memoryLANJournal) LoadLAN() (*lanSnapshot, error) { return m.value, nil }
func (m *memoryLANJournal) SaveLAN(s lanSnapshot) error {
	if m.value != nil {
		return errors.New("occupied")
	}
	m.value = &s
	return m.saveErr
}
func (m *memoryLANJournal) ClearLAN() error {
	if m.clearErr != nil {
		return m.clearErr
	}
	m.value = nil
	return nil
}

func TestLANExperimentTransaction(t *testing.T) {
	old := LANProxySettings{Flags: 13, Server: "old:8080", Bypass: "*.local", PAC: "https://pac.invalid/a"}
	t.Run("connect and disconnect", func(t *testing.T) {
		s, j := &memoryLANStore{value: old}, &memoryLANJournal{}
		if err := activateLAN(s, j, 23456); err != nil {
			t.Fatal(err)
		}
		if s.value != desiredLAN(23456) || j.value == nil || j.value.Previous != old {
			t.Fatal("activation did not preserve old state")
		}
		if err := recoverLAN(s, j); err != nil {
			t.Fatal(err)
		}
		if s.value != old || j.value != nil {
			t.Fatal("restore failed")
		}
	})
	t.Run("crash after journal save before apply", func(t *testing.T) {
		s, j := &memoryLANStore{value: old}, &memoryLANJournal{value: &lanSnapshot{Version: 1, Port: 23456, Previous: old, Applied: desiredLAN(23456)}}
		if err := recoverLAN(s, j); err != nil || s.writes != 0 || j.value != nil {
			t.Fatal("failed to finish no-op recovery", err)
		}
	})
	t.Run("crash after apply", func(t *testing.T) {
		s, j := &memoryLANStore{value: desiredLAN(23456)}, &memoryLANJournal{value: &lanSnapshot{Version: 1, Port: 23456, Previous: old, Applied: desiredLAN(23456)}}
		if err := recoverLAN(s, j); err != nil || s.value != old || j.value != nil {
			t.Fatal("failed recovery", err)
		}
	})
	t.Run("external change retains journal and does not write", func(t *testing.T) {
		other := LANProxySettings{Flags: 3, Server: "other:1234"}
		s, j := &memoryLANStore{value: other}, &memoryLANJournal{value: &lanSnapshot{Version: 1, Port: 23456, Previous: old, Applied: desiredLAN(23456)}}
		if err := recoverLAN(s, j); err == nil || s.value != other || s.writes != 0 || j.value == nil {
			t.Fatal("conflict was not preserved", err)
		}
	})
	t.Run("uncertain save must not apply", func(t *testing.T) {
		s, j := &memoryLANStore{value: old}, &memoryLANJournal{saveErr: errors.New("flush failed")}
		if err := activateLAN(s, j, 23456); err == nil || s.value != old || s.writes != 0 || j.value == nil {
			t.Fatal("uncertain save mutated LAN", err)
		}
	})
	t.Run("uncertain apply can recover", func(t *testing.T) {
		s, j := &memoryLANStore{value: old, writeErr: errors.New("notify failed")}, &memoryLANJournal{}
		if err := activateLAN(s, j, 23456); err == nil || j.value == nil {
			t.Fatal("apply error lost journal", err)
		}
		s.writeErr = nil
		if err := recoverLAN(s, j); err != nil || s.value != old || j.value != nil {
			t.Fatal("uncertain apply not recovered", err)
		}
	})
	t.Run("uncertain clear is retryable", func(t *testing.T) {
		s, j := &memoryLANStore{value: desiredLAN(23456)}, &memoryLANJournal{value: &lanSnapshot{Version: 1, Port: 23456, Previous: old, Applied: desiredLAN(23456)}, clearErr: errors.New("flush failed")}
		if err := recoverLAN(s, j); err == nil || s.value != old || j.value == nil {
			t.Fatal("clear error lost backup", err)
		}
		j.clearErr = nil
		if err := recoverLAN(s, j); err != nil || j.value != nil {
			t.Fatal("clear retry failed", err)
		}
	})
}

func TestLANExperimentRegistryJournal(t *testing.T) {
	key := isolatedRegistry(t)
	j := experimentalLANJournal{registryJournal{key: key.key}}
	s := lanSnapshot{Version: 1, Port: 23456, Previous: LANProxySettings{Flags: 13, PAC: "https://pac.invalid"}, Applied: desiredLAN(23456)}
	if err := j.SaveLAN(s); err != nil {
		t.Fatal(err)
	}
	got, err := j.LoadLAN()
	if err != nil || got == nil || !reflect.DeepEqual(*got, s) {
		t.Fatal("journal round-trip failed", err)
	}
	if err := j.SaveLAN(s); err == nil {
		t.Fatal("overwrote journal")
	}
	data, _, err := key.key.GetBinaryValue(lanJournalName)
	if err != nil {
		t.Fatal(err)
	}
	data[len(data)-1] ^= 1
	if err := key.key.SetBinaryValue(lanJournalName, data); err != nil {
		t.Fatal(err)
	}
	if _, err := j.LoadLAN(); err == nil {
		t.Fatal("accepted damaged journal")
	}
	if err := j.SaveLAN(s); err == nil {
		t.Fatal("overwrote damaged journal")
	}
}
