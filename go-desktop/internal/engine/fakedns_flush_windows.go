//go:build windows

package engine

import (
	"context"
	"fmt"
	"os/exec"
	"path/filepath"
	"syscall"
	"time"

	"golang.org/x/sys/windows"
)

// Use Microsoft's documented ipconfig operation, not an undocumented dnsapi
// entry point. This affects the machine's Windows resolver cache only, not
// browser, application, upstream, or external VPN caches. It does not prove
// synthetic identities are unique across independent implementations.
func flushSyntheticDNSSystemCache() error {
	system, err := windows.GetSystemDirectory()
	if err != nil {
		return err
	}
	ctx, cancel := context.WithTimeout(context.Background(), 10*time.Second)
	defer cancel()
	cmd := exec.CommandContext(ctx, filepath.Join(system, "ipconfig.exe"), "/flushdns")
	cmd.SysProcAttr = &syscall.SysProcAttr{HideWindow: true, CreationFlags: windows.CREATE_NO_WINDOW}
	if err := cmd.Run(); err != nil {
		if ctx.Err() != nil {
			return fmt.Errorf("Windows DNS flush timed out: %w", ctx.Err())
		}
		return fmt.Errorf("Windows DNS flush command failed: %w", err)
	}
	return nil
}
