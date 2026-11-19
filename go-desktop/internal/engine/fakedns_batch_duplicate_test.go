package engine

import (
	"net/netip"
	"sync"
	"testing"
)

func TestFakeDNSBatchConcurrentSameNameGetsOneIdentity(t *testing.T) {
	j := openTestDNSFiles(t, t.TempDir())
	f, err := newJournalFakeDNS(j, nil)
	if err != nil {
		t.Fatal(err)
	}
	const count = 64
	addresses := make([]netip.Addr, count)
	errs := make([]error, count)
	var wg sync.WaitGroup
	start := make(chan struct{})
	for i := range addresses {
		wg.Add(1)
		go func(i int) { defer wg.Done(); <-start; addresses[i], errs[i] = f.address("same.example") }(i)
	}
	close(start)
	wg.Wait()
	for i, addr := range addresses {
		if errs[i] != nil || addr != addresses[0] || !addr.IsValid() {
			t.Fatalf("request %d got %s %v", i, addr, errs[i])
		}
	}
	f.mu.RLock()
	allocated, pending := f.allocated, len(f.pending)
	f.mu.RUnlock()
	if allocated != 1 || pending != 0 || len(j.names) != 1 {
		t.Fatalf("got %d allocations, %d pending, %d journal entries", allocated, pending, len(j.names))
	}
}
