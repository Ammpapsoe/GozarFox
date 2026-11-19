package sysproxy

import "fmt"

// proxyJournal implementations must save a complete snapshot durably before
// returning, return nil from Load only when no snapshot exists, and make Clear
// durable. The caller must hold an exclusive cross-process ownership lock
// across recovery, activation and restoration. This interface alone does not
// provide either durability or mutual exclusion.
type proxyJournal interface {
	Load() (*ProxySnapshot, error)
	Save(ProxySnapshot) error
	Clear() error
}

// recoverProxy must run under the ownership lock before a new activation. A
// conflict retains the journal. A failed journal write or clear may have taken
// effect despite returning an error; the caller must stop and explicitly retry
// recovery before performing any other changes. The restore refuses to
// overwrite values it recognises as externally changed, but its reads and
// writes are not an atomic transaction against other programs.
// commitProxyChanges notifies the platform only after the complete registry
// transaction. The final read-back catches platform normalization or changes
// made by another writer before the journal can be discarded.
func commitProxyChanges(store proxyStore, expected map[string]ProxyValue) error {
	if notifier, ok := store.(interface{ Commit() error }); ok {
		if err := notifier.Commit(); err != nil {
			return err
		}
	}
	for _, name := range proxyNames {
		value, err := store.Read(name)
		if err != nil {
			return err
		}
		if value != expected[name] {
			return fmt.Errorf("proxy setting %s changed after notification", name)
		}
	}
	return nil
}

func recoverProxy(store proxyStore, journal proxyJournal) error {
	snapshot, err := journal.Load()
	if err != nil {
		return fmt.Errorf("load proxy backup: %w", err)
	}
	if snapshot == nil {
		return nil
	}
	if err := restoreProxy(store, *snapshot); err != nil {
		return fmt.Errorf("restore proxy backup: %w", err)
	}
	if err := commitProxyChanges(store, snapshot.Previous); err != nil {
		return fmt.Errorf("notify restored proxy settings: %w", err)
	}
	if err := journal.Clear(); err != nil {
		return fmt.Errorf("clear proxy backup: %w", err)
	}
	return nil
}

// activateProxy saves the original state before the first registry write.
// Any failed apply retains the backup even if rollback appeared to succeed:
// recovery can safely retry instead of losing evidence of a partial failure.
func activateProxy(store proxyStore, journal proxyJournal, port int) error {
	if err := recoverProxy(store, journal); err != nil {
		return err
	}
	snapshot, err := captureProxy(store, port)
	if err != nil {
		return fmt.Errorf("capture proxy settings: %w", err)
	}
	if err := journal.Save(snapshot); err != nil {
		return fmt.Errorf("save proxy backup: %w", err)
	}
	if err := applyProxy(store, snapshot); err != nil {
		return fmt.Errorf("activate proxy: %w", err)
	}
	if err := commitProxyChanges(store, desired(port)); err != nil {
		return fmt.Errorf("notify activated proxy settings: %w", err)
	}
	return nil
}
