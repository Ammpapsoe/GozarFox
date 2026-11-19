//go:build windows

package engine

import (
	"os"
	"testing"
)

// Mutates only the disposable CI machine. DnsQuery uses the Windows resolver;
// the cache-only query proves this is not just a successful ipconfig exit.
func TestFakeDNSWindowsSystemFlushReal(t *testing.T) {
	if os.Getenv("GITHUB_ACTIONS") != "true" || os.Getenv("GOZAR_LIVE_TUN_TEST") != "1" {
		t.Skip("requires disposable Windows CI runner")
	}
	if err := flushSyntheticDNSSystemCache(); err != nil {
		t.Fatal(err)
	}
	if _, err := windowsQueryIPv4("example.com", 0); err != nil {
		t.Fatalf("cannot seed DNS client cache: %v", err)
	}
	cached, err := windowsQueryIPv4("example.com", windowsDNSCacheOnly)
	if err != nil || !cached.IsValid() {
		t.Fatalf("DNS client cache not seeded: %s %v", cached, err)
	}
	if err := flushSyntheticDNSSystemCache(); err != nil {
		t.Fatal(err)
	}
	if got, err := windowsQueryIPv4("example.com", windowsDNSCacheOnly); err == nil && got.IsValid() {
		t.Fatalf("entry remained in DNS client cache after flush: %s", got)
	}
}
