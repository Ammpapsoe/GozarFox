package tunnel

import (
	"errors"
	"io"
	"sync"
)

// writeBuffer is the request body of a CONNECT tunnel: a bounded ring that a
// writer fills and the HTTP transport drains.
//
// It replaces io.Pipe here, and the difference is not cosmetic. io.Pipe has no
// buffer at all: every Write returns only once a reader has taken the bytes, so
// the local connection's read pump is coupled to the transport's send loop and
// each Write costs a goroutine hand-off. On an upload that ceiling is the
// hand-off rate, not the link's capacity, and the relay buffer upstream cannot
// help because it never holds more than the pipe does.
//
// With a ring the writer runs ahead by up to the capacity, so the transport
// drains large reads while the app keeps writing. The capacity is deliberately
// smaller than an HTTP/2 stream window: the window is what actually allows data
// on the wire, and buffering far past it would only turn flow-control backpressure
// into memory growth without moving bytes any faster.
const writeBufferSize = 128 * 1024

var errWriteBufferClosed = errors.New("tunnel: write buffer is closed")

type writeBuffer struct {
	writeMu  sync.Mutex // serializes whole writes even when the ring is full
	mu       sync.Mutex
	notEmpty *sync.Cond
	notFull  *sync.Cond

	data  []byte
	head  int // index of the first buffered byte
	size  int // bytes buffered
	limit int // capacity, kept separately because data may be reused

	err    error // why the stream ended, if not a clean close
	closed bool  // no more Writes
}

func newWriteBuffer(size int) *writeBuffer {
	if size < 1 {
		size = writeBufferSize
	}
	b := &writeBuffer{data: make([]byte, size), limit: size}
	b.notEmpty = sync.NewCond(&b.mu)
	b.notFull = sync.NewCond(&b.mu)
	return b
}

// Write appends p, blocking while the ring is full. Writes are serialized
// across waits, so bytes from concurrent calls cannot interleave; a short
// Write is only returned on error.
func (b *writeBuffer) Write(p []byte) (int, error) {
	b.writeMu.Lock()
	defer b.writeMu.Unlock()
	b.mu.Lock()
	defer b.mu.Unlock()
	written := 0
	for len(p) > 0 {
		for b.size == b.limit && !b.closed {
			b.notFull.Wait()
		}
		if b.closed {
			// A clean Close still has to tell the writer why its bytes
			// will never be sent. Returning a nil error here would break
			// the io.Writer contract: n < len(p) with no error reads as
			// "wrote less on purpose".
			err := b.err
			if err == nil {
				err = errWriteBufferClosed
			}
			return written, err
		}
		// The free span may wrap, so copy up to the end of the ring and
		// loop for the remainder.
		tail := (b.head + b.size) % b.limit
		free := b.limit - b.size
		if run := b.limit - tail; run < free {
			free = run
		}
		if free > len(p) {
			free = len(p)
		}
		copied := copy(b.data[tail:tail+free], p[:free])
		b.size += copied
		written += copied
		p = p[copied:]
		b.notEmpty.Signal()
	}
	return written, nil
}

// Read drains buffered bytes, blocking while the ring is empty and the buffer
// is open. It returns io.EOF once the buffer is closed and empty, like io.Pipe.
func (b *writeBuffer) Read(p []byte) (int, error) {
	if len(p) == 0 {
		return 0, nil
	}
	b.mu.Lock()
	defer b.mu.Unlock()
	for b.size == 0 {
		if b.closed {
			if b.err != nil {
				return 0, b.err
			}
			return 0, io.EOF
		}
		b.notEmpty.Wait()
	}
	n := b.size
	if run := b.limit - b.head; run < n {
		n = run
	}
	if n > len(p) {
		n = len(p)
	}
	copied := copy(p[:n], b.data[b.head:b.head+n])
	b.head = (b.head + copied) % b.limit
	b.size -= copied
	b.notFull.Signal()
	return copied, nil
}

// Close ends the stream cleanly: buffered bytes are still readable, then Read
// returns io.EOF. A writer blocked on a full ring is released with
// errWriteBufferClosed.
func (b *writeBuffer) Close() error {
	return b.closeWith(nil)
}

// CloseWithError ends the stream with err, so a reader waiting for data sees
// the real reason instead of a clean end.
func (b *writeBuffer) CloseWithError(err error) error {
	if err == nil {
		return b.closeWith(nil)
	}
	return b.closeWith(err)
}

func (b *writeBuffer) closeWith(err error) error {
	b.mu.Lock()
	defer b.mu.Unlock()
	if b.closed {
		return nil
	}
	b.closed = true
	if err != nil {
		b.err = err
	}
	b.notEmpty.Broadcast()
	b.notFull.Broadcast()
	return nil
}
