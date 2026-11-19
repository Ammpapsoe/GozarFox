//go:build windows

package engine

import (
	"bytes"
	"crypto/sha256"
	"fmt"
	"io"
	"net"
	"net/netip"
	"os"
	"runtime"
	"testing"
	"time"

	"github.com/Ammpapsoe/GozarFox/go-desktop/internal/wintun"
)

// This is a diagnostic measurement, not a performance gate or a comparison
// with a remote VPN. It runs only on an isolated Windows CI runner and uses a
// loopback test peer; real networks and authenticated relays have different
// latency, bandwidth, MTU and loss characteristics.
func TestLiveKernelTCPThroughput(t *testing.T) {
	if os.Getenv("GOZAR_LIVE_TUN_TEST") != "1" || runtime.GOOS != "windows" {
		t.Skip("requires an isolated Windows kernel tunnel test environment")
	}
	if err := wintun.Ensure(); err != nil {
		t.Fatal(err)
	}
	listener, err := net.Listen("tcp4", "127.0.0.1:0")
	if err != nil {
		t.Fatal(err)
	}
	defer listener.Close()
	// A repeating payload is adequate here: integrity and the byte count are
	// checked, and no compression is used in this local TCP test.
	payload := bytes.Repeat([]byte("gozar-throughput-diagnostic-block"), 262144)
	digest := sha256.Sum256(payload)
	peerDone := make(chan error, 1)
	go func() {
		conn, err := listener.Accept()
		if err != nil {
			peerDone <- err
			return
		}
		defer conn.Close()
		_ = conn.SetReadDeadline(time.Now().Add(35 * time.Second))
		incoming := sha256.New()
		if _, err := io.CopyN(incoming, conn, int64(len(payload))); err != nil {
			peerDone <- err
			return
		}
		if !bytes.Equal(incoming.Sum(nil), digest[:]) {
			peerDone <- fmt.Errorf("upload digest mismatch")
			return
		}
		_ = conn.SetWriteDeadline(time.Now().Add(35 * time.Second))
		if _, err := conn.Write([]byte{0xa5}); err != nil {
			peerDone <- err
			return
		}
		_, err = io.Copy(conn, bytes.NewReader(payload))
		peerDone <- err
	}()
	dial := func(host string, port int) (io.ReadWriteCloser, error) {
		if host != "198.18.0.80" || port != 443 {
			return nil, fmt.Errorf("unexpected diagnostic destination")
		}
		return net.DialTimeout("tcp4", listener.Addr().String(), 5*time.Second)
	}
	var keep []netip.Addr
	if raw := os.Getenv("GOZAR_PROTECT_TEST_IP"); raw != "" {
		address, err := netip.ParseAddr(raw)
		if err != nil {
			t.Fatal(err)
		}
		keep = append(keep, address)
	}
	e, err := start("test.invalid", dial, keep, liveResolver{}, nil, nil)
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(e.Stop)
	conn, err := net.DialTimeout("tcp4", "198.18.0.80:443", 10*time.Second)
	if err != nil {
		t.Fatal(err)
	}
	defer conn.Close()
	_ = conn.SetWriteDeadline(time.Now().Add(30 * time.Second))
	_ = conn.SetReadDeadline(time.Now().Add(30 * time.Second))
	started := time.Now()
	written, err := io.Copy(conn, bytes.NewReader(payload))
	if err != nil || written != int64(len(payload)) {
		t.Fatalf("upload: %d bytes, %v", written, err)
	}
	// Wait for the peer's digest check, not merely a local kernel buffer.
	var ack [1]byte
	if _, err := io.ReadFull(conn, ack[:]); err != nil || ack[0] != 0xa5 {
		t.Fatalf("upload acknowledgement: %x, %v", ack, err)
	}
	upload := time.Since(started)
	downCount, upCount := e.Counters()
	t.Logf("upload and peer acknowledgement: %d bytes in %s; engine up=%d down=%d", written, upload, upCount, downCount)
	_ = conn.SetReadDeadline(time.Now().Add(30 * time.Second))
	started = time.Now()
	incoming := sha256.New()
	read, err := io.CopyN(incoming, conn, int64(len(payload)))
	download := time.Since(started)
	if err != nil || read != int64(len(payload)) || !bytes.Equal(incoming.Sum(nil), digest[:]) {
		downCount, upCount = e.Counters()
		t.Fatalf("download: %d bytes in %s, %v, digest matches=%t; engine up=%d down=%d", read, download, err, bytes.Equal(incoming.Sum(nil), digest[:]), upCount, downCount)
	}
	if err := <-peerDone; err != nil {
		t.Fatal(err)
	}
	rate := func(elapsed time.Duration) float64 {
		return float64(len(payload)*8) / elapsed.Seconds() / 1_000_000
	}
	t.Logf("local Wintun TCP diagnostic: bytes per direction=%d, upload=%.2f Mbit/s (%s), download=%.2f Mbit/s (%s); no remote relay or WAN", len(payload), rate(upload), upload, rate(download), download)
}
