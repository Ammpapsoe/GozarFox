//go:build !windows

package config

import (
	"crypto/aes"
	"crypto/cipher"
	"crypto/rand"
	"encoding/base64"
	"errors"
	"os"
	"path/filepath"
)

func keyFile() string { return filepath.Join(Dir(), "session.key") }

func key() ([]byte, error) {
	data, err := os.ReadFile(keyFile())
	if err == nil && len(data) == 32 {
		return data, nil
	}
	fresh := make([]byte, 32)
	if _, err := rand.Read(fresh); err != nil {
		return nil, err
	}
	temp, err := os.CreateTemp(Dir(), ".session-key-*")
	if err != nil {
		return nil, err
	}
	name := temp.Name()
	defer os.Remove(name)
	if err := temp.Chmod(0o600); err != nil {
		temp.Close()
		return nil, err
	}
	if _, err := temp.Write(fresh); err != nil {
		temp.Close()
		return nil, err
	}
	if err := temp.Close(); err != nil {
		return nil, err
	}
	if err := os.Rename(name, keyFile()); err != nil {
		return nil, err
	}
	return fresh, nil
}

func protect(plain []byte) (string, error) {
	secret, err := key()
	if err != nil {
		return "", err
	}
	block, err := aes.NewCipher(secret)
	if err != nil {
		return "", err
	}
	gcm, err := cipher.NewGCM(block)
	if err != nil {
		return "", err
	}
	nonce := make([]byte, gcm.NonceSize())
	if _, err := rand.Read(nonce); err != nil {
		return "", err
	}
	packed := gcm.Seal(nonce, nonce, plain, []byte("gozar"))
	return base64.RawStdEncoding.EncodeToString(packed), nil
}

func unprotect(text string) ([]byte, error) {
	secret, err := key()
	if err != nil {
		return nil, err
	}
	raw, err := base64.RawStdEncoding.DecodeString(text)
	if err != nil {
		return nil, err
	}
	block, err := aes.NewCipher(secret)
	if err != nil {
		return nil, err
	}
	gcm, err := cipher.NewGCM(block)
	if err != nil {
		return nil, err
	}
	if len(raw) < gcm.NonceSize() {
		return nil, errors.New("short session payload")
	}
	return gcm.Open(nil, raw[:gcm.NonceSize()], raw[gcm.NonceSize():], []byte("gozar"))
}
