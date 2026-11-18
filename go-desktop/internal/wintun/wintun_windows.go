//go:build windows

package wintun

import (
	"crypto/sha256"
	"encoding/hex"
	"errors"
	"fmt"
	"os"
	"path/filepath"

	"golang.org/x/sys/windows"
)

// Ensure makes a usable wintun.dll resolvable for the process.
//
// Two paths, in order:
//
//  1. the embedded copy, extracted to %LOCALAPPDATA%\Gozar\<hash> and verified
//     against its SHA-256 before it is loaded;
//  2. a copy already installed on the machine, if the embedded one cannot be
//     used. Some managed desktops quarantine any newly written DLL in the user
//     profile, and the old code turned that into a bare Win32 error, so the
//     tunnel simply refused to start with no explanation. Falling back to an
//     existing wintun.dll keeps those machines working.
//
// Every failure now names which step failed and what to do about it, because
// the previous single "LoadLibrary failed" message was indistinguishable from a
// missing file, a blocked file and a 32/64-bit mismatch.
func Ensure() error {
	embedded := ensureEmbedded()
	if embedded == nil {
		return nil
	}
	system := useSystem()
	if system == nil {
		return nil
	}
	return fmt.Errorf(
		"the tunnel driver could not be prepared: %v; no usable wintun.dll was found on this machine either (%v). "+
			"An antivirus or an application-control policy is the usual cause: allow the Gozar folder in %%LOCALAPPDATA%%, "+
			"or install Wintun yourself and start Gozar again",
		embedded, system,
	)
}

func ensureEmbedded() error {
	base := os.Getenv("LOCALAPPDATA")
	if base == "" {
		return errors.New("the per-user application folder is unavailable")
	}
	sum := sha256.Sum256(library)
	folder := filepath.Join(base, "Gozar", hex.EncodeToString(sum[:8]))
	if err := os.MkdirAll(folder, 0o700); err != nil {
		return fmt.Errorf("creating the driver folder failed (permission or antivirus): %w", err)
	}
	target := filepath.Join(folder, "wintun.dll")
	if err := install(target, sum[:]); err != nil {
		return fmt.Errorf("extracting wintun.dll to %s failed: %w", folder, err)
	}
	if _, err := windows.LoadLibraryEx(target, 0, windows.LOAD_LIBRARY_SEARCH_SYSTEM32|windows.LOAD_LIBRARY_SEARCH_DLL_LOAD_DIR); err != nil {
		return fmt.Errorf("loading %s failed (the file exists but Windows refused it, which usually means it was "+
			"quarantined or is the wrong architecture): %w", target, err)
	}
	return register(folder)
}

// useSystem looks for a wintun.dll that is already on the machine and makes its
// folder resolvable. The loading code asks Windows for "wintun.dll" by name, so
// putting the folder on the search path is all that is needed.
func useSystem() error {
	var tried []string
	for _, folder := range systemFolders() {
		candidate := filepath.Join(folder, "wintun.dll")
		info, err := os.Stat(candidate)
		if err != nil || info.IsDir() || info.Size() == 0 {
			continue
		}
		tried = append(tried, candidate)
		if _, err := windows.LoadLibraryEx(candidate, 0, windows.LOAD_LIBRARY_SEARCH_SYSTEM32|windows.LOAD_LIBRARY_SEARCH_DLL_LOAD_DIR); err != nil {
			continue
		}
		if err := register(folder); err != nil {
			continue
		}
		return nil
	}
	if len(tried) == 0 {
		return errors.New("no existing wintun.dll in System32 or next to the executable")
	}
	return fmt.Errorf("wintun.dll was found but could not be loaded: %v", tried)
}

func systemFolders() []string {
	var folders []string
	if root := os.Getenv("SystemRoot"); root != "" {
		folders = append(folders, filepath.Join(root, "System32"))
	}
	if exe, err := os.Executable(); err == nil {
		folders = append(folders, filepath.Dir(exe))
	}
	return folders
}

// register puts the driver folder on the DLL search path. It runs after a
// successful load, so the only way it fails is a policy that forbids user
// directories, which is worth reporting separately.
func register(folder string) error {
	if err := windows.SetDefaultDllDirectories(windows.LOAD_LIBRARY_SEARCH_SYSTEM32 | windows.LOAD_LIBRARY_SEARCH_USER_DIRS); err != nil {
		return fmt.Errorf("restricting the DLL search path failed: %w", err)
	}
	wide, err := windows.UTF16PtrFromString(folder)
	if err != nil {
		return fmt.Errorf("encoding the driver path failed: %w", err)
	}
	if _, err := windows.AddDllDirectory(wide); err != nil {
		return fmt.Errorf("adding %s to the DLL search path failed: %w", folder, err)
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
	// Rename fails when another process (an antivirus scanner, or a second
	// instance of this app) holds the target open. The caller falls back to the
	// system copy in that case instead of failing the whole start.
	return os.Rename(name, target)
}

func verify(data []byte, want []byte) bool {
	sum := sha256.Sum256(data)
	return hex.EncodeToString(sum[:]) == hex.EncodeToString(want)
}
