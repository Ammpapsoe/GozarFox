package killswitch

// Recovery from a kill switch that outlived its process.
//
// The kill switch is installed as firewall state, not as a handle, so nothing
// in the operating system removes it when the core dies. If the process is
// killed (a crash, the task manager, a power loss, an update that stops the
// service), Close never runs and the machine keeps the rules it was left with.
// On Windows that is the worst case: the profile default outbound action stays
// Block, so the user is offline for everything, including the browser they
// would use to search for a fix, and the tunnel that would restore access
// cannot start because the relay is blocked.
//
// Sweep runs once at startup, before anything connects. Disabling an absent
// kill switch is a no-op on every platform (nft deletes a table that is not
// there, pf flushes an empty anchor, and the Windows script removes rules that
// match a display-name prefix), so this is safe to call unconditionally.

// Stale reports whether rules from a previous run are still in place, and
// clears them. It is best-effort: an error means the rules could not be
// inspected or removed, and the caller should neither fail the start nor claim
// that the machine is clean.
func Sweep() (bool, error) {
	found, err := staleOS()
	if !found {
		return false, err
	}
	if err := disableOS(); err != nil {
		return true, err
	}
	return true, nil
}
