package tunnel

import (
	"bytes"
	"fmt"
	"net"
	"net/http"
	"sync"
	"testing"
	"time"

	"github.com/bardia049b-stack/Gozar1/go-desktop/internal/masque"
)

// This exercises an actual loopback UDP echo socket behind an authenticated
// synthetic HTTP/2 proxy. It does not use the app's packet engine or a
// production relay, and it must never contact an external destination.
func TestUDPHTTP2SyntheticProxyForwardsLoopbackPackets(t *testing.T) {
	if !udpTestChild(t, true) {
		return
	}
	packet, err := net.ListenPacket("udp4", "127.0.0.1:0")
	if err != nil {
		t.Fatal(err)
	}
	var echo sync.WaitGroup
	echo.Add(1)
	go func() {
		defer echo.Done()
		buf := make([]byte, 65535)
		for {
			n, peer, err := packet.ReadFrom(buf)
			if err != nil {
				return
			}
			if _, err := packet.WriteTo(buf[:n], peer); err != nil {
				return
			}
		}
	}()
	t.Cleanup(func() { _ = packet.Close(); echo.Wait() })
	udpPort := packet.LocalAddr().(*net.UDPAddr).Port
	path := fmt.Sprintf("/.well-known/masque/udp/127.0.0.1/%d/", udpPort)
	handler := http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if r.ProtoMajor != 2 || r.Method != http.MethodConnect || r.Header.Get(":protocol") != "connect-udp" ||
			r.Header.Get("Capsule-Protocol") != "?1" || r.Header.Get("Proxy-Authorization") != "Bearer loopback-only" || r.RequestURI != path {
			w.WriteHeader(http.StatusForbidden)
			return
		}
		// The destination is fixed by the test, not obtained from an
		// untrusted URL; this server is intentionally loopback-only.
		udp, err := net.Dial("udp4", packet.LocalAddr().String())
		if err != nil {
			w.WriteHeader(http.StatusBadGateway)
			return
		}
		defer udp.Close()
		w.Header().Set("Capsule-Protocol", "?1")
		w.WriteHeader(http.StatusOK)
		w.(http.Flusher).Flush()
		buf := make([]byte, 65535)
		for {
			payload, err := masque.ReadDatagram(r.Body)
			if err != nil {
				return
			}
			if err := udp.SetDeadline(time.Now().Add(5 * time.Second)); err != nil {
				return
			}
			if _, err := udp.Write(payload); err != nil {
				return
			}
			n, err := udp.Read(buf)
			if err != nil {
				return
			}
			wire, err := masque.EncodeDatagram(buf[:n])
			if err != nil {
				return
			}
			if _, err := w.Write(wire); err != nil {
				return
			}
			w.(http.Flusher).Flush()
		}
	})
	h2Port, roots := udpSyntheticServer(t, handler)
	manager := newUDPTestManager(h2Port, roots)
	defer manager.Close()
	stream, err := manager.OpenUDP("127.0.0.1", udpPort, "loopback-only", 5*time.Second)
	if err != nil {
		t.Fatal(err)
	}
	defer stream.Close()
	for _, payload := range [][]byte{[]byte("first packet"), {}, bytes.Repeat([]byte{0x5a}, 8192), []byte("last")} {
		if err := stream.WriteDatagram(payload); err != nil {
			t.Fatal(err)
		}
		reply, err := stream.ReadDatagram()
		if err != nil || !bytes.Equal(reply, payload) {
			t.Fatalf("loopback UDP reply: got %d bytes, error %v; want %d bytes", len(reply), err, len(payload))
		}
	}
}
