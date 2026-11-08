package tunnel

import (
	"bytes"
	"crypto/sha256"
	"crypto/tls"
	"fmt"
	"io"
	"net"
	"net/http"
	"net/netip"
	"os"
	"runtime"
	"sync"
	"testing"
	"time"

	"github.com/bardia049b-stack/Gozar1/go-desktop/internal/engine"
	"github.com/bardia049b-stack/Gozar1/go-desktop/internal/masque"
	"github.com/bardia049b-stack/Gozar1/go-desktop/internal/wintun"
	"golang.org/x/net/http2"
)

func TestLiveKernelTunnel(t *testing.T) {
	if os.Getenv("GOZAR_LIVE_TUN_TEST") != "1" {
		t.Skip("requires an isolated, privileged kernel tunnel environment")
	}
	// The synthetic x/net HTTP/2 server enables extended CONNECT at process
	// startup. This uses only the isolated test namespace and a fake pass.
	if !udpTestChild(t, true) {
		return
	}
	if err := wintun.Ensure(); err != nil {
		t.Fatal(err)
	}
	cert, roots := localCertificate(t)
	listener, err := net.Listen("tcp4", "127.0.0.1:0")
	if err != nil {
		t.Fatal(err)
	}
	defer listener.Close()
	var lock sync.Mutex
	requests := 0
	udpRequests := 0
	handler := http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if r.Method == http.MethodConnect && r.Header.Get(":protocol") == "connect-udp" {
			if r.Header.Get("Proxy-Authorization") != "Bearer fake-pass" || r.URL.Path != "/.well-known/masque/udp/198.18.0.81/443/" {
				w.WriteHeader(http.StatusForbidden)
				return
			}
			lock.Lock()
			udpRequests++
			lock.Unlock()
			w.Header().Set("Capsule-Protocol", "?1")
			w.WriteHeader(http.StatusOK)
			w.(http.Flusher).Flush()
			for {
				payload, err := masque.ReadDatagram(r.Body)
				if err != nil {
					return
				}
				wire, err := masque.EncodeDatagram(payload)
				if err != nil {
					return
				}
				if _, err = w.Write(wire); err != nil {
					return
				}
				w.(http.Flusher).Flush()
			}
		}
		if r.Method != http.MethodConnect || r.Host != "198.18.0.80:443" || r.Header.Get("Proxy-Authorization") != "Bearer fake-pass" {
			w.WriteHeader(http.StatusForbidden)
			return
		}
		lock.Lock()
		requests++
		lock.Unlock()
		w.WriteHeader(http.StatusOK)
		w.(http.Flusher).Flush()
		hash := sha256.New()
		if _, err := io.Copy(hash, r.Body); err == nil {
			_, _ = w.Write(hash.Sum(nil))
			w.(http.Flusher).Flush()
		}
	})
	go func() {
		for {
			raw, err := listener.Accept()
			if err != nil {
				return
			}
			go func() {
				conn := tls.Server(raw, &tls.Config{Certificates: []tls.Certificate{cert}, NextProtos: []string{"h2"}})
				defer conn.Close()
				if conn.Handshake() == nil {
					(&http2.Server{}).ServeConn(conn, &http2.ServeConnOpts{Handler: handler})
				}
			}()
		}
	}()
	manager := NewManager(Target{Host: "localhost", DialHost: "127.0.0.1", Port: listener.Addr().(*net.TCPAddr).Port})
	manager.testTLS = &tls.Config{RootCAs: roots}
	defer manager.Close()
	var keep []netip.Addr
	if raw := os.Getenv("GOZAR_PROTECT_TEST_IP"); raw != "" {
		address, err := netip.ParseAddr(raw)
		if err != nil {
			t.Fatal(err)
		}
		keep = append(keep, address)
	}
	device, err := engine.StartSplitUDP("localhost", func(host string, port int) (io.ReadWriteCloser, error) {
		return manager.Open(host, port, "fake-pass", 10*time.Second)
	}, nil, keep, nil, func(host string, port int) (engine.DatagramTunnel, error) {
		stream, err := manager.OpenUDP(host, port, "fake-pass", 10*time.Second)
		if err != nil {
			return nil, err
		}
		return stream, nil
	})
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(device.Stop)
	const count = 4
	payload := bytes.Repeat([]byte("Gozar-TUN-H2-CONNECT-"), 32768)
	digest := sha256.Sum256(payload)
	results := make(chan error, count)
	for i := 0; i < count; i++ {
		go func() {
			conn, err := net.DialTimeout("tcp4", "198.18.0.80:443", 10*time.Second)
			if err != nil {
				results <- err
				return
			}
			defer conn.Close()
			_ = conn.SetDeadline(time.Now().Add(20 * time.Second))
			if _, err := conn.Write(payload); err != nil {
				results <- err
				return
			}
			if err := conn.(*net.TCPConn).CloseWrite(); err != nil {
				results <- err
				return
			}
			result := make([]byte, len(digest))
			if _, err := io.ReadFull(conn, result); err != nil {
				results <- err
				return
			}
			if !bytes.Equal(result, digest[:]) {
				results <- fmt.Errorf("incorrect response digest")
				return
			}
			results <- nil
		}()
	}
	for i := 0; i < count; i++ {
		if err := <-results; err != nil {
			t.Fatal(err)
		}
	}
	udp, err := net.DialTimeout("udp4", "198.18.0.81:443", 10*time.Second)
	if err != nil {
		t.Fatal(err)
	}
	_ = udp.SetDeadline(time.Now().Add(10 * time.Second))
	for _, payload := range [][]byte{[]byte("one UDP packet"), []byte("another UDP packet")} {
		if _, err = udp.Write(payload); err != nil {
			udp.Close()
			t.Fatal(err)
		}
		buffer := make([]byte, 1500)
		n, err := udp.Read(buffer)
		if err != nil || !bytes.Equal(buffer[:n], payload) {
			udp.Close()
			t.Fatalf("UDP echo: got %q, err=%v", buffer[:n], err)
		}
	}
	udp.Close()
	lock.Lock()
	got := requests
	gotUDP := udpRequests
	lock.Unlock()
	if got != count || gotUDP != 1 {
		t.Fatalf("proxy received %d TCP and %d UDP streams, want %d and 1", got, gotUDP, count)
	}
	if runtime.GOOS == "linux" && os.Getenv("GOZAR_LIVE_DNS_CHANGE_TEST") == "1" {
		if err := os.WriteFile("/etc/resolv.conf", []byte("nameserver 192.0.2.99\n"), 0o644); err != nil {
			t.Fatal(err)
		}
	}
	device.Stop()
	t.Logf("real %s packet path passed TCP and UDP through a local TLS-verified HTTP/2 proxy", device.Device())
}
