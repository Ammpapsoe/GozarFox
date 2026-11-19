//go:build windows && proxyexperiment

// Experimental backend: WinINet changes per-connection registry blobs that
// this journal does not yet capture. Never enable in production builds.

package sysproxy

import (
	"bufio"
	"context"
	"errors"
	"fmt"
	"io"
	"os"
	"os/exec"
	"sync"
	"syscall"
	"time"

	"golang.org/x/sys/windows"
	"golang.org/x/sys/windows/registry"
)

const productionJournalKey = `Software\GozarFox\SystemProxyRecoveryV1`
const productionLockPrefix = `Global\GozarFox.SystemProxy.V1.`

var internetSetOption = windows.NewLazySystemDLL("wininet.dll").NewProc("InternetSetOptionW")

func notifyWinINET() error {
	if err := internetSetOption.Find(); err != nil {
		return err
	}
	for _, option := range []uintptr{39, 37} { // SETTINGS_CHANGED, REFRESH
		ok, _, callErr := internetSetOption.Call(0, option, 0, 0)
		if ok == 0 {
			return fmt.Errorf("WinINet notification %d: %w", option, callErr)
		}
	}
	return nil
}

type notifyingStore struct {
	proxyStore
	notify func() error
}

func (s notifyingStore) Commit() error { return s.notify() }

type windowsBackend struct {
	store    proxyStore
	journal  registryJournal
	lock     proxyOwnership
	settings registry.Key
	backup   registry.Key
}

func openWindowsBackend() (*windowsBackend, error) {
	sid, keyAttrs, err := userSecurity("KA")
	if err != nil {
		return nil, err
	}
	lockSID, mutexAttrs, err := userSecurity("0x001f0001")
	if err != nil {
		return nil, err
	}
	if sid != lockSID {
		return nil, errors.New("proxy user changed")
	}
	backup, err := openSecureJournalKey(productionJournalKey, sid, keyAttrs)
	if err != nil {
		return nil, err
	}
	settings, err := registry.OpenKey(registry.CURRENT_USER, settingsKey, registry.QUERY_VALUE|registry.SET_VALUE)
	if err != nil {
		backup.Close()
		return nil, err
	}
	verify := func(handle windows.Handle) error {
		return verifyUserSecurity(handle, windows.SE_KERNEL_OBJECT, sid, mutexAttrs)
	}
	lock := func(ctx context.Context, fn func(bool) error) error {
		return withWindowsProxyLockSecurity(ctx, productionLockPrefix+sid, mutexAttrs, verify, fn)
	}
	return &windowsBackend{store: notifyingStore{registryProxyStore{key: settings}, notifyWinINET}, journal: registryJournal{key: backup}, lock: lock, settings: settings, backup: backup}, nil
}
func (b *windowsBackend) Close() { _ = b.settings.Close(); _ = b.backup.Close() }

// RunRecoveryWatchdog is only used by the internal watchdog subprocess. It
// cannot select arbitrary registry paths. The persistent journal is retained on
// conflict, so the next connection attempt reports the unresolved error.
func RunRecoveryWatchdog() error {
	backend, err := openWindowsBackend()
	if err != nil {
		return err
	}
	defer backend.Close()
	ctx, cancel := context.WithTimeout(context.Background(), 5*time.Second)
	defer cancel()
	if err := backend.lock(ctx, func(bool) error { return nil }); err != nil {
		return err
	}
	return serveRecoveryWatchdog(os.Stdin, os.Stdout, func() error {
		return backend.lock(context.Background(), func(bool) error { return recoverProxy(backend.store, backend.journal) })
	})
}

type recoveryWatchdog struct {
	input io.WriteCloser
	done  chan struct{}
	err   error
}

func launchRecoveryWatchdog() (*recoveryWatchdog, error) {
	return launchWatchdog("--internal-proxy-watchdog")
}

func launchWatchdog(mode string) (*recoveryWatchdog, error) {
	executable, err := os.Executable()
	if err != nil {
		return nil, err
	}
	cmd := exec.Command(executable, mode)
	cmd.SysProcAttr = &syscall.SysProcAttr{HideWindow: true, CreationFlags: windows.CREATE_NO_WINDOW}
	stdin, err := cmd.StdinPipe()
	if err != nil {
		return nil, err
	}
	stdout, err := cmd.StdoutPipe()
	if err != nil {
		stdin.Close()
		return nil, err
	}
	if err := cmd.Start(); err != nil {
		stdin.Close()
		return nil, err
	}
	ready := make(chan error, 1)
	go func() {
		line, err := bufio.NewReader(stdout).ReadString('\n')
		if err == nil && line != "READY\n" {
			err = errors.New("invalid watchdog handshake")
		}
		ready <- err
	}()
	watch := &recoveryWatchdog{input: stdin, done: make(chan struct{})}
	go func() { watch.err = cmd.Wait(); close(watch.done) }()
	select {
	case handshakeErr := <-ready:
		if handshakeErr == nil {
			return watch, nil
		}
		err = handshakeErr
	case <-watch.done:
		err = errors.New("recovery watchdog exited before handshake")
	case <-time.After(10 * time.Second):
		err = errors.New("recovery watchdog handshake timed out")
	}
	_ = stdin.Close()
	_ = cmd.Process.Kill()
	<-watch.done
	return nil, err
}

func (w *recoveryWatchdog) stop() error {
	_, writeErr := w.input.Write([]byte{'D'})
	closeErr := w.input.Close()
	<-w.done
	return errors.Join(writeErr, closeErr, w.err)
}

type systemSession struct {
	owned    *ownedSession
	watch    *recoveryWatchdog
	backend  *windowsBackend
	cancel   context.CancelFunc
	once     sync.Once
	err      error
	failures chan error
}

func (s *systemSession) Failures() <-chan error { return s.failures }
func (s *systemSession) Close() error {
	s.once.Do(func() {
		s.cancel()
		s.err = s.owned.Close()
		s.err = errors.Join(s.err, s.watch.stop())
		s.backend.Close()
	})
	return s.err
}

// StartSystemProxy owns the secure mutex for the full session and starts a
// separate crash recovery process before changing any WinINet settings.
func StartSystemProxy(ctx context.Context, port int) (io.Closer, error) {
	if ctx == nil {
		return nil, errors.New("missing system proxy context")
	}
	backend, err := openWindowsBackend()
	if err != nil {
		return nil, err
	}
	watch, err := launchRecoveryWatchdog()
	if err != nil {
		backend.Close()
		return nil, err
	}
	ownedCtx, cancel := context.WithCancel(ctx)
	// A watchdog that exits during startup must cancel activation too.
	go func() { <-watch.done; cancel() }()
	owned, err := startOwnedSession(ownedCtx, backend.lock, backend.store, backend.journal, port)
	if err != nil {
		cancel()
		stopErr := watch.stop()
		backend.Close()
		return nil, errors.Join(err, stopErr)
	}
	s := &systemSession{owned: owned, watch: watch, backend: backend, cancel: cancel, failures: make(chan error, 1)}
	go func() {
		<-watch.done
		select {
		case <-owned.done:
		default:
			cancel()
			s.failures <- errors.New("system proxy recovery watchdog stopped unexpectedly")
		}
		close(s.failures)
	}()
	return s, nil
}
