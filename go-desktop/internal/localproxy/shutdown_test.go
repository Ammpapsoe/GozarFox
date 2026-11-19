package localproxy

import (
	"bufio"
	"fmt"
	"io"
	"net"
	"net/http"
	"sync"
	"testing"
	"time"
)

// noHalfClosePipe models a tunnel whose sending half can close while its
// receiving half never sends data or finishes until the whole stream closes.
type noHalfClosePipe struct {
	net.Conn
	closed chan struct{}
	once   sync.Once
}

func (c *noHalfClosePipe) CloseWrite() error { return nil }
func (c *noHalfClosePipe) Close() error {
	c.once.Do(func() { close(c.closed) })
	return c.Conn.Close()
}

func TestCloseTerminatesBothSidesOfHijackedConnect(t *testing.T) {
	proxyEnd, peer := net.Pipe()
	defer peer.Close()
	upstream := &noHalfClosePipe{Conn: proxyEnd, closed: make(chan struct{})}
	proxy, err := Listen(0, func(string, int) (io.ReadWriteCloser, error) { return upstream, nil })
	if err != nil {
		t.Fatal(err)
	}
	defer proxy.Close()
	client, err := net.DialTimeout("tcp4", fmt.Sprintf("127.0.0.1:%d", proxy.Port()), 5*time.Second)
	if err != nil {
		t.Fatal(err)
	}
	defer client.Close()
	_ = client.SetDeadline(time.Now().Add(5 * time.Second))
	if _, err := io.WriteString(client, "CONNECT example.org:443 HTTP/1.1\r\nHost: example.org:443\r\n\r\n"); err != nil {
		t.Fatal(err)
	}
	response, err := http.ReadResponse(bufio.NewReader(client), &http.Request{Method: http.MethodConnect})
	if err != nil || response.StatusCode != http.StatusOK {
		t.Fatalf("CONNECT: %#v, %v", response, err)
	}
	if err := proxy.Close(); err != nil {
		t.Fatal(err)
	}
	select {
	case <-upstream.closed:
	case <-time.After(2 * time.Second):
		t.Fatal("upstream stream remained open after proxy shutdown")
	}
}
