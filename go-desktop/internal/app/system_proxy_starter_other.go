//go:build !windows

package app

import (
	"context"
	"io"
)

func platformSystemProxyStart() func(context.Context, int) (io.Closer, error) { return nil }
