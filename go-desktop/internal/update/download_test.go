package update

import (
	"bytes"
	"context"
	"crypto/sha256"
	"fmt"
	"io"
	"net/http"
	"os"
	"path/filepath"
	"runtime"
	"strings"
	"testing"
)

func verifiedForData(t *testing.T, data []byte) *Verified {
	t.Helper()
	m := fixture()
	digest := sha256.Sum256(data)
	m.Artifacts[0].SHA256 = fmt.Sprintf("%x", digest)
	m.Artifacts[0].Size = int64(len(data))
	v, err := verifyFixture(t, m)
	if err != nil {
		t.Fatal(err)
	}
	return v
}

func TestVerifiedDownloadAndCache(t *testing.T) {
	data := []byte("release package fixture")
	v := verifiedForData(t, data)
	artifact := v.Manifest().Artifacts[0]
	client := testClient(map[string][]byte{artifact.URL: data})
	directory := filepath.Join(t.TempDir(), "updates")
	path, err := downloadWithClient(context.Background(), client, v, artifact.ID, directory)
	if err != nil {
		t.Fatal(err)
	}
	got, err := os.ReadFile(path)
	if err != nil || !bytes.Equal(got, data) {
		t.Fatalf("download mismatch: %v", err)
	}
	second, err := downloadWithClient(context.Background(), testClient(nil), v, artifact.ID, directory)
	if err != nil || second != path {
		t.Fatalf("valid cached artifact was not reused: %v", err)
	}
	if len(v.ArtifactsFor("linux", "amd64")) != 1 || len(v.ArtifactsFor("windows", "amd64")) != 0 {
		t.Fatal("incorrect platform selection")
	}
}

func TestFailedOrTamperedDownloadsLeaveNoPartialFile(t *testing.T) {
	data := []byte("good payload")
	v := verifiedForData(t, data)
	artifact := v.Manifest().Artifacts[0]
	for _, payload := range [][]byte{[]byte("evil payload"), data[:4], append(data, '!')} {
		directory := filepath.Join(t.TempDir(), "updates")
		// An unknown Content-Length exercises streaming bounds as well as hashes.
		client := &http.Client{Transport: transportFunc(func(req *http.Request) (*http.Response, error) {
			return &http.Response{StatusCode: 200, Body: io.NopCloser(bytes.NewReader(payload)), Header: make(http.Header), Request: req, ContentLength: -1}, nil
		})}
		if _, err := downloadWithClient(context.Background(), client, v, artifact.ID, directory); err == nil {
			t.Fatal("accepted tampered or incorrectly sized download")
		}
		entries, err := os.ReadDir(directory)
		if err != nil || len(entries) != 0 {
			t.Fatalf("failure left an update file: %v %v", entries, err)
		}
	}
}

func TestExistingUnverifiedFileIsNotOverwritten(t *testing.T) {
	data := []byte("good payload")
	v := verifiedForData(t, data)
	a := v.Manifest().Artifacts[0]
	dir := filepath.Join(t.TempDir(), "updates")
	if err := os.Mkdir(dir, 0700); err != nil {
		t.Fatal(err)
	}
	path := filepath.Join(dir, a.SHA256[:32]+"-"+a.Name)
	bad := []byte(strings.Repeat("x", len(data)))
	if err := os.WriteFile(path, bad, 0600); err != nil {
		t.Fatal(err)
	}
	if _, err := downloadWithClient(context.Background(), testClient(map[string][]byte{a.URL: data}), v, a.ID, dir); err == nil {
		t.Fatal("accepted an unverified existing file")
	}
	got, _ := os.ReadFile(path)
	if !bytes.Equal(got, bad) {
		t.Fatal("overwrote an existing unverified file")
	}
}

func TestSymlinkTargetIsRejected(t *testing.T) {
	if runtime.GOOS == "windows" {
		t.Skip("unprivileged Windows symlinks are not generally available")
	}
	data := []byte("good payload")
	v := verifiedForData(t, data)
	a := v.Manifest().Artifacts[0]
	dir := filepath.Join(t.TempDir(), "updates")
	if err := os.Mkdir(dir, 0700); err != nil {
		t.Fatal(err)
	}
	outside := filepath.Join(t.TempDir(), "outside")
	if err := os.WriteFile(outside, data, 0600); err != nil {
		t.Fatal(err)
	}
	path := filepath.Join(dir, a.SHA256[:32]+"-"+a.Name)
	if err := os.Symlink(outside, path); err != nil {
		t.Fatal(err)
	}
	if _, err := downloadWithClient(context.Background(), testClient(nil), v, a.ID, dir); err == nil {
		t.Fatal("accepted a symlink as a cached update")
	}
}
