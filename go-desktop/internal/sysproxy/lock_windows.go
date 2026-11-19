//go:build windows

package sysproxy

import (
	"context"
	"errors"
	"fmt"
	"runtime"

	"golang.org/x/sys/windows"
)

// withWindowsProxyLock serializes only cooperating processes that use exactly
// the same name. It is not wired to production; the caller must first choose a
// per-user, cross-session name and an appropriate security descriptor/ACL.
// A Windows mutex is owned by an OS thread, not a goroutine: the callback runs
// on a locked thread and must complete before this function returns.
// Abandonment means the previous owner died while holding the mutex. Callers
// must recover and validate their journal before any new mutation regardless
// of this flag; an abandoned mutex can disappear when all handles are closed.
func withWindowsProxyLock(ctx context.Context, name string, fn func(abandoned bool) error) (result error) {
	if ctx == nil || name == "" || fn == nil {
		return errors.New("invalid proxy lock arguments")
	}
	if err := ctx.Err(); err != nil {
		return err
	}
	encoded, err := windows.UTF16PtrFromString(name)
	if err != nil {
		return err
	}
	runtime.LockOSThread()
	defer runtime.UnlockOSThread()
	handle, err := windows.CreateMutex(nil, false, encoded)
	if err != nil && !errors.Is(err, windows.ERROR_ALREADY_EXISTS) {
		return err
	}
	if handle == 0 {
		return errors.New("empty proxy mutex handle")
	}
	defer func() {
		if err := windows.CloseHandle(handle); err != nil {
			result = errors.Join(result, fmt.Errorf("close proxy mutex: %w", err))
		}
	}()
	for {
		if err := ctx.Err(); err != nil {
			return err
		}
		status, err := windows.WaitForSingleObject(handle, 100)
		if err != nil {
			return err
		}
		switch status {
		case windows.WAIT_OBJECT_0, windows.WAIT_ABANDONED:
			defer func() {
				if err := windows.ReleaseMutex(handle); err != nil {
					result = errors.Join(result, fmt.Errorf("release proxy mutex: %w", err))
				}
			}()
			if err := ctx.Err(); err != nil {
				return err
			}
			return fn(status == windows.WAIT_ABANDONED)
		case uint32(windows.WAIT_TIMEOUT):
			continue
		default:
			return fmt.Errorf("unexpected proxy mutex wait result: %d", status)
		}
	}
}
