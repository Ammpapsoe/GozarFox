//go:build windows

package sysproxy

import (
	"bytes"
	"crypto/sha256"
	"encoding/binary"
	"encoding/json"
	"errors"
	"io"

	"golang.org/x/sys/windows/registry"
)

// Uses the existing ACL-protected key and flush primitive, but a separate
// value/schema. A checksum detects corruption, not a malicious same-user edit.
// Caller MUST hold the proxy ownership mutex throughout all operations.
type lanRegistryJournal struct{ registryJournal }

const lanJournalName = "LANProxyRecoveryV1"

var lanJournalMagic = [8]byte{'G', 'Z', 'L', 'A', 'N', '0', '0', '1'}

func encodeLANJournal(s lanSnapshot) ([]byte, error) {
	if err := validateLANSnapshot(s); err != nil {
		return nil, err
	}
	payload, err := json.Marshal(s)
	if err != nil {
		return nil, err
	}
	if len(payload) > journalMaxBytes-journalHeaderLen {
		return nil, errors.New("LAN proxy backup is too large")
	}
	out := make([]byte, journalHeaderLen+len(payload))
	copy(out[:8], lanJournalMagic[:])
	binary.BigEndian.PutUint32(out[8:12], uint32(len(payload)))
	sum := sha256.Sum256(payload)
	copy(out[12:journalHeaderLen], sum[:])
	copy(out[journalHeaderLen:], payload)
	return out, nil
}

func decodeLANJournal(data []byte) (*lanSnapshot, error) {
	if len(data) < journalHeaderLen || len(data) > journalMaxBytes || !bytes.Equal(data[:8], lanJournalMagic[:]) || uint64(binary.BigEndian.Uint32(data[8:12])) != uint64(len(data)-journalHeaderLen) {
		return nil, errors.New("invalid LAN backup header")
	}
	payload := data[journalHeaderLen:]
	sum := sha256.Sum256(payload)
	if !bytes.Equal(data[12:journalHeaderLen], sum[:]) {
		return nil, errors.New("invalid LAN backup checksum")
	}
	dec := json.NewDecoder(bytes.NewReader(payload))
	dec.DisallowUnknownFields()
	var s lanSnapshot
	if err := dec.Decode(&s); err != nil {
		return nil, err
	}
	var extra any
	if err := dec.Decode(&extra); !errors.Is(err, io.EOF) {
		return nil, errors.New("invalid trailing LAN backup data")
	}
	if err := validateLANSnapshot(s); err != nil {
		return nil, err
	}
	return &s, nil
}

func (j lanRegistryJournal) LoadLAN() (*lanSnapshot, error) {
	size, kind, err := j.key.GetValue(lanJournalName, nil)
	if errors.Is(err, registry.ErrNotExist) {
		return nil, nil
	}
	if err != nil {
		return nil, err
	}
	if kind != registry.BINARY || size < journalHeaderLen || size > journalMaxBytes {
		return nil, errors.New("invalid LAN backup registry value")
	}
	data := make([]byte, size)
	n, kind, err := j.key.GetValue(lanJournalName, data)
	if err != nil {
		return nil, err
	}
	if kind != registry.BINARY || n != len(data) {
		return nil, errors.New("LAN backup changed while reading")
	}
	return decodeLANJournal(data)
}

func (j lanRegistryJournal) SaveLAN(s lanSnapshot) error {
	data, err := encodeLANJournal(s)
	if err != nil {
		return err
	}
	old, err := j.LoadLAN()
	if err != nil {
		return err
	}
	if old != nil {
		return errors.New("LAN backup already exists")
	}
	if err := j.key.SetBinaryValue(lanJournalName, data); err != nil {
		return err
	}
	return j.sync()
}

func (j lanRegistryJournal) ClearLAN() error {
	if err := j.key.DeleteValue(lanJournalName); err != nil {
		return err
	}
	return j.sync()
}
