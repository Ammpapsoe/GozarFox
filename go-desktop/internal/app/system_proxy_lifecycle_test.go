package app

import (
	"context"
	"errors"
	"fmt"
	"io"
	"net"
	"strings"
	"testing"
	"time"

	"github.com/Ammpapsoe/GozarFox/go-desktop/internal/config"
)

type systemProxyTestSession struct {
	port     int
	closed   bool
	closeErr error
}

func (s *systemProxyTestSession) Close() error {
	conn, err := net.DialTimeout("tcp4", fmt.Sprintf("127.0.0.1:%d", s.port), time.Second)
	if err != nil {
		return fmt.Errorf("proxy listener was closed before system proxy restore: %w", err)
	}
	_ = conn.Close()
	s.closed = true
	return s.closeErr
}

func newSystemProxyLifecycleApp(t *testing.T, closeErr error) (*App, **systemProxyTestSession) {
	t.Helper()
	current := newProxyLifecycleApp(t)
	if err := current.SetMode(config.ModeSystemProxy); err != nil {
		t.Fatal(err)
	}
	var session *systemProxyTestSession
	current.systemProxyStart = func(ctx context.Context, port int) (io.Closer, error) {
		if err := ctx.Err(); err != nil {
			return nil, err
		}
		conn, err := net.DialTimeout("tcp4", fmt.Sprintf("127.0.0.1:%d", port), time.Second)
		if err != nil {
			return nil, err
		}
		_ = conn.Close()
		session = &systemProxyTestSession{port: port, closeErr: closeErr}
		return session, nil
	}
	return current, &session
}

func TestSystemProxyLifecycleKeepsListenerUntilRestore(t *testing.T) {
	current, session := newSystemProxyLifecycleApp(t, nil)
	if err := current.Connect(); err != nil {
		t.Fatal(err)
	}
	if *session == nil || !current.State().Live || current.State().Mode != string(config.ModeSystemProxy) {
		t.Fatal("system session not activated")
	}
	exerciseProxyLifecycleTraffic(t, current.State().Port)
	if err := current.Disconnect(); err != nil {
		t.Fatal(err)
	}
	if !(*session).closed || current.State().Live || current.systemProxy != nil || current.proxy != nil || current.modeLease != nil {
		t.Fatal("system session not cleaned up")
	}
}

func TestSystemProxyLifecycleReportsRestoreConflict(t *testing.T) {
	current, session := newSystemProxyLifecycleApp(t, errors.New("proxy setting changed externally"))
	if err := current.Connect(); err != nil {
		t.Fatal(err)
	}
	err := current.Disconnect()
	if err == nil || !strings.Contains(err.Error(), "changed externally") || !strings.Contains(current.State().Fault, "changed externally") {
		t.Fatalf("restore error was suppressed: %v", err)
	}
	if !(*session).closed || current.State().Live {
		t.Fatal("session was not closed")
	}
}
