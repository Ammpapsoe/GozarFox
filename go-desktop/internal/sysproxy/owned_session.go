package sysproxy

import (
	"context"
	"errors"
	"sync"
)

// ownedSession keeps the OS-thread-owned lock until restoration has completed.
// Its caller must keep the local proxy listener alive until Close returns.
type ownedSession struct {
	cancel context.CancelFunc
	done   chan struct{}
	once   sync.Once
	err    error // published before done closes
}

func startOwnedSession(ctx context.Context, lock proxyOwnership, store proxyStore, journal proxyJournal, port int) (*ownedSession, error) {
	if ctx == nil {
		return nil, errors.New("missing proxy session context")
	}
	sessionCtx, cancel := context.WithCancel(ctx)
	s := &ownedSession{cancel: cancel, done: make(chan struct{})}
	ready := make(chan struct{}, 1)
	go func() {
		s.err = runOwnedProxySession(sessionCtx, lock, store, journal, port, func(ctx context.Context) error {
			ready <- struct{}{}
			<-ctx.Done()
			return nil
		})
		close(s.done)
	}()
	select {
	case <-ready:
		if err := ctx.Err(); err != nil {
			return nil, errors.Join(err, s.Close())
		}
		return s, nil
	case <-s.done:
		cancel()
		if s.err == nil {
			return nil, errors.New("proxy session ended before startup")
		}
		return nil, s.err
	case <-ctx.Done():
		return nil, errors.Join(ctx.Err(), s.Close())
	}
}

func (s *ownedSession) Close() error {
	if s == nil {
		return nil
	}
	s.once.Do(s.cancel)
	<-s.done
	return s.err
}
