//go:build windows

package sysproxy

import (
	"bytes"
	"crypto/sha256"
	"encoding/binary"
	"errors"
	"reflect"
	"testing"

	"golang.org/x/sys/windows/registry"
)

func TestRegistryJournalRoundTripAndRecovery(t *testing.T) {
	backupKey := isolatedRegistry(t)
	settings := isolatedRegistry(t)
	previous := map[string]ProxyValue{
		"ProxyEnable": number(1), "ProxyServer": text("old-proxy.example:3128"),
		"ProxyOverride": {Kind: "expand", Text: "%USERDOMAIN%;内网"},
		"AutoConfigURL": text("https://proxy.example/setup.pac"), "AutoDetect": number(1),
	}
	for name, value := range previous {
		if err := settings.Write(name, value); err != nil {
			t.Fatal(err)
		}
	}
	journal := registryJournal{key: backupKey.key}
	if err := activateProxy(settings, journal, 23456); err != nil {
		t.Fatal(err)
	}
	// A new journal wrapper simulates a new owner reopening the backup key;
	// process locking and a real process crash are tested separately later.
	reopened := registryJournal{key: backupKey.key}
	got, err := reopened.Load()
	if err != nil || got == nil || !reflect.DeepEqual(got.Previous, previous) || got.Port != 23456 {
		t.Fatalf("backup differs: %#v, error %v", got, err)
	}
	if err := recoverProxy(settings, reopened); err != nil {
		t.Fatal(err)
	}
	for name, want := range previous {
		value, err := settings.Read(name)
		if err != nil || value != want {
			t.Fatalf("%s: got %#v, want %#v, error %v", name, value, want, err)
		}
	}
	if got, err := reopened.Load(); err != nil || got != nil {
		t.Fatalf("backup remains after recovery: %#v, %v", got, err)
	}
}

func TestRegistryJournalRefusesOverwriteAndConflict(t *testing.T) {
	backupKey := isolatedRegistry(t)
	journal := registryJournal{key: backupKey.key}
	store := newMemoryProxy()
	if err := activateProxy(store, journal, 10810); err != nil {
		t.Fatal(err)
	}
	before, err := journal.Load()
	if err != nil || before == nil {
		t.Fatal("backup was not saved", err)
	}
	if err := journal.Save(*before); err == nil {
		t.Fatal("occupied backup was overwritten")
	}
	store.values["ProxyServer"] = text("other-owner.invalid:8080")
	values := copyValues(store.values)
	if err := recoverProxy(store, journal); err == nil {
		t.Fatal("external change accepted")
	}
	after, err := journal.Load()
	if err != nil || !reflect.DeepEqual(after, before) || !reflect.DeepEqual(values, store.values) {
		t.Fatal("conflict changed settings or backup", err)
	}
}

func TestRegistryJournalRejectsDamagedOrUnsupportedData(t *testing.T) {
	backupKey := isolatedRegistry(t)
	journal := registryJournal{key: backupKey.key}
	snapshot, err := captureProxy(newMemoryProxy(), 10810)
	if err != nil {
		t.Fatal(err)
	}
	valid, err := encodeJournal(snapshot)
	if err != nil {
		t.Fatal(err)
	}
	for name, mutate := range map[string]func([]byte) []byte{
		"magic":     func(b []byte) []byte { b[0] ^= 1; return b },
		"length":    func(b []byte) []byte { b[11] ^= 1; return b },
		"checksum":  func(b []byte) []byte { b[len(b)-1] ^= 1; return b },
		"truncated": func(b []byte) []byte { return b[:len(b)-1] },
		"unsupported schema": func(b []byte) []byte {
			payload := []byte(`{"version":99,"port":10810,"previous":{}}`)
			b = make([]byte, journalHeaderLen+len(payload))
			copy(b[:8], journalMagic[:])
			binary.BigEndian.PutUint32(b[8:12], uint32(len(payload)))
			hash := sha256.Sum256(payload)
			copy(b[12:journalHeaderLen], hash[:])
			copy(b[journalHeaderLen:], payload)
			return b
		},
	} {
		t.Run(name, func(t *testing.T) {
			damaged := mutate(bytes.Clone(valid))
			if err := backupKey.key.SetBinaryValue(journalValueName, damaged); err != nil {
				t.Fatal(err)
			}
			if _, err := journal.Load(); err == nil {
				t.Fatal("invalid backup was accepted")
			}
			if err := journal.Save(snapshot); err == nil {
				t.Fatal("invalid backup was overwritten")
			}
			actual, _, err := backupKey.key.GetBinaryValue(journalValueName)
			if err != nil || !bytes.Equal(actual, damaged) {
				t.Fatal("invalid backup was modified", err)
			}
		})
	}
	if err := backupKey.key.SetStringValue(journalValueName, "wrong type"); err != nil {
		t.Fatal(err)
	}
	if _, err := journal.Load(); err == nil {
		t.Fatal("wrong registry value type was accepted")
	}
}

func TestRegistryJournalUncertainSaveDoesNotApplyProxy(t *testing.T) {
	backupKey := isolatedRegistry(t)
	store := newMemoryProxy()
	original := copyValues(store.values)
	journal := registryJournal{key: backupKey.key, flush: func(registry.Key) error { return errors.New("simulated flush failure") }}
	if err := activateProxy(store, journal, 10810); err == nil {
		t.Fatal("sync failure not reported")
	}
	if !reflect.DeepEqual(original, store.values) {
		t.Fatal("proxy changed after uncertain journal write")
	}
	// The write may nevertheless have taken effect: preserve it for recovery.
	if got, err := journal.Load(); err != nil || got == nil {
		t.Fatal("uncertain save lost its backup", err)
	}
}
