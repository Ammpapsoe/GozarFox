package sysproxy

import (
	"errors"
	"io"
)

// serveRecoveryWatchdog first proves that the child is running. The parent
// sends D after an orderly restore; unexpected pipe closure means the parent
// crashed or was killed and requires recovery under the shared OS lock.
func serveRecoveryWatchdog(input io.Reader, output io.Writer, recover func() error) error {
	if input == nil || output == nil || recover == nil {
		return errors.New("invalid recovery watchdog arguments")
	}
	if _, err := io.WriteString(output, "READY\n"); err != nil {
		return err
	}
	var command [1]byte
	n, err := io.ReadFull(input, command[:])
	if err == nil && n == 1 && command[0] == 'D' {
		return nil
	}
	if errors.Is(err, io.EOF) || errors.Is(err, io.ErrUnexpectedEOF) {
		return recover()
	}
	if err != nil {
		return err
	}
	return errors.New("invalid recovery watchdog command")
}
