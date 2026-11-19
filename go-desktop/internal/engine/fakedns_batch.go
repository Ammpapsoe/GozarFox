package engine

import (
	"errors"
	"net/netip"
	"time"
)

// AppendBatch must persist the complete ordered batch before returning nil.
// On error it may have persisted a prefix, so no address from the batch can
// be returned and further allocations must be stopped until recovery.
type batchDNSJournal interface{ AppendBatch([]string) error }

type fakeDNSPending struct {
	name    string
	done    chan struct{}
	address netip.Addr
	err     error
}

const fakeDNSBatchLimit = 128
const fakeDNSBatchWindow = time.Millisecond

func (f *fakeDNS) batchAddress(name string, journal batchDNSJournal) (netip.Addr, error) {
	f.mu.Lock()
	if address, ok := f.byName[name]; ok {
		f.mu.Unlock()
		return address, nil
	}
	if !fakeDomain(name) {
		f.mu.Unlock()
		return netip.Addr{}, errors.New("invalid synthetic DNS name")
	}
	if f.writeErr != nil {
		err := f.writeErr
		f.mu.Unlock()
		return netip.Addr{}, err
	}
	if p := f.pending[name]; p != nil {
		f.mu.Unlock()
		<-p.done
		return p.address, p.err
	}
	if uint64(f.allocated)+uint64(len(f.pending)) >= uint64(fakeCount) {
		f.mu.Unlock()
		return netip.Addr{}, errors.New("synthetic DNS address pool exhausted")
	}
	if f.pending == nil {
		f.pending = make(map[string]*fakeDNSPending)
	}
	p := &fakeDNSPending{name: name, done: make(chan struct{})}
	f.pending[name] = p
	f.queue = append(f.queue, p)
	if !f.batching {
		f.batching = true
		go f.flushDNSBatches(journal)
	}
	f.mu.Unlock()
	<-p.done
	return p.address, p.err
}

func (f *fakeDNS) flushDNSBatches(journal batchDNSJournal) {
	// Give simultaneously arriving DNS questions a chance to share a disk
	// flush. This is an upper bound for the initial batching wait, not a TTL.
	time.Sleep(fakeDNSBatchWindow)
	for {
		f.mu.Lock()
		if len(f.queue) == 0 {
			f.batching = false
			f.mu.Unlock()
			return
		}
		n := len(f.queue)
		if n > fakeDNSBatchLimit {
			n = fakeDNSBatchLimit
		}
		batch := append([]*fakeDNSPending(nil), f.queue[:n]...)
		f.queue = f.queue[n:]
		f.mu.Unlock()
		names := make([]string, len(batch))
		for i, p := range batch {
			names[i] = p.name
		}
		err := journal.AppendBatch(names)
		f.mu.Lock()
		if err != nil {
			f.writeErr = errors.New("synthetic DNS journal failed; restart required")
		}
		for _, p := range batch {
			if err != nil {
				p.err = f.writeErr
			} else {
				p.address = fakeAddressAt(f.start, f.allocated)
				f.allocated++
				f.byName[p.name] = p.address
				f.byAddr[p.address] = p.name
			}
			delete(f.pending, p.name)
			close(p.done)
		}
		if err != nil {
			for _, p := range f.queue {
				p.err = f.writeErr
				delete(f.pending, p.name)
				close(p.done)
			}
			f.queue = nil
			f.batching = false
			f.mu.Unlock()
			return
		}
		f.mu.Unlock()
	}
}
