package warp

import (
	"bytes"
	"context"
	"crypto/rand"
	"encoding/binary"
	"errors"
	"io"
	"sync"
	"time"
)

type endpoint struct {
	host string
	port int
}

// Only Cloudflare's MASQUE prefix and known service ports are considered. The
// dialer carries all probes through the already-authenticated Mozilla relay.
var candidates = []endpoint{
	{"162.159.198.2", 443}, {"162.159.198.3", 443},
	{"162.159.198.50", 443}, {"162.159.198.2", 8443},
}

func healthQuery(id uint16) []byte {
	message := make([]byte, 12)
	binary.BigEndian.PutUint16(message[:2], id)
	binary.BigEndian.PutUint16(message[2:4], 0x0100)
	binary.BigEndian.PutUint16(message[4:6], 1)
	return append(message, []byte{7, 'e', 'x', 'a', 'm', 'p', 'l', 'e', 3, 'c', 'o', 'm', 0, 0, 1, 0, 1}...)
}

func verifyUDP(ctx context.Context, session *Session) error {
	flow, err := session.OpenUDP("1.1.1.1", 53)
	if err != nil {
		return err
	}
	defer flow.Close()
	var nonce [2]byte
	if _, err := rand.Read(nonce[:]); err != nil {
		return err
	}
	id := binary.BigEndian.Uint16(nonce[:])
	query := healthQuery(id)
	type reply struct {
		data []byte
		err  error
	}
	done := make(chan reply, 1)
	go func() {
		if err := flow.WriteDatagram(query); err != nil {
			done <- reply{err: err}
			return
		}
		for {
			data, err := flow.ReadDatagram()
			if err != nil {
				done <- reply{err: err}
				return
			}
			if len(data) >= 12 && bytes.Equal(data[:2], nonce[:]) && data[2]&0x80 != 0 && data[3]&0x0f == 0 {
				done <- reply{data: data}
				return
			}
		}
	}()
	select {
	case result := <-done:
		if result.err != nil {
			return result.err
		}
		if len(result.data) < 12 {
			return errors.New("WARP UDP probe failed")
		}
		return nil
	case <-ctx.Done():
		_ = session.Close() // unblocks both the shared writer and reader
		return ctx.Err()
	}
}

// Scan chooses the first endpoint to complete authentication, CONNECT-IP and a
// real UDP request/response. A TCP handshake alone is never called success.
func Scan(ctx context.Context, identity *Identity, dial Dialer) (*Session, error) {
	if identity == nil || !identity.Ready() || dial == nil {
		return nil, errors.New("WARP identity or upstream unavailable")
	}
	ctx, cancel := context.WithCancel(ctx)
	defer cancel()
	type result struct {
		session *Session
		err     error
	}
	results := make(chan result, len(candidates))
	var workers sync.WaitGroup
	for _, candidate := range candidates {
		candidate := candidate
		workers.Add(1)
		go func() {
			defer workers.Done()
			probe, stop := context.WithTimeout(ctx, 12*time.Second)
			defer stop()
			session, err := openSession(probe, identity, dial, candidate.host, candidate.port)
			if err == nil {
				err = verifyUDP(probe, session)
				if err != nil {
					_ = session.Close()
					session = nil
				}
			}
			results <- result{session, err}
		}()
	}
	go func() { workers.Wait(); close(results) }()
	var winner *Session
	for item := range results {
		if item.err == nil && item.session != nil && winner == nil {
			winner = item.session
			cancel() // cancel remaining probes; openSession detached the winner
		} else if item.session != nil {
			_ = item.session.Close()
		}
	}
	if winner == nil {
		return nil, errors.New("no WARP endpoint passed an authenticated UDP round trip")
	}
	return winner, nil
}

// Auto owns one verified session and re-scans after it is lost. Closing it
// cancels in-flight scans and revokes all datagram flows.
type Auto struct {
	ctx      context.Context
	cancel   context.CancelFunc
	identity *Identity
	dial     Dialer
	mu       sync.Mutex
	session  *Session
	closed   bool
}

func NewAuto(ctx context.Context, identity *Identity, dial Dialer) (*Auto, error) {
	lifetime, cancel := context.WithCancel(ctx)
	auto := &Auto{ctx: lifetime, cancel: cancel, identity: identity, dial: dial}
	session, err := Scan(lifetime, identity, dial)
	if err != nil {
		cancel()
		return nil, err
	}
	auto.session = session
	return auto, nil
}

func (a *Auto) OpenUDP(host string, port int) (*Flow, error) {
	a.mu.Lock()
	defer a.mu.Unlock()
	if a.closed || a.ctx.Err() != nil {
		return nil, io.ErrClosedPipe
	}
	if a.session == nil || !a.session.Alive() {
		session, err := Scan(a.ctx, a.identity, a.dial)
		if err != nil {
			return nil, err
		}
		a.session = session
	}
	return a.session.OpenUDP(host, port)
}

func (a *Auto) Close() error {
	a.cancel() // interrupt scans before waiting for the owner lock
	a.mu.Lock()
	defer a.mu.Unlock()
	if a.closed {
		return nil
	}
	a.closed = true
	if a.session != nil {
		return a.session.Close()
	}
	return nil
}
