package update

import (
	"context"
	"crypto/sha256"
	"crypto/subtle"
	"encoding/hex"
	"errors"
	"fmt"
	"io"
	"net/http"
	"os"
	"path/filepath"
	"runtime"
	"sync"
	"time"
)

// ArtifactsFor lists signed packages for a platform without guessing the user's
// package manager. The returned values are copies.
func (v *Verified) ArtifactsFor(osName, arch string) []Artifact {
	var matches []Artifact
	if v != nil {
		for _, artifact := range v.manifest.Artifacts {
			if artifact.OS == osName && artifact.Arch == arch {
				matches = append(matches, artifact)
			}
		}
	}
	return matches
}

func CacheDirectory() (string, error) {
	root, err := os.UserCacheDir()
	if err != nil || root == "" {
		return "", errors.New("user cache directory unavailable")
	}
	return filepath.Join(root, "Gozar", "updates"), nil
}

// Download saves a newer, signed artifact to a private cache. Downloading is
// not installation; the caller must obtain consent and use a platform-aware
// installation mechanism after the file has been verified.
func (v *Verified) Download(ctx context.Context, artifactID string) (string, error) {
	directory, err := CacheDirectory()
	if err != nil {
		return "", err
	}
	ctx, cancel := context.WithTimeout(ctx, 15*time.Minute)
	defer cancel()
	return downloadWithClient(ctx, updateHTTPClient, v, artifactID, directory)
}

func downloadWithClient(ctx context.Context, client *http.Client, v *Verified, artifactID, directory string) (string, error) {
	if v == nil || !v.newer {
		return "", errors.New("no authenticated newer release")
	}
	var selected *Artifact
	for _, item := range v.manifest.Artifacts {
		if item.ID == artifactID {
			copy := item
			selected = &copy
			break
		}
	}
	if selected == nil {
		return "", errors.New("artifact not found in authenticated release")
	}
	if directory == "" {
		return "", errors.New("empty update directory")
	}
	if err := os.MkdirAll(directory, 0700); err != nil {
		return "", err
	}
	info, err := os.Lstat(directory)
	if err != nil || !info.IsDir() || info.Mode()&os.ModeSymlink != 0 {
		return "", errors.New("update directory is not a real directory")
	}
	// Windows protects the user cache with ACLs rather than Unix permission bits.
	if runtime.GOOS != "windows" && info.Mode().Perm()&0077 != 0 {
		return "", errors.New("update directory is not private")
	}
	directory, err = filepath.EvalSymlinks(directory)
	if err != nil {
		return "", err
	}
	directory, err = filepath.Abs(directory)
	if err != nil {
		return "", err
	}
	target := filepath.Join(directory, selected.SHA256[:32]+"-"+selected.Name)
	unlock, err := lockArtifact(ctx, target)
	if err != nil {
		return "", err
	}
	defer unlock()
	if _, err := os.Lstat(target); err == nil {
		if err := verifyExisting(target, *selected); err != nil {
			return "", fmt.Errorf("existing update file is unsafe: %w", err)
		}
		return target, nil
	} else if !os.IsNotExist(err) {
		return "", err
	}

	req, err := http.NewRequestWithContext(ctx, http.MethodGet, selected.URL, nil)
	if err != nil {
		return "", err
	}
	req.Header.Set("User-Agent", "Gozar desktop updater")
	resp, err := client.Do(req)
	if err != nil {
		return "", err
	}
	defer resp.Body.Close()
	if resp.StatusCode != http.StatusOK {
		return "", fmt.Errorf("update server returned HTTP %d", resp.StatusCode)
	}
	if resp.ContentLength >= 0 && resp.ContentLength != selected.Size {
		return "", errors.New("update size does not match signed manifest")
	}
	temporary, err := os.CreateTemp(directory, ".gozar-update-*.part")
	if err != nil {
		return "", err
	}
	temporaryPath := temporary.Name()
	defer os.Remove(temporaryPath)
	defer temporary.Close()
	hash := sha256.New()
	size, err := io.Copy(io.MultiWriter(temporary, hash), io.LimitReader(resp.Body, selected.Size+1))
	if err != nil || size != selected.Size || !digestMatches(hash.Sum(nil), selected.SHA256) {
		return "", errors.New("download does not match signed size and digest")
	}
	if err := temporary.Sync(); err != nil {
		return "", err
	}
	if err := temporary.Close(); err != nil {
		return "", err
	}
	if err := os.Rename(temporaryPath, target); err != nil {
		// A separate process may have completed the same verified download.
		if existingErr := verifyExisting(target, *selected); existingErr != nil {
			return "", err
		}
	}
	return target, nil
}

func verifyExisting(path string, artifact Artifact) error {
	info, err := os.Lstat(path)
	if err != nil || !info.Mode().IsRegular() || info.Size() != artifact.Size {
		return errors.New("not a regular file of the expected size")
	}
	file, err := os.Open(path)
	if err != nil {
		return err
	}
	defer file.Close()
	opened, err := file.Stat()
	if err != nil || !os.SameFile(info, opened) {
		return errors.New("file changed while opening")
	}
	hash := sha256.New()
	n, err := io.Copy(hash, io.LimitReader(file, artifact.Size+1))
	if err != nil || n != artifact.Size || !digestMatches(hash.Sum(nil), artifact.SHA256) {
		return errors.New("cached digest mismatch")
	}
	return nil
}

func digestMatches(actual []byte, expectedHex string) bool {
	expected, err := hex.DecodeString(expectedHex)
	return err == nil && subtle.ConstantTimeCompare(actual, expected) == 1
}

type artifactLock struct {
	gate chan struct{}
	refs int
}

var artifactLocks = struct {
	sync.Mutex
	items map[string]*artifactLock
}{items: make(map[string]*artifactLock)}

// Same-target callers serialize verification and publication; entries are
// removed after the last waiter so checking many releases does not leak locks.
func lockArtifact(ctx context.Context, key string) (func(), error) {
	artifactLocks.Lock()
	entry := artifactLocks.items[key]
	if entry == nil {
		entry = &artifactLock{gate: make(chan struct{}, 1)}
		entry.gate <- struct{}{}
		artifactLocks.items[key] = entry
	}
	entry.refs++
	artifactLocks.Unlock()
	releaseRef := func() {
		artifactLocks.Lock()
		entry.refs--
		if entry.refs == 0 {
			delete(artifactLocks.items, key)
		}
		artifactLocks.Unlock()
	}
	select {
	case <-ctx.Done():
		releaseRef()
		return nil, ctx.Err()
	case <-entry.gate:
		if err := ctx.Err(); err != nil {
			entry.gate <- struct{}{}
			releaseRef()
			return nil, err
		}
		return func() {
			entry.gate <- struct{}{}
			releaseRef()
		}, nil
	}
}
