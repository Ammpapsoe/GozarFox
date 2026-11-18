package tunnel

import (
	"bytes"
	"errors"
	"io"
	"sync"
	"testing"
	"time"
)

// TestWriteBufferRoundTrips drives bytes through the ring with a writer and a
// reader that never agree on chunk sizes, which is the case a CONNECT body
// actually sees: the app writes MTU-sized pieces and the transport reads its own.
func TestWriteBufferRoundTrips(t *testing.T) {
	b := newWriteBuffer(64)
	want := bytes.Repeat([]byte("0123456789abcdef"), 500) // 8 KiB through a 64-byte ring
	go func() {
		for off := 0; off < len(want); off += 7 {
			end := off + 7
			if end > len(want) {
				end = len(want)
			}
			if _, err := b.Write(want[off:end]); err != nil {
				t.Errorf("write: %v", err)
				return
			}
		}
		b.Close()
	}()
	got, err := io.ReadAll(b)
	if err != nil {
		t.Fatalf("read: %v", err)
	}
	if !bytes.Equal(got, want) {
		t.Fatalf("round trip mismatch: got %d bytes, want %d", len(got), len(want))
	}
}

// TestWriteBufferWrapsTheRing forces the head and tail to wrap past the end of
// the backing array several times, which is where an off-by-one in the modular
// arithmetic shows up as corruption rather than as an error.
func TestWriteBufferWrapsTheRing(t *testing.T) {
	b := newWriteBuffer(8)
	done := make(chan []byte)
	go func() {
		var out []byte
		buf := make([]byte, 3) // deliberately not a divisor of 8
		for {
			n, err := b.Read(buf)
			out = append(out, buf[:n]...)
			if err == io.EOF {
				done <- out
				return
			}
			if err != nil {
				done <- nil
				return
			}
		}
	}()
	var want []byte
	for i := 0; i < 100; i++ {
		chunk := []byte{byte('a' + i%26), byte('0' + i%10), 0x00}
		if _, err := b.Write(chunk); err != nil {
			t.Fatalf("write %d: %v", i, err)
		}
		want = append(want, chunk...)
	}
	b.Close()
	got := <-done
	if !bytes.Equal(got, want) {
		t.Fatalf("wrap mismatch: got %d bytes, want %d", len(got), len(want))
	}
}

// TestWriteBufferBlocksWhileFull checks the whole point of the buffer: a writer
// may run ahead by the capacity and no further. Without the bound, an upload
// whose peer stalls would grow without limit; with it, the writer waits.
func TestWriteBufferBlocksWhileFull(t *testing.T) {
	const size = 32
	b := newWriteBuffer(size)

	n, err := b.Write(make([]byte, size))
	if err != nil || n != size {
		t.Fatalf("first write: n=%d err=%v", n, err)
	}

	wrote := make(chan int, 1)
	go func() {
		n, _ := b.Write([]byte("more"))
		wrote <- n
	}()

	select {
	case n := <-wrote:
		t.Fatalf("a write past the capacity returned %d without blocking", n)
	case <-time.After(50 * time.Millisecond):
	}

	// One byte of room is enough to make progress but not to finish: Write
	// keeps waiting until all of p is in, so the writer must still be
	// parked here. This is the bound doing its job, not a bug.
	if _, err := b.Read(make([]byte, 1)); err != nil {
		t.Fatalf("read: %v", err)
	}
	select {
	case n := <-wrote:
		t.Fatalf("writer finished with %d bytes after one byte of room, want it still parked", n)
	case <-time.After(50 * time.Millisecond):
	}

	// Draining the rest lets it complete, and it must report the full length
	// so a caller looping on partial writes terminates.
	rest := make([]byte, size)
	for drained := 0; drained < size-1; {
		n, err := b.Read(rest)
		if err != nil {
			t.Fatalf("drain: %v", err)
		}
		drained += n
	}
	select {
	case n := <-wrote:
		if n != 4 {
			t.Fatalf("released writer wrote %d bytes, want 4", n)
		}
	case <-time.After(2 * time.Second):
		t.Fatal("writer stayed blocked after the ring had room")
	}
}

// TestWriteBufferCloseKeepsBufferedBytes is the half-close case: the app calls
// CloseWrite after its last byte, and the transport must still deliver what was
// already buffered before it sees EOF. Dropping the tail here would truncate
// the end of every upload whose final write raced the close.
func TestWriteBufferCloseKeepsBufferedBytes(t *testing.T) {
	b := newWriteBuffer(64)
	if _, err := b.Write([]byte("last bytes")); err != nil {
		t.Fatal(err)
	}
	if err := b.Close(); err != nil {
		t.Fatal(err)
	}
	got, err := io.ReadAll(b)
	if err != nil {
		t.Fatalf("read after close: %v", err)
	}
	if string(got) != "last bytes" {
		t.Fatalf("close dropped buffered data: %q", got)
	}
}

// TestWriteBufferCloseWithErrorSurfacesTheReason checks that a failed tunnel
// open reports its real cause to the reader instead of masquerading as a clean
// end of stream, which would look like the peer closing normally.
func TestWriteBufferCloseWithErrorSurfacesTheReason(t *testing.T) {
	b := newWriteBuffer(64)
	want := errors.New("the proxy answered 403")
	b.CloseWithError(want)
	if _, err := b.Read(make([]byte, 8)); !errors.Is(err, want) {
		t.Fatalf("read after CloseWithError = %v, want %v", err, want)
	}
}

// TestWriteBufferReleasesBlockedWriterOnClose is the teardown path: a relay
// goroutine parked on a full ring must be let go when the stream dies, or the
// engine leaks a goroutine for every aborted upload.
func TestWriteBufferReleasesBlockedWriterOnClose(t *testing.T) {
	b := newWriteBuffer(8)
	if _, err := b.Write(make([]byte, 8)); err != nil {
		t.Fatal(err)
	}
	released := make(chan error, 1)
	go func() {
		_, err := b.Write(make([]byte, 8))
		released <- err
	}()
	time.Sleep(50 * time.Millisecond)
	b.Close()
	select {
	case err := <-released:
		if err == nil {
			t.Fatal("a write released by Close reported success; it wrote nothing")
		}
	case <-time.After(2 * time.Second):
		t.Fatal("Close did not release the blocked writer")
	}
}

// TestWriteBufferConcurrentWritesStreamsCleanly is a race-detector case: the
// old io.Pipe serialised writers through a mutex and this one must too, or two
// goroutines writing one stream would interleave bytes mid-slice.
func TestWriteBufferConcurrentWritesStreamsCleanly(t *testing.T) {
	b := newWriteBuffer(1024)
	const writers, perWriter = 4, 500

	var wg sync.WaitGroup
	for i := 0; i < writers; i++ {
		wg.Add(1)
		go func(id byte) {
			defer wg.Done()
			chunk := bytes.Repeat([]byte{id}, 16)
			for j := 0; j < perWriter; j++ {
				if _, err := b.Write(chunk); err != nil {
					t.Errorf("writer %d: %v", id, err)
					return
				}
			}
		}(byte('A' + i))
	}
	go func() {
		wg.Wait()
		b.Close()
	}()

	got, err := io.ReadAll(b)
	if err != nil {
		t.Fatalf("read: %v", err)
	}
	if len(got) != writers*perWriter*16 {
		t.Fatalf("lost bytes: got %d, want %d", len(got), writers*perWriter*16)
	}
	// Every chunk is uniform, so any interleaving would show up as a change
	// of byte inside a 16-byte group.
	for i := 0; i < len(got); i += 16 {
		for j := i + 1; j < i+16; j++ {
			if got[j] != got[i] {
				t.Fatalf("chunk at %d was interleaved", i)
			}
		}
	}
}

// TestWriteBufferReadAfterCloseIsEOF pins the end-of-stream contract that the
// HTTP transport relies on to finish a request body.
func TestWriteBufferReadAfterCloseIsEOF(t *testing.T) {
	b := newWriteBuffer(16)
	b.Close()
	if n, err := b.Read(make([]byte, 4)); err != io.EOF || n != 0 {
		t.Fatalf("Read after Close = %d, %v; want 0, io.EOF", n, err)
	}
}

// TestWriteBufferDefaultSizeIsNotTiny guards the constant: a CONNECT body is on
// the upload path, and a small ring would put the hand-off ceiling straight
// back where it was.
func TestWriteBufferDefaultSizeIsNotTiny(t *testing.T) {
	if writeBufferSize < 64*1024 {
		t.Fatalf("writeBufferSize = %d, too small to beat a pipe hand-off", writeBufferSize)
	}
	if got := len(newWriteBuffer(0).data); got != writeBufferSize {
		t.Fatalf("a zero size gave a %d-byte ring, want the default", got)
	}
}

// A write larger than the ring must remain contiguous even while another
// writer arrives during a full-buffer wait.
func TestWriteBufferSerializesLargeConcurrentWrites(t *testing.T) {
	for attempt := 0; attempt < 100; attempt++ {
		b := newWriteBuffer(3)
		var writers sync.WaitGroup
		writers.Add(2)
		go func() {
			defer writers.Done()
			if _, err := b.Write([]byte("AAAAAA")); err != nil {
				t.Errorf("first writer: %v", err)
			}
		}()
		first := make([]byte, 1)
		if _, err := io.ReadFull(b, first); err != nil {
			t.Fatal(err)
		}
		go func() {
			defer writers.Done()
			if _, err := b.Write([]byte("BBBBBB")); err != nil {
				t.Errorf("second writer: %v", err)
			}
		}()
		go func() {
			writers.Wait()
			b.Close()
		}()
		rest, err := io.ReadAll(b)
		if err != nil {
			t.Fatal(err)
		}
		if got := string(first) + string(rest); got != "AAAAAABBBBBB" {
			t.Fatalf("interleaved writes: %q", got)
		}
	}
}

func TestWriteBufferEmptyReadDoesNotBlock(t *testing.T) {
	b := newWriteBuffer(3)
	done := make(chan struct{})
	go func() {
		defer close(done)
		if n, err := b.Read(nil); n != 0 || err != nil {
			t.Errorf("empty read = %d, %v", n, err)
		}
	}()
	select {
	case <-done:
	case <-time.After(time.Second):
		b.Close()
		t.Fatal("empty read blocked")
	}
}
