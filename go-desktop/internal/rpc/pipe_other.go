//go:build !linux && !darwin && !windows

package rpc

import (
	"os/signal"
	"syscall"
)

func ignorePipe() {
	signal.Ignore(syscall.SIGPIPE)
}
