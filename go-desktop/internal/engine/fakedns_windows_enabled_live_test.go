//go:build windows

package engine

import (
	"errors"
	"io"
	"net"
	"net/netip"
	"os"
	"path/filepath"
	"testing"
	"time"

	"github.com/Ammpapsoe/GozarFox/go-desktop/internal/dnsproxy"
	"github.com/Ammpapsoe/GozarFox/go-desktop/internal/wintun"
	"golang.org/x/net/dns/dnsmessage"
	"golang.org/x/sys/windows"
	"golang.org/x/sys/windows/registry"
)

// Test the exact enabled starter, mandatory flushes, machine storage, real
// Wintun and DNS publication on a disposable runner. The relay is a stub.
func TestFakeDNSWindowsProductionStarterLive(t *testing.T) {
	if os.Getenv("GITHUB_ACTIONS") != "true" || os.Getenv("GOZAR_LIVE_TUN_TEST") != "1" || os.Getenv("GOZAR_FAKEDNS_PRODUCTION_STORAGE_TEST") != "1" {
		t.Skip("requires disposable privileged Windows runner")
	}
	if err := wintun.Ensure(); err != nil {
		t.Fatal(err)
	}
	base, err := windows.KnownFolderPath(windows.FOLDERID_ProgramData, windows.KF_FLAG_DEFAULT)
	if err != nil {
		t.Fatal(err)
	}
	root := filepath.Join(base, machineDNSWindowsFolder)
	if _, err = os.Lstat(root); err == nil || !errors.Is(err, os.ErrNotExist) {
		t.Fatal("production DNS state exists or cannot be inspected")
	}
	key, err := registry.OpenKey(registry.LOCAL_MACHINE, machineDNSWindowsKey, registry.QUERY_VALUE|registry.WOW64_64KEY)
	if err == nil {
		_ = key.Close()
		t.Fatal("production registry marker already exists")
	}
	if !errors.Is(err, registry.ErrNotExist) {
		t.Fatal(err)
	}
	t.Cleanup(func() {
		if err := os.RemoveAll(root); err != nil {
			t.Error(err)
		}
		if err := registry.DeleteKey(registry.LOCAL_MACHINE, machineDNSWindowsKey); err != nil && !errors.Is(err, registry.ErrNotExist) {
			t.Error(err)
		}
	})
	stub := func(string, int) (io.ReadWriteCloser, error) { return nil, errors.New("test relay is not used") }
	e, err := StartSplitUDPWithFakeDNS("test.invalid", stub, nil, nil, nil, nil)
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(e.Stop)
	conn, err := net.DialTimeout("udp4", net.JoinHostPort(dnsproxy.Address, "53"), 5*time.Second)
	if err != nil {
		t.Fatal(err)
	}
	_ = conn.SetDeadline(time.Now().Add(8 * time.Second))
	if _, err = conn.Write(fakeQuery(t, "production-live.example", dnsmessage.TypeA)); err != nil {
		t.Fatal(err)
	}
	var buf [2048]byte
	n, err := conn.Read(buf[:])
	_ = conn.Close()
	if err != nil {
		t.Fatal(err)
	}
	var answer dnsmessage.Message
	if err = answer.Unpack(buf[:n]); err != nil || len(answer.Answers) != 1 {
		t.Fatalf("DNS answer: %v", err)
	}
	record, ok := answer.Answers[0].Body.(*dnsmessage.AResource)
	if !ok {
		t.Fatal("missing A record")
	}
	published := netip.AddrFrom4(record.A)
	if !fakePrefix.Contains(published) {
		t.Fatalf("not a fake address: %s", published)
	}
	e.Stop()
	f, owner, err := openProductionMachineFakeDNS(nil)
	if err != nil {
		t.Fatal(err)
	}
	defer owner.Close()
	if got, err := f.address("production-live.example"); err != nil || got != published {
		t.Fatalf("identity not durable: got %s want %s error %v", got, published, err)
	}
}
