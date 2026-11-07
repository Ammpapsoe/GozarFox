package update

import (
	"bytes"
	"context"
	"fmt"
	"io"
	"net/http"
	"os"
	"path/filepath"
	"sync/atomic"
	"testing"
	"time"
)

func TestConcurrentDownloadsAreAtomicAcrossFiftyRaces(t *testing.T) {
	data := bytes.Repeat([]byte("release data"), 8192)
	verified := verifiedForData(t, data)
	artifact := verified.Manifest().Artifacts[0]
	root := t.TempDir()
	for round := 0; round < 50; round++ {
		directory := filepath.Join(root, fmt.Sprintf("round-%d", round))
		var requests atomic.Int32
		client := &http.Client{Transport: transportFunc(func(req *http.Request) (*http.Response, error) {
			requests.Add(1)
			return &http.Response{StatusCode: 200, Body: io.NopCloser(bytes.NewReader(data)), Header: make(http.Header), Request: req, ContentLength: int64(len(data))}, nil
		})}
		start := make(chan struct{})
		ready := make(chan struct{}, 2)
		type result struct {
			path string
			err  error
		}
		results := make(chan result, 2)
		for worker := 0; worker < 2; worker++ {
			go func() {
				ready <- struct{}{}
				<-start
				ctx, cancel := context.WithTimeout(context.Background(), 10*time.Second)
				defer cancel()
				path, err := downloadWithClient(ctx, client, verified, artifact.ID, directory)
				results <- result{path, err}
			}()
		}
		<-ready
		<-ready
		close(start)
		first, second := <-results, <-results
		if first.err != nil || second.err != nil || first.path != second.path {
			t.Fatalf("round %d: first=%+v second=%+v", round, first, second)
		}
		if requests.Load() != 1 {
			t.Fatalf("round %d: expected one network download, got %d", round, requests.Load())
		}
		got, err := os.ReadFile(first.path)
		if err != nil || !bytes.Equal(got, data) {
			t.Fatalf("round %d: published bytes differ: %v", round, err)
		}
		files, err := os.ReadDir(directory)
		if err != nil || len(files) != 1 {
			t.Fatalf("round %d: temporary files leaked: %v %v", round, files, err)
		}
	}
	artifactLocks.Lock()
	defer artifactLocks.Unlock()
	if len(artifactLocks.items) != 0 {
		t.Fatal("download lock registry leaked entries")
	}
}

func TestCancelledWaitersReleaseLocksAcrossFiftyRaces(t *testing.T) {
	for round := 0; round < 50; round++ {
		key := fmt.Sprintf("cancel-%d", round)
		release, err := lockArtifact(context.Background(), key)
		if err != nil {
			t.Fatal(err)
		}
		ctx, cancel := context.WithCancel(context.Background())
		result := make(chan error, 1)
		go func() {
			unlock, err := lockArtifact(ctx, key)
			if unlock != nil {
				unlock()
			}
			result <- err
		}()
		deadline := time.NewTimer(5 * time.Second)
		ticker := time.NewTicker(time.Millisecond)
		waiting := false
		for !waiting {
			artifactLocks.Lock()
			entry := artifactLocks.items[key]
			waiting = entry != nil && entry.refs == 2
			artifactLocks.Unlock()
			if waiting {
				break
			}
			select {
			case <-ticker.C:
			case <-deadline.C:
				cancel()
				release()
				ticker.Stop()
				t.Fatal("waiter did not register")
			}
		}
		ticker.Stop()
		deadline.Stop()
		cancel()
		select {
		case err := <-result:
			if err != context.Canceled {
				release()
				t.Fatalf("cancelled waiter returned %v", err)
			}
		case <-time.After(5 * time.Second):
			release()
			t.Fatal("cancelled waiter remained blocked")
		}
		release()
		artifactLocks.Lock()
		_, remains := artifactLocks.items[key]
		artifactLocks.Unlock()
		if remains {
			t.Fatal("cancelled waiter leaked a lock entry")
		}
	}
}
