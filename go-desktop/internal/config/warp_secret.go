package config

import (
	"errors"
	"os"
	"path/filepath"
)

const warpIdentityFile = "warp-device.enc"

// SaveWarpIdentity never falls back to plaintext. WARP enrollment credentials
// and the private device key must not enter logs, config JSON or public assets.
func SaveWarpIdentity(plain []byte) error {
	if len(plain) == 0 || len(plain) > 1<<20 {
		return errors.New("invalid WARP identity")
	}
	sealed, err := protect(plain)
	if err != nil || sealed == "" {
		return errors.New("WARP identity encryption unavailable")
	}
	file, err := os.CreateTemp(Dir(), ".warp-device-*")
	if err != nil {
		return err
	}
	name := file.Name()
	defer os.Remove(name)
	if err = file.Chmod(0o600); err == nil {
		_, err = file.WriteString(sealedPrefix + sealed)
	}
	if err == nil {
		err = file.Sync()
	}
	closeErr := file.Close()
	if err != nil {
		return err
	}
	if closeErr != nil {
		return closeErr
	}
	return os.Rename(name, filepath.Join(Dir(), warpIdentityFile))
}

func LoadWarpIdentity() ([]byte, error) {
	data, err := os.ReadFile(filepath.Join(Dir(), warpIdentityFile))
	if err != nil {
		return nil, err
	}
	if len(data) <= len(sealedPrefix) || len(data) > 2<<20 || string(data[:len(sealedPrefix)]) != sealedPrefix {
		return nil, errors.New("invalid sealed WARP identity")
	}
	return unprotect(string(data[len(sealedPrefix):]))
}
