package localproxy

import (
	"bufio"
	"fmt"
	"io"
	"net"
	"net/http"
	"net/http/httptest"
	"net/url"
	"strings"
	"sync"
	"testing"
	"time"
)

func TestHTTPProxyForwardsWithoutLeakingProxyHeaders(t *testing.T) {
	upstream := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if r.Host != "origin.example" || r.URL.Path != "/hello" || r.Header.Get("Proxy-Authorization") != "" || r.Header.Get("X-Hop") != "" {
			w.WriteHeader(http.StatusBadRequest)
			return
		}
		_, _ = io.WriteString(w, "proxied")
	}))
	defer upstream.Close()
	var mu sync.Mutex
	var host string
	proxy, err := Listen(0, func(h string, port int) (io.ReadWriteCloser, error) {
		mu.Lock()
		host = fmt.Sprintf("%s:%d", h, port)
		mu.Unlock()
		return net.Dial("tcp", upstream.Listener.Addr().String())
	})
	if err != nil {
		t.Fatal(err)
	}
	defer proxy.Close()
	proxyURL, _ := url.Parse(fmt.Sprintf("http://127.0.0.1:%d", proxy.Port()))
	client := &http.Client{Transport: &http.Transport{Proxy: http.ProxyURL(proxyURL)}, Timeout: 5 * time.Second}
	request, _ := http.NewRequest("GET", "http://origin.example/hello", nil)
	request.Header.Set("Proxy-Authorization", "secret")
	request.Header.Set("Connection", "X-Hop")
	request.Header.Set("X-Hop", "do not forward")
	resp, err := client.Do(request)
	if err != nil {
		t.Fatal(err)
	}
	defer resp.Body.Close()
	body, err := io.ReadAll(resp.Body)
	if err != nil || resp.StatusCode != 200 || string(body) != "proxied" {
		t.Fatalf("response %d %q %v", resp.StatusCode, body, err)
	}
	mu.Lock()
	got := host
	mu.Unlock()
	if got != "origin.example:80" {
		t.Fatalf("wrong target: %s", got)
	}
}

func TestCONNECTPreservesBufferedBytesAndHalfClose(t *testing.T) {
	upstream, err := net.Listen("tcp4", "127.0.0.1:0")
	if err != nil {
		t.Fatal(err)
	}
	defer upstream.Close()
	go func() {
		c, err := upstream.Accept()
		if err != nil {
			return
		}
		defer c.Close()
		data, err := io.ReadAll(c)
		if err == nil {
			_, _ = c.Write(append([]byte("answer:"), data...))
		}
		if tcp, ok := c.(*net.TCPConn); ok {
			_ = tcp.CloseWrite()
		}
	}()
	targets := make(chan string, 1)
	proxy, err := Listen(0, func(host string, port int) (io.ReadWriteCloser, error) {
		targets <- fmt.Sprintf("%s:%d", host, port)
		return net.Dial("tcp", upstream.Addr().String())
	})
	if err != nil {
		t.Fatal(err)
	}
	defer proxy.Close()
	conn, err := net.DialTimeout("tcp4", fmt.Sprintf("127.0.0.1:%d", proxy.Port()), 3*time.Second)
	if err != nil {
		t.Fatal(err)
	}
	defer conn.Close()
	_ = conn.SetDeadline(time.Now().Add(5 * time.Second))
	_, err = io.WriteString(conn, "CONNECT site.example:443 HTTP/1.1\r\nHost: site.example:443\r\n\r\nearly payload")
	if err != nil {
		t.Fatal(err)
	}
	if err := conn.(*net.TCPConn).CloseWrite(); err != nil {
		t.Fatal(err)
	}
	reader := bufio.NewReader(conn)
	resp, err := http.ReadResponse(reader, &http.Request{Method: http.MethodConnect})
	if err != nil || resp.StatusCode != 200 {
		t.Fatalf("connect response: %+v %v", resp, err)
	}
	body, err := io.ReadAll(reader)
	if err != nil || string(body) != "answer:early payload" {
		t.Fatalf("payload %q %v", body, err)
	}
	if got := <-targets; got != "site.example:443" {
		t.Fatalf("target %q", got)
	}
}

func TestProxyRejectsBadRequestsAndCloses(t *testing.T) {
	proxy, err := Listen(0, func(string, int) (io.ReadWriteCloser, error) { return nil, fmt.Errorf("unreachable") })
	if err != nil {
		t.Fatal(err)
	}
	conn, err := net.Dial("tcp4", fmt.Sprintf("127.0.0.1:%d", proxy.Port()))
	if err != nil {
		t.Fatal(err)
	}
	_, _ = io.WriteString(conn, "CONNECT bad:0 HTTP/1.1\r\nHost: bad:0\r\n\r\n")
	_ = conn.SetDeadline(time.Now().Add(5 * time.Second))
	line, err := bufio.NewReader(conn).ReadString('\n')
	if err != nil || !strings.Contains(line, "400") {
		t.Fatalf("unexpected status %q %v", line, err)
	}
	_ = conn.Close()
	if err := proxy.Close(); err != nil {
		t.Fatal(err)
	}
	if err := proxy.Close(); err != nil {
		t.Fatal(err)
	}
}
