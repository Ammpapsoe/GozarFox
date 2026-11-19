//go:build linux || windows

package engine

import (
	"fmt"
	"net/netip"
	"os"
	"sort"
	"testing"
	"time"
)

// Measures the actual prototype store in a disposable CI test directory, not
// end-to-end VPN performance. The durable design syncs before publishing a
// new address; this diagnostic exposes that cost instead of assuming that a
// synthetic DNS answer is instantaneous. The memory-only comparator is a
// primitive baseline, not a benchmark of the other client.
func TestFakeDNSDurableAllocationDiagnostic(t *testing.T) {
	if os.Getenv("GOZAR_FAKEDNS_PERF_TEST") != "1" || os.Getenv("GITHUB_ACTIONS") != "true" {
		t.Skip("opt-in disposable CI diagnostic")
	}
	open := platformFakeDNSMachineStorage(t)
	f, owner, err := open()
	if err != nil {
		t.Fatal(err)
	}
	defer owner.Close()
	inMemory, err := newFakeDNS(nil)
	if err != nil {
		t.Fatal(err)
	}
	const count = 32
	var durable, memory, repeat []time.Duration
	addresses := make([]netip.Addr, 0, count)
	for i := 0; i < count; i++ {
		name := fmt.Sprintf("perf-%02d.example", i)
		start := time.Now()
		address, err := f.address(name)
		durable = append(durable, time.Since(start))
		if err != nil {
			t.Fatal(err)
		}
		addresses = append(addresses, address)
		start = time.Now()
		if _, err := inMemory.address(name); err != nil {
			t.Fatal(err)
		}
		memory = append(memory, time.Since(start))
		start = time.Now()
		if again, err := f.address(name); err != nil || again != address {
			t.Fatalf("repeat mapping %s %v", again, err)
		}
		repeat = append(repeat, time.Since(start))
	}
	if err := owner.Close(); err != nil {
		t.Fatal(err)
	}
	reopened, next, err := open()
	if err != nil {
		t.Fatal(err)
	}
	defer next.Close()
	for i, want := range addresses {
		name, synthetic, known := reopened.lookup(want)
		if !synthetic || !known || name != fmt.Sprintf("perf-%02d.example", i) {
			t.Fatalf("reopened mapping %s: %q %t %t", want, name, synthetic, known)
		}
	}
	show := func(samples []time.Duration) (time.Duration, time.Duration) {
		sort.Slice(samples, func(i, j int) bool { return samples[i] < samples[j] })
		return samples[len(samples)/2], samples[(len(samples)*95+99)/100-1]
	}
	d50, d95 := show(durable)
	m50, m95 := show(memory)
	r50, r95 := show(repeat)
	t.Logf("%d allocations: durable p50=%s p95=%s; in-memory-only p50=%s p95=%s; existing durable identity p50=%s p95=%s (isolated machine, no WAN, no rival benchmark)", count, d50, d95, m50, m95, r50, r95)
}
