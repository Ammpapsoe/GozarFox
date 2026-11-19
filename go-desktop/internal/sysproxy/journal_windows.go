//go:build windows

package sysproxy

import (
	"bytes"
	"crypto/sha256"
	"encoding/binary"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"syscall"

	"golang.org/x/sys/windows"
	"golang.org/x/sys/windows/registry"
)

// registryJournal is an unwired building block. The caller supplies the key,
// owns it, and MUST hold a cross-process lock around Load/Save/Clear and all
// associated proxy mutations. No production key or ACL is selected here.
// A checksum detects accidental damage, not malicious edits, deletion, replay,
// or rollback. The journal can contain private proxy/PAC settings: use a
// correctly restricted per-user ACL before ever connecting it to the app.
type registryJournal struct {
	key   registry.Key
	flush func(registry.Key) error
}

const (
	journalValueName = "ProxyRecoveryV1"
	journalMaxBytes  = 1 << 20
	journalHeaderLen = 8 + 4 + sha256.Size
)

var (
	journalMagic = [8]byte{'G', 'Z', 'P', 'R', 'X', 'Y', '0', '1'}
	regFlushKey  = windows.NewLazySystemDLL("advapi32.dll").NewProc("RegFlushKey")
)

func flushRegistryKey(key registry.Key) error {
	if err := regFlushKey.Find(); err != nil {
		return err
	}
	status, _, _ := regFlushKey.Call(uintptr(key))
	if status != 0 {
		return syscall.Errno(status)
	}
	return nil
}

func (j registryJournal) sync() error {
	if j.flush != nil {
		return j.flush(j.key)
	}
	return flushRegistryKey(j.key)
}

func encodeJournal(snapshot ProxySnapshot) ([]byte, error) {
	if err := validateSnapshot(snapshot); err != nil {
		return nil, err
	}
	payload, err := json.Marshal(snapshot)
	if err != nil {
		return nil, err
	}
	if len(payload) > journalMaxBytes-journalHeaderLen {
		return nil, errors.New("proxy backup is too large")
	}
	result := make([]byte, journalHeaderLen+len(payload))
	copy(result[:8], journalMagic[:])
	binary.BigEndian.PutUint32(result[8:12], uint32(len(payload)))
	digest := sha256.Sum256(payload)
	copy(result[12:journalHeaderLen], digest[:])
	copy(result[journalHeaderLen:], payload)
	return result, nil
}

func decodeJournal(data []byte) (*ProxySnapshot, error) {
	if len(data) < journalHeaderLen || len(data) > journalMaxBytes || !bytes.Equal(data[:8], journalMagic[:]) {
		return nil, errors.New("invalid proxy backup header")
	}
	if uint64(binary.BigEndian.Uint32(data[8:12])) != uint64(len(data)-journalHeaderLen) {
		return nil, errors.New("invalid proxy backup length")
	}
	payload := data[journalHeaderLen:]
	digest := sha256.Sum256(payload)
	if !bytes.Equal(data[12:journalHeaderLen], digest[:]) {
		return nil, errors.New("invalid proxy backup checksum")
	}
	decoder := json.NewDecoder(bytes.NewReader(payload))
	decoder.DisallowUnknownFields()
	var snapshot ProxySnapshot
	if err := decoder.Decode(&snapshot); err != nil {
		return nil, fmt.Errorf("decode proxy backup: %w", err)
	}
	var extra any
	if err := decoder.Decode(&extra); !errors.Is(err, io.EOF) {
		return nil, errors.New("invalid trailing proxy backup data")
	}
	if err := validateSnapshot(snapshot); err != nil {
		return nil, err
	}
	return &snapshot, nil
}

func (j registryJournal) Load() (*ProxySnapshot, error) {
	size, kind, err := j.key.GetValue(journalValueName, nil)
	if errors.Is(err, registry.ErrNotExist) {
		return nil, nil
	}
	if err != nil {
		return nil, err
	}
	if kind != registry.BINARY || size < journalHeaderLen || size > journalMaxBytes {
		return nil, errors.New("invalid proxy backup registry value")
	}
	data := make([]byte, size)
	n, kind, err := j.key.GetValue(journalValueName, data)
	if err != nil {
		return nil, err
	}
	if kind != registry.BINARY || n != len(data) {
		return nil, errors.New("proxy backup changed while reading")
	}
	return decodeJournal(data)
}

func (j registryJournal) Save(snapshot ProxySnapshot) error {
	data, err := encodeJournal(snapshot)
	if err != nil {
		return err
	}
	present, err := j.Load()
	if err != nil {
		return err
	}
	if present != nil {
		return errors.New("proxy backup already exists")
	}
	if err := j.key.SetBinaryValue(journalValueName, data); err != nil {
		return err
	}
	// A sync failure means the write may or may not survive a crash. Keep the
	// value and let the caller fail closed; never proceed to proxy mutation.
	return j.sync()
}

func (j registryJournal) Clear() error {
	if err := j.key.DeleteValue(journalValueName); err != nil {
		return err
	}
	return j.sync()
}
