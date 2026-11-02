//go:build windows

package wintun

import (
	"crypto/sha256"
	"encoding/hex"
	"errors"
	"os"
	"path/filepath"

	"golang.org/x/sys/windows"
)

func Ensure() error {
	base := os.Getenv("LOCALAPPDATA")
	if base == "" {
		return errors.New("the per-user application folder is unavailable")
	}
	sum := sha256.Sum256(library)
	folder := filepath.Join(base, "Gozar", hex.EncodeToString(sum[:8]))
	if err := os.MkdirAll(folder, 0o700); err != nil {
		return err
	}
	target := filepath.Join(folder, "wintun.dll")
	if err := install(target, sum[:]); err != nil {
		return err
	}
	if _, err := windows.LoadLibraryEx(target, 0, windows.LOAD_LIBRARY_SEARCH_SYSTEM32|windows.LOAD_LIBRARY_SEARCH_DLL_LOAD_DIR); err != nil {
		return err
	}
	if err := windows.SetDefaultDllDirectories(windows.LOAD_LIBRARY_SEARCH_SYSTEM32 | windows.LOAD_LIBRARY_SEARCH_USER_DIRS); err != nil {
		return err
	}
	wide, err := windows.UTF16PtrFromString(folder)
	if err != nil {
		return err
	}
	if _, err := windows.AddDllDirectory(wide); err != nil {
		return err
	}
	return nil
}

func install(target string, want []byte) error {
	current, err := os.ReadFile(target)
	if err == nil && verify(current, want) {
		return nil
	}
	temp, err := os.CreateTemp(filepath.Dir(target), ".wintun-*")
	if err != nil {
		return err
	}
	name := temp.Name()
	defer os.Remove(name)
	if err := temp.Chmod(0o600); err != nil {
		temp.Close()
		return err
	}
	if _, err := temp.Write(library); err != nil {
		temp.Close()
		return err
	}
	if err := temp.Sync(); err != nil {
		temp.Close()
		return err
	}
	if err := temp.Close(); err != nil {
		return err
	}
	written, err := os.ReadFile(name)
	if err != nil || !verify(written, want) {
		return errors.New("the extracted wintun library failed verification")
	}
	return os.Rename(name, target)
}

func verify(data []byte, want []byte) bool {
	sum := sha256.Sum256(data)
	return hex.EncodeToString(sum[:]) == hex.EncodeToString(want)
}
