package tunnel

import (
	"fmt"
	"io"
	"net"
	"testing"
	"time"

	"golang.org/x/net/http2"
)

// Inspect the actual wire settings, not just the configuration structure: the
// client connection must inherit the configuration from the HTTP transport.
func TestH2AdvertisesStreamReceiveWindow(t *testing.T) {
	transport, err := newH2Transport()
	if err != nil {
		t.Fatal(err)
	}
	client, server := net.Pipe()
	defer client.Close()
	defer server.Close()
	deadline := time.Now().Add(5 * time.Second)
	client.SetDeadline(deadline)
	server.SetDeadline(deadline)
	result := make(chan error, 1)
	go func() {
		preface := make([]byte, len(http2.ClientPreface))
		if _, err := io.ReadFull(server, preface); err != nil {
			result <- err
			return
		}
		if string(preface) != http2.ClientPreface {
			result <- fmt.Errorf("invalid HTTP/2 preface")
			return
		}
		framer := http2.NewFramer(server, server)
		frame, err := framer.ReadFrame()
		if err != nil {
			result <- err
			return
		}
		settings, ok := frame.(*http2.SettingsFrame)
		if !ok {
			result <- fmt.Errorf("first frame is %T, not SETTINGS", frame)
			return
		}
		window, ok := settings.Value(http2.SettingInitialWindowSize)
		if !ok || window != h2ReceiveWindow {
			result <- fmt.Errorf("initial stream window = %d (present %t), want %d", window, ok, h2ReceiveWindow)
			return
		}
		// The client sends a connection window update in the same flush.
		_, err = framer.ReadFrame()
		result <- err
	}()
	conn, err := transport.NewClientConn(client)
	if err != nil {
		t.Fatal(err)
	}
	defer conn.Close()
	if err := <-result; err != nil {
		t.Fatal(err)
	}
}
