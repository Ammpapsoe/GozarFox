package rpc

import (
	"bufio"
	"bytes"
	"context"
	"encoding/json"
	"io"
	"sync"
	"testing"
	"time"

	"github.com/bardia049b-stack/Gozar1/go-desktop/internal/update"
)

type blockedUpdate struct {
	started   chan struct{}
	cancelled chan struct{}
	once      sync.Once
}

func (b *blockedUpdate) Check(ctx context.Context, _ string) (update.Summary, error) {
	close(b.started)
	select {
	case <-b.cancelled:
		return update.Summary{}, context.Canceled
	case <-ctx.Done():
		return update.Summary{}, ctx.Err()
	}
}
func (b *blockedUpdate) Download(context.Context, string) (string, error) { return "", io.EOF }
func (b *blockedUpdate) Cancel()                                          { b.once.Do(func() { close(b.cancelled) }) }

type captureWriter struct {
	mu     sync.Mutex
	data   bytes.Buffer
	writes chan struct{}
}

func (w *captureWriter) Write(p []byte) (int, error) {
	w.mu.Lock()
	n, err := w.data.Write(p)
	w.mu.Unlock()
	w.writes <- struct{}{}
	return n, err
}

func TestCancellationBeforeWorkerStartupAcrossFiftyRaces(t *testing.T) {
	for round := 0; round < 50; round++ {
		backend := &blockedUpdate{started: make(chan struct{}), cancelled: make(chan struct{})}
		server := &Server{updates: backend, ctx: context.Background()}
		ctx, done := server.registerUpdate()
		response := server.handleUpdate(Request{ID: 2, Method: "cancel_update"}, "")
		if response.Error != "" || ctx.Err() != context.Canceled {
			done()
			t.Fatalf("early cancellation did not reach pending worker: %v", ctx.Err())
		}
		response = server.handleUpdateContext(ctx, Request{ID: 1, Method: "check_update"}, "1.10.4")
		if response.Error != "update operation cancelled" {
			done()
			t.Fatalf("cancelled worker started: %+v", response)
		}
		done()
		server.pendingMu.Lock()
		remaining := len(server.pending)
		server.pendingMu.Unlock()
		if remaining != 0 {
			t.Fatal("pending update registration leaked")
		}
	}
}

func TestUpdateRPCRemainsResponsiveAcrossFiftyCancellationRaces(t *testing.T) {
	for round := 0; round < 50; round++ {
		backend := &blockedUpdate{started: make(chan struct{}), cancelled: make(chan struct{})}
		output := &captureWriter{writes: make(chan struct{}, 2)}
		server := &Server{updates: backend, out: output, ctx: context.Background()}
		server.startUpdate(Request{ID: 1, Method: "check_update"}, "1.10.4")
		select {
		case <-backend.started:
		case <-time.After(5 * time.Second):
			t.Fatal("update request did not start")
		}
		if err := server.emit(server.handleUpdate(Request{ID: 2, Method: "cancel_update"}, "")); err != nil {
			t.Fatal(err)
		}
		for n := 0; n < 2; n++ {
			select {
			case <-output.writes:
			case <-time.After(5 * time.Second):
				t.Fatal("RPC response was blocked")
			}
		}
		output.mu.Lock()
		reader := bufio.NewScanner(bytes.NewReader(append([]byte(nil), output.data.Bytes()...)))
		output.mu.Unlock()
		responses := map[int]Response{}
		for reader.Scan() {
			var response Response
			if err := json.Unmarshal(reader.Bytes(), &response); err != nil {
				t.Fatal(err)
			}
			responses[response.ID] = response
		}
		if len(responses) != 2 || responses[1].Error != "update operation cancelled" || responses[2].Error != "" {
			t.Fatalf("invalid or interleaved responses: %+v", responses)
		}
	}
}
