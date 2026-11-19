//go:build windows

package app

import (
	"context"
	"io"

	"github.com/Ammpapsoe/GozarFox/go-desktop/internal/sysproxy"
)

func platformSystemProxyStart() func(context.Context, int) (io.Closer, error) {
	return sysproxy.StartSystemProxy
}

func platformSystemProxyRecover() func(context.Context) error {
	return sysproxy.RecoverSystemProxy
}
