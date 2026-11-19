package engine

import (
	"errors"
	"net/netip"
)

// fakeDNSJournal is a persistence contract, not a storage implementation.
// The owner must hold an exclusive cross-process lock throughout its lifetime.
// Initialize must durably store the start offset before returning. Append must
// durably store the next name before returning success. Load must return the
// exact complete ordered history or fail closed on corruption, truncation,
// ambiguous recovery or rollback; it must enforce size and privacy limits.
// Deleting, replacing or rolling back a journal while DNS answers might be
// cached can cause misrouting and must never be treated as an empty journal.
// Platform-specific storage openers are prototypes only. Separate journals
// can allocate the same address to different names, even sequentially, so an
// account-local journal is insufficient when OS or application DNS caches are
// shared. Production needs a single durable machine-wide namespace (or an
// equally strong disjoint-allocation scheme), across users and privilege
// changes, plus safe storage lifecycle integration. Do not enable until then.
type fakeDNSJournal interface {
	Load() (start uint32, names []string, exists bool, err error)
	Initialize(start uint32) error
	Append(name string) error
}

func fakeAddressAt(start, index uint32) netip.Addr {
	// Exclude the first and last address of the reserved range.
	offset := 1 + (start+index)%fakeCount
	value := uint32(198)<<24 | uint32(18)<<16 | offset
	return netip.AddrFrom4([4]byte{byte(value >> 24), byte(value >> 16), byte(value >> 8), byte(value)})
}

// newJournalFakeDNS reconstructs a prior mapping before admitting traffic.
// The journal and ownership lock must outlive this instance. Later owners
// must reopen the same complete history, including after cached answers
// survive process exit. This helper is not wired to a production engine.
func newJournalFakeDNS(journal fakeDNSJournal, direct func(string) bool) (*fakeDNS, error) {
	if journal == nil {
		return nil, errors.New("synthetic DNS journal is required")
	}
	start, names, exists, err := journal.Load()
	if err != nil {
		return nil, err
	}
	if !exists && (start != 0 || len(names) != 0) {
		return nil, errors.New("invalid missing synthetic DNS journal")
	}
	if exists && (start >= fakeCount || len(names) > int(fakeCount)) {
		return nil, errors.New("invalid synthetic DNS journal")
	}
	f, err := newFakeDNS(direct)
	if err != nil {
		return nil, err
	}
	if !exists {
		if err := journal.Initialize(f.start); err != nil {
			return nil, err
		}
	} else {
		f.start = start
		for _, name := range names {
			if !fakeDomain(name) {
				return nil, errors.New("invalid synthetic DNS journal name")
			}
			if _, found := f.byName[name]; found {
				return nil, errors.New("duplicate synthetic DNS journal name")
			}
			address := fakeAddressAt(f.start, f.allocated)
			f.byName[name] = address
			f.byAddr[address] = name
			f.allocated++
		}
	}
	f.journal = journal
	return f, nil
}
