//go:build windows

package sysproxy

import (
	"bufio"
	"context"
	"errors"
	"fmt"
	"io"
	"os"
	"os/exec"
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

type windowsBackend struct {
	journal registryJournal
	lock    proxyOwnership
	backup  registry.Key
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
	verify := func(handle windows.Handle) error {
		return verifyUserSecurity(handle, windows.SE_KERNEL_OBJECT, sid, mutexAttrs)
	}
	lock := func(ctx context.Context, fn func(bool) error) error {
		return withWindowsProxyLockSecurity(ctx, productionLockPrefix+sid, mutexAttrs, verify, fn)
	}
	return &windowsBackend{journal: registryJournal{key: backup}, lock: lock, backup: backup}, nil
}
func (b *windowsBackend) Close() { _ = b.backup.Close() }

type recoveryWatchdog struct {
	input io.WriteCloser
	done  chan struct{}
	err   error
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
