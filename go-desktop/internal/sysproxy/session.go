package sysproxy

import (
	"context"
	"errors"
	"fmt"
)

// proxyOwnership invokes fn while an exclusive cross-process lock is held.
// The owner must hold it until fn returns, including cleanup. An abandoned
// flag can be useful diagnostically but does not replace journal recovery.
type proxyOwnership func(context.Context, func(abandoned bool) error) error

// runOwnedProxySession is an inactive orchestration building block. The caller
// must first start a loopback listener, and must keep it alive until this
// function returns. run blocks for the desired session lifetime and observes
// ctx itself. Every access to store/journal requires the same ownership lock.
// A future production caller must select a per-user cross-session lock, secure
// the journal and propagate conflicts to the user before changing settings.
func runOwnedProxySession(ctx context.Context, lock proxyOwnership, store proxyStore, journal proxyJournal, port int, run func(context.Context) error) error {
	if ctx == nil || lock == nil || store == nil || journal == nil || run == nil {
		return errors.New("invalid proxy session arguments")
	}
	return lock(ctx, func(_ bool) (result error) {
		if err := ctx.Err(); err != nil {
			return err
		}
		if err := activateProxy(store, journal, port); err != nil {
			// The failed activation may have partially mutated the registry.
			// Recover under the same lock when safe; conflict or ambiguity
			// retains the durable journal and is reported to the caller.
			if recoveryErr := recoverProxy(store, journal); recoveryErr != nil {
				return errors.Join(err, fmt.Errorf("recover failed activation: %w", recoveryErr))
			}
			return err
		}
		defer func() {
			if err := recoverProxy(store, journal); err != nil {
				result = errors.Join(result, fmt.Errorf("end proxy session: %w", err))
			}
		}()
		if err := ctx.Err(); err != nil {
			return err
		}
		return run(ctx)
	})
}
