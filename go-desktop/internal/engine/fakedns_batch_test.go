package engine

import (
	"errors"
	"fmt"
	"net/netip"
	"os"
	"sync"
	"sync/atomic"
	"testing"
	"time"
)

func TestFakeDNSBatchPublishesOnlyAfterDurableSync(t *testing.T) {
	dir := t.TempDir()
	journal := openTestDNSFiles(t, dir)
	f, err := newJournalFakeDNS(journal, nil)
	if err != nil {
		t.Fatal(err)
	}
	var historySyncs atomic.Int32
	staged := make(chan struct{})
	release := make(chan struct{})
	var once sync.Once
	journal.syncFile = func(file *os.File) error {
		if file == journal.history {
			historySyncs.Add(1)
			once.Do(func() { close(staged); <-release })
		}
		return file.Sync()
	}
	const count = 64
	start := make(chan struct{})
	var wg sync.WaitGroup
	addresses := make([]netip.Addr, count)
	errs := make([]error, count)
	for i := 0; i < count; i++ {
		wg.Add(1)
		go func(i int) {
			defer wg.Done()
			<-start
			addresses[i], errs[i] = f.address(fmt.Sprintf("batch-%d.example", i))
		}(i)
	}
	close(start)
	select {
	case <-staged:
	case <-time.After(5 * time.Second):
		t.Fatal("journal not reached")
	}
	deadline := time.Now().Add(5 * time.Second)
	for {
		f.mu.RLock()
		pending, visible := len(f.pending), len(f.byName)
		f.mu.RUnlock()
		if visible != 0 {
			t.Fatal("address visible before first durable sync")
		}
		if pending == count {
			break
		}
		if time.Now().After(deadline) {
			close(release)
			wg.Wait()
			t.Fatalf("only %d pending", pending)
		}
		time.Sleep(time.Millisecond)
	}
	close(release)
	wg.Wait()
	if n := historySyncs.Load(); n >= count {
		t.Fatalf("concurrent writes were not grouped: %d syncs", n)
	}
	seen := make(map[netip.Addr]bool)
	for i, address := range addresses {
		if errs[i] != nil || !address.IsValid() || seen[address] {
			t.Fatalf("bad mapping %d %s %v", i, address, errs[i])
		}
		seen[address] = true
	}
	_ = journal.history.Close()
	_ = journal.anchor.Close()
	reopened, err := newJournalFakeDNS(openTestDNSFiles(t, dir), nil)
	if err != nil {
		t.Fatal(err)
	}
	for i, address := range addresses {
		name, synthetic, ok := reopened.lookup(address)
		if !synthetic || !ok || name != fmt.Sprintf("batch-%d.example", i) {
			t.Fatalf("changed identity %d %q", i, name)
		}
	}
}

func TestFakeDNSBatchFailureDoesNotPublishOrReuse(t *testing.T) {
	for _, failure := range []string{"history", "anchor"} {
		t.Run(failure, func(t *testing.T) {
			dir := t.TempDir()
			j := openTestDNSFiles(t, dir)
			f, err := newJournalFakeDNS(j, nil)
			if err != nil {
				t.Fatal(err)
			}
			var block *os.File
			if failure == "history" {
				block = j.history
			} else {
				block = j.anchor
			}
			j.syncFile = func(file *os.File) error {
				if file == block {
					return errors.New("injected sync failure")
				}
				return file.Sync()
			}
			start := make(chan struct{})
			const count = 32
			var wg sync.WaitGroup
			errs := make([]error, count)
			for i := 0; i < count; i++ {
				wg.Add(1)
				go func(i int) { defer wg.Done(); <-start; _, errs[i] = f.address(fmt.Sprintf("failure-%d.example", i)) }(i)
			}
			close(start)
			wg.Wait()
			for _, err := range errs {
				if err == nil {
					t.Fatal("failed batch published an address")
				}
			}
			if _, err := f.address("later.example"); err == nil {
				t.Fatal("continued after uncertain write")
			}
			_ = j.history.Close()
			_ = j.anchor.Close()
			reopened, err := newJournalFakeDNS(openTestDNSFiles(t, dir), nil)
			if err != nil {
				return
			} // a torn write is allowed to fail closed
			seen := make(map[netip.Addr]bool)
			for i := 0; i < count; i++ {
				a, err := reopened.address(fmt.Sprintf("failure-%d.example", i))
				if err != nil || seen[a] {
					t.Fatalf("recovery reuse %s %v", a, err)
				}
				seen[a] = true
			}
		})
	}
}
