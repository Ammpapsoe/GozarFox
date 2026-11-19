package app

import (
	"context"
	"errors"
	"io"
	"strings"
	"testing"
	"time"
)

type failingSystemProxySession struct {
	*systemProxyTestSession
	failures chan error
}

func (s *failingSystemProxySession) Failures() <-chan error { return s.failures }

func TestSystemProxyWatchdogFailureStopsConnection(t *testing.T) {
	current, _ := newSystemProxyLifecycleApp(t, nil)
	var session *failingSystemProxySession
	current.systemProxyStart = func(ctx context.Context, port int) (io.Closer, error) {
		session = &failingSystemProxySession{systemProxyTestSession: &systemProxyTestSession{port: port}, failures: make(chan error, 1)}
		return session, nil
	}
	if err := current.Connect(); err != nil {
		t.Fatal(err)
	}
	session.failures <- errors.New("watchdog exited")
	deadline := time.After(5 * time.Second)
	ticker := time.NewTicker(10 * time.Millisecond)
	defer ticker.Stop()
	for {
		select {
		case <-deadline:
			t.Fatal("watchdog failure did not stop connection")
		case <-ticker.C:
			state := current.State()
			if !state.Live && strings.Contains(state.Fault, "watchdog exited") {
				if err := current.Disconnect(); err != nil {
					t.Fatal(err)
				}
				if !session.closed {
					t.Fatal("system session was not restored before listener shutdown")
				}
				return
			}
		}
	}
}
