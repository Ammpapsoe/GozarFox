package tunnel

import (
	"encoding/binary"
	"errors"
	"fmt"
	"io"
	"net"
	"net/url"
	"strconv"
	"strings"
	"time"
)

type Hop struct {
	Scheme string
	Host   string
	Port   int
}

func ParseChain(values []string) ([]Hop, error) {
	if len(values) > 8 {
		return nil, errors.New("the proxy chain can have at most eight hops")
	}
	var hops []Hop
	for _, value := range values {
		value = strings.TrimSpace(value)
		if value == "" {
			continue
		}
		if !strings.Contains(value, "://") {
			value = "socks5://" + value
		}
		parsed, err := url.Parse(value)
		if err != nil || parsed.User != nil || parsed.Path != "" || parsed.RawQuery != "" || parsed.Fragment != "" {
			return nil, errors.New("each proxy must be a SOCKS5 or HTTP host and port without credentials")
		}
		scheme := strings.ToLower(parsed.Scheme)
		if scheme != "socks5" && scheme != "http" {
			return nil, errors.New("only SOCKS5 and HTTP proxies are supported")
		}
		host := parsed.Hostname()
		port, err := strconv.Atoi(parsed.Port())
		if err != nil || port < 1 || port > 65535 || host == "" || len(host) > 255 || strings.ContainsAny(host, "\r\n \t") {
			return nil, errors.New("each proxy needs a valid host and port")
		}
		hops = append(hops, Hop{Scheme: scheme, Host: host, Port: port})
	}
	return hops, nil
}

func dialTCP(host string, port int, timeout time.Duration) (net.Conn, error) {
	dialer := net.Dialer{Timeout: timeout, KeepAlive: -1}
	conn, err := dialer.Dial("tcp", net.JoinHostPort(host, strconv.Itoa(port)))
	if err != nil {
		return nil, err
	}
	if tcp, ok := conn.(*net.TCPConn); ok {
		_ = tcp.SetNoDelay(true)
	}
	return conn, nil
}

func dialChain(target Target, timeout time.Duration) (net.Conn, error) {
	host := target.Host
	port := target.Port
	if len(target.Chain) > 0 {
		host = target.Chain[0].Host
		port = target.Chain[0].Port
	}
	if target.DialHost != "" {
		host = target.DialHost
	}
	conn, err := dialTCP(host, port, timeout)
	if err != nil {
		return nil, err
	}
	if err := conn.SetDeadline(time.Now().Add(timeout)); err != nil {
		conn.Close()
		return nil, err
	}
	for index, hop := range target.Chain {
		nextHost, nextPort := target.Host, target.Port
		if index+1 < len(target.Chain) {
			nextHost, nextPort = target.Chain[index+1].Host, target.Chain[index+1].Port
		}
		switch hop.Scheme {
		case "socks5":
			err = socksConnect(conn, nextHost, nextPort)
		case "http":
			err = httpConnect(conn, nextHost, nextPort)
		default:
			err = errors.New("unsupported proxy type")
		}
		if err != nil {
			conn.Close()
			return nil, fmt.Errorf("proxy hop %d: %w", index+1, err)
		}
	}
	if err := conn.SetDeadline(time.Time{}); err != nil {
		conn.Close()
		return nil, err
	}
	return conn, nil
}

func writeAll(w io.Writer, data []byte) error {
	for len(data) > 0 {
		n, err := w.Write(data)
		if err != nil {
			return err
		}
		if n == 0 {
			return io.ErrShortWrite
		}
		data = data[n:]
	}
	return nil
}

func socksConnect(conn net.Conn, host string, port int) error {
	if port < 1 || port > 65535 || len(host) == 0 || len(host) > 255 {
		return errors.New("invalid proxy destination")
	}
	if err := writeAll(conn, []byte{5, 1, 0}); err != nil {
		return err
	}
	var greeting [2]byte
	if _, err := io.ReadFull(conn, greeting[:]); err != nil {
		return err
	}
	if greeting != [2]byte{5, 0} {
		return errors.New("SOCKS5 proxy requires unsupported authentication")
	}
	request := []byte{5, 1, 0}
	if ip := net.ParseIP(host); ip != nil {
		if v4 := ip.To4(); v4 != nil {
			request = append(request, 1)
			request = append(request, v4...)
		} else {
			request = append(request, 4)
			request = append(request, ip.To16()...)
		}
	} else {
		request = append(request, 3, byte(len(host)))
		request = append(request, host...)
	}
	request = binary.BigEndian.AppendUint16(request, uint16(port))
	if err := writeAll(conn, request); err != nil {
		return err
	}
	var reply [4]byte
	if _, err := io.ReadFull(conn, reply[:]); err != nil {
		return err
	}
	if reply[0] != 5 || reply[1] != 0 || reply[2] != 0 {
		return errors.New("SOCKS5 proxy refused the connection")
	}
	var size int
	switch reply[3] {
	case 1:
		size = 4
	case 4:
		size = 16
	case 3:
		var length [1]byte
		if _, err := io.ReadFull(conn, length[:]); err != nil {
			return err
		}
		size = int(length[0])
	default:
		return errors.New("SOCKS5 proxy returned an invalid address")
	}
	_, err := io.CopyN(io.Discard, conn, int64(size+2))
	return err
}

func httpConnect(conn net.Conn, host string, port int) error {
	if host == "" || strings.ContainsAny(host, "\r\n \t") || port < 1 || port > 65535 {
		return errors.New("invalid proxy destination")
	}
	authority := net.JoinHostPort(host, strconv.Itoa(port))
	request := "CONNECT " + authority + " HTTP/1.1\r\nHost: " + authority + "\r\n\r\n"
	if err := writeAll(conn, []byte(request)); err != nil {
		return err
	}
	header := make([]byte, 0, 256)
	var one [1]byte
	for len(header) < 16384 {
		if _, err := io.ReadFull(conn, one[:]); err != nil {
			return err
		}
		header = append(header, one[0])
		if len(header) >= 4 && header[len(header)-4] == '\r' && header[len(header)-3] == '\n' &&
			header[len(header)-2] == '\r' && header[len(header)-1] == '\n' {
			break
		}
	}
	if !strings.HasSuffix(string(header), "\r\n\r\n") {
		return errors.New("HTTP proxy response is too large")
	}
	first, _, _ := strings.Cut(string(header), "\r\n")
	parts := strings.Fields(first)
	if len(parts) < 2 || (parts[0] != "HTTP/1.0" && parts[0] != "HTTP/1.1") {
		return errors.New("invalid HTTP proxy response")
	}
	status, err := strconv.Atoi(parts[1])
	if err != nil || status < 200 || status >= 300 {
		return errors.New("HTTP proxy refused the connection")
	}
	return nil
}
