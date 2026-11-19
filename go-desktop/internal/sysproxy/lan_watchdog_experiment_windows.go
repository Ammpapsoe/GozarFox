//go:build windows && proxyexperiment

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

// Experimental lifecycle only. No application entry point calls this code;
// the child mode is provided solely by the disposable runner test executable.
func RunExperimentalLANWatchdog() error {
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
			return recoverLAN(experimentalLANStore{}, experimentalLANJournal{b.journal})
		})
	})
}

type experimentalLANSession struct {
	cancel   context.CancelFunc
	done     chan struct{}
	watch    *recoveryWatchdog
	backend  *windowsBackend
	once     sync.Once
	err      error
	failures chan error
}

func (s *experimentalLANSession) Failures() <-chan error { return s.failures }
func (s *experimentalLANSession) Close() error {
	s.once.Do(func() {
		s.cancel()
		<-s.done
		s.err = errors.Join(s.err, s.watch.stop())
		s.backend.Close()
	})
	return s.err
}

func StartExperimentalLANSession(ctx context.Context, port int) (io.Closer, error) {
	if ctx == nil {
		return nil, errors.New("missing session context")
	}
	b, err := openWindowsBackend()
	if err != nil {
		return nil, err
	}
	watch, err := launchWatchdog("--internal-lan-proxy-watchdog")
	if err != nil {
		b.Close()
		return nil, err
	}
	sessionCtx, cancel := context.WithCancel(ctx)
	s := &experimentalLANSession{cancel: cancel, done: make(chan struct{}), watch: watch, backend: b, failures: make(chan error, 1)}
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
			store, journal := experimentalLANStore{}, experimentalLANJournal{b.journal}
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
