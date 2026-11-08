package tunnel

import (
	"context"
	"errors"
	"fmt"
	"io"
	"net"
	"net/http"
	"net/netip"
	"strconv"
	"strings"
	"sync"
	"time"

	"github.com/bardia049b-stack/Gozar1/go-desktop/internal/masque"
)

// UDPStream is a single target's CONNECT-UDP capsule stream. The desktop
// packet engine uses it without any direct-UDP fallback; an unsupported proxy
// or failed authorization returns an error.
type UDPStream struct {
	stream  Dup
	readMu  sync.Mutex
	writeMu sync.Mutex
}

// ReadDatagram preserves message boundaries and returns io.EOF only when the
// stream ends cleanly between capsules. Malformed capsules close the stream.
func (s *UDPStream) ReadDatagram() ([]byte, error) {
	s.readMu.Lock()
	defer s.readMu.Unlock()
	payload, err := masque.ReadDatagram(s.stream)
	if err != nil {
		_ = s.stream.Close()
	}
	return payload, err
}

// WriteDatagram serializes whole capsules. If a partial write occurs, the
// stream is unusable and is closed rather than reusing damaged framing.
func (s *UDPStream) WriteDatagram(payload []byte) error {
	if len(payload) > masque.MaxUDPPayload {
		return fmt.Errorf("%w: UDP payload is too large", masque.ErrProtocol)
	}
	s.writeMu.Lock()
	defer s.writeMu.Unlock()
	wire, err := masque.EncodeDatagram(payload)
	if err != nil {
		return err
	}
	n, err := s.stream.Write(wire)
	if err == nil && n != len(wire) {
		err = io.ErrShortWrite
	}
	if err != nil {
		_ = s.stream.Close()
	}
	return err
}

// Close may run concurrently with blocked reads or writes and must unblock
// them through the underlying tracked stream; it does not wait for their locks.
func (s *UDPStream) Close() error { return s.stream.Close() }

// OpenUDP opens an authenticated HTTP/2 extended CONNECT stream for one UDP
// destination. x/net/http2 waits for SETTINGS_ENABLE_CONNECT_PROTOCOL before
// sending extended CONNECT; compatibility with any particular relay is not assumed.
func (m *Manager) OpenUDP(host string, port int, pass string, timeout time.Duration) (*UDPStream, error) {
	path, err := udpPath(host, port)
	if err != nil {
		return nil, err
	}
	if pass == "" || strings.ContainsAny(pass, "\r\n") {
		return nil, errors.New("invalid proxy credential")
	}
	if timeout <= 0 {
		timeout = 15 * time.Second
	}
	session, err := m.h2Client(timeout)
	if err != nil {
		return nil, err
	}
	authority := net.JoinHostPort(m.target.Host, strconv.Itoa(m.target.Port))
	stream, err := openUDPStream(session.client, authority, path, pass, timeout, func() { m.releaseH2(session) })
	if err != nil {
		m.releaseH2(session)
		return nil, err
	}
	tracked, err := m.track(stream)
	if err != nil {
		return nil, err
	}
	return &UDPStream{stream: tracked}, nil
}

func openUDPStream(client roundTripper, authority, path, pass string, timeout time.Duration, release func()) (Dup, error) {
	reader, writer := io.Pipe()
	request, err := http.NewRequest(http.MethodConnect, "https://"+authority+path, reader)
	if err != nil {
		_ = reader.Close()
		_ = writer.Close()
		return nil, err
	}
	request.Host = authority
	request.Header.Set(":protocol", "connect-udp")
	request.Header.Set("Capsule-Protocol", "?1")
	request.Header.Set("Proxy-Authorization", "Bearer "+pass)
	request.Header.Set("User-Agent", agent)
	ctx, cancel := context.WithCancel(context.Background())
	timerDone := make(chan struct{})
	timer := time.AfterFunc(timeout, func() {
		cancel()
		close(timerDone)
	})
	response, err := client.RoundTrip(request.WithContext(ctx))
	if !timer.Stop() {
		// If the timeout raced the response, wait for cancellation and never
		// hand a timed-out stream to the caller as a successful open.
		<-timerDone
		if err == nil {
			err = context.DeadlineExceeded
		}
	}
	if err != nil {
		if response != nil && response.Body != nil {
			_ = response.Body.Close()
		}
		cancel()
		_ = reader.CloseWithError(err)
		_ = writer.CloseWithError(err)
		return nil, err
	}
	if err := validUDPResponse(response); err != nil {
		if response != nil && response.Body != nil {
			_ = response.Body.Close()
		}
		cancel()
		_ = reader.Close()
		_ = writer.Close()
		return nil, err
	}
	return &managedStream{body: response.Body, writer: writer, cancel: cancel, release: release}, nil
}

// This intentionally requires an explicit, unparameterized ?1 response; RFC
// 9297 only recommends sending the field, so this conservative first version
// may reject otherwise conforming relays rather than guessing about framing.
func validUDPResponse(response *http.Response) error {
	if response == nil || response.Body == nil {
		return errors.New("missing UDP proxy response")
	}
	status := response.StatusCode
	if status < 200 || status >= 300 {
		return &proxyRefusal{code: status}
	}
	if status == 204 || status == 205 || status == 206 {
		return errors.New("invalid UDP capsule response status")
	}
	for _, name := range []string{"Content-Length", "Content-Type", "Transfer-Encoding", "Content-Encoding"} {
		if _, present := response.Header[http.CanonicalHeaderKey(name)]; present {
			return fmt.Errorf("invalid %s in capsule response", name)
		}
	}
	if response.ContentLength >= 0 || len(response.TransferEncoding) != 0 {
		return errors.New("invalid capsule response body framing")
	}
	values := response.Header.Values("Capsule-Protocol")
	if len(values) != 1 || strings.TrimSpace(values[0]) != "?1" {
		return errors.New("proxy did not explicitly confirm capsule protocol")
	}
	return nil
}

func udpPath(host string, port int) (string, error) {
	if port < 1 || port > 65535 || host == "" {
		return "", errors.New("invalid UDP destination")
	}
	if strings.HasPrefix(host, "[") && strings.HasSuffix(host, "]") {
		host = host[1 : len(host)-1]
	}
	if address, err := netip.ParseAddr(host); err == nil {
		if address.Zone() != "" {
			return "", errors.New("scoped IPv6 destinations are unsupported")
		}
		host = address.String()
	} else {
		if len(host) > 253 {
			return "", errors.New("invalid UDP destination")
		}
		for _, label := range strings.Split(host, ".") {
			if label == "" || len(label) > 63 || label[0] == '-' || label[len(label)-1] == '-' {
				return "", errors.New("invalid UDP destination")
			}
			for _, c := range label {
				if !('a' <= c && c <= 'z' || 'A' <= c && c <= 'Z' || '0' <= c && c <= '9' || c == '-') {
					return "", errors.New("invalid UDP destination")
				}
			}
		}
		host = strings.ToLower(host)
	}
	var encoded strings.Builder
	for _, c := range []byte(host) {
		if c >= 'a' && c <= 'z' || c >= 'A' && c <= 'Z' || c >= '0' && c <= '9' || c == '-' || c == '.' || c == '_' || c == '~' {
			encoded.WriteByte(c)
		} else {
			fmt.Fprintf(&encoded, "%%%02X", c)
		}
	}
	return "/.well-known/masque/udp/" + encoded.String() + "/" + strconv.Itoa(port) + "/", nil
}
