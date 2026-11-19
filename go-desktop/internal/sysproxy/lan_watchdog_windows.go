//go:build windows

package sysproxy

import (
	"context"
	"errors"
	"fmt"
	"io"
	"os"
	"sync"
	"time"
)

// RecoverSystemProxy handles a journal left when both the owner and its
// watchdog were terminated (for example, an OS restart). It never replaces a
// conflicting setting and coordinates with any live owner through the lock.
func RecoverSystemProxy(ctx context.Context) error {
	if ctx == nil {
		return errors.New("missing recovery context")
	}
	if err := ctx.Err(); err != nil {
		return err
	}
	b, err := openWindowsBackend()
	if err != nil {
		return err
	}
	defer b.Close()
	return b.lock(ctx, func(bool) error {
		old, err := b.journal.Load()
		if err != nil {
			return err
		}
		if old != nil {
			return errors.New("legacy proxy recovery journal exists")
		}
		return recoverLAN(wininetLANStore{}, lanRegistryJournal{b.journal})
	})
}

// RunRecoveryWatchdog is the internal crash-recovery child. The caller
// launches it before mutation and holds the ownership lock while connected.
func RunRecoveryWatchdog() error {
	b, err := openWindowsBackend()
	if err != nil {
		return err
	}
	defer b.Close()
	ctx, cancel := context.WithTimeout(context.Background(), 5*time.Second)
	defer cancel()
	if err := b.lock(ctx, func(bool) error { return nil }); err != nil {
		return err
	}
	return serveRecoveryWatchdog(os.Stdin, os.Stdout, func() error {
		return b.lock(context.Background(), func(bool) error {
			return recoverLAN(wininetLANStore{}, lanRegistryJournal{b.journal})
		})
	})
}

type lanSession struct {
	cancel   context.CancelFunc
	done     chan struct{}
	watch    *recoveryWatchdog
	backend  *windowsBackend
	once     sync.Once
	err      error
	failures chan error
}

func (s *lanSession) Failures() <-chan error { return s.failures }
func (s *lanSession) Close() error {
	s.once.Do(func() {
		s.cancel()
		<-s.done
		s.err = errors.Join(s.err, s.watch.stop())
		s.backend.Close()
	})
	return s.err
}

func StartSystemProxy(ctx context.Context, port int) (io.Closer, error) {
	if ctx == nil {
		return nil, errors.New("missing session context")
	}
	if err := ctx.Err(); err != nil {
		return nil, err
	}
	if port < 1 || port > 65535 {
		return nil, errors.New("invalid proxy port")
	}
	b, err := openWindowsBackend()
	if err != nil {
		return nil, err
	}
	watch, err := launchWatchdog("--internal-proxy-watchdog")
	if err != nil {
		b.Close()
		return nil, err
	}
	sessionCtx, cancel := context.WithCancel(ctx)
	s := &lanSession{cancel: cancel, done: make(chan struct{}), watch: watch, backend: b, failures: make(chan error, 1)}
	go func() { <-watch.done; cancel() }()
	ready := make(chan struct{}, 1)
	go func() {
		s.err = b.lock(sessionCtx, func(bool) (result error) {
			if err := sessionCtx.Err(); err != nil {
				return err
			}
			// Never ignore or replace the old experimental format's journal.
			old, err := b.journal.Load()
			if err != nil {
				return err
			}
			if old != nil {
				return errors.New("legacy proxy recovery journal exists")
			}
			store, journal := wininetLANStore{}, lanRegistryJournal{b.journal}
			if err := activateLAN(store, journal, port); err != nil {
				if recoveryErr := recoverLAN(store, journal); recoveryErr != nil {
					return errors.Join(err, recoveryErr)
				}
				return err
			}
			defer func() {
				if err := recoverLAN(store, journal); err != nil {
					result = errors.Join(result, fmt.Errorf("restore LAN session: %w", err))
				}
			}()
			if err := sessionCtx.Err(); err != nil {
				return err
			}
			ready <- struct{}{}
			<-sessionCtx.Done()
			return nil
		})
		close(s.done)
	}()
	select {
	case <-ready:
		if err := ctx.Err(); err != nil {
			return nil, errors.Join(err, s.Close())
		}
		select {
		case <-watch.done:
			return nil, errors.Join(errors.New("LAN watchdog exited during startup"), s.Close())
		default:
		}
		go func() {
			<-watch.done
			select {
			case <-s.done:
			default:
				cancel()
				s.failures <- errors.New("LAN recovery watchdog stopped unexpectedly")
			}
			close(s.failures)
		}()
		return s, nil
	case <-s.done:
		err := s.err
		cancel()
		err = errors.Join(err, watch.stop())
		b.Close()
		if err == nil {
			err = errors.New("LAN session ended before startup")
		}
		return nil, err
	case <-ctx.Done():
		return nil, errors.Join(ctx.Err(), s.Close())
	}
}
