// Package signinproxy is the loopback CONNECT proxy for the sign-in web
// view, and nothing else.
//
// Why it exists: a web view left to itself dials the single address the
// system resolver returns, writes a plain ClientHello, and waits as long as
// the network wants. On networks that poison, blackhole or shape some of
// Mozilla's addresses that means a page whose load never completes. Through
// this proxy the same page gets three things the bare view does not:
//
//   - every address of a name is tried at once and the first answer wins,
//     with the system resolver and independent ones unioned first, so one
//     dead or poisoned answer cannot decide the outcome;
//   - the TLS ClientHello is written in two fragments split inside the SNI
//     name, so a middlebox matching the name in one packet sees none;
//   - every wait is bounded, so a host that never answers becomes an error
//     the window can close on, not a spinner that never stops.
//
// It is not a VPN and not a general proxy: it serves only while Start has
// run and Stop has not, only the TLS port, and only the hosts of a Mozilla
// sign-in and the partners that page itself offers. Anything else is
// refused with a status and closed.
package signinproxy

import (
	"bytes"
	"context"
	"fmt"
	"io"
	"net"
	"strconv"
	"strings"
	"sync"
	"sync/atomic"
	"time"
)

const (
	// The only port a sign-in CONNECT may ask for.
	tlsPort = 443

	// How long the address race may take before the tunnel reports 504.
	dialBudget = 30 * time.Second

	// How long the independent resolvers may take before the race goes on
	// without them.
	resolverBudget = 4 * time.Second

	// How long reading the CONNECT request and the first TLS block may take.
	handshakeRead = 30 * time.Second

	// How many bytes of the SNI name stay in the first fragment.
	sniPrefix = 3

	maxRequestBytes = 8 * 1024
	pumpBlock       = 16 * 1024
)

// The hosts a Mozilla sign-in page and its fonts really use.
var signInHosts = map[string]bool{
	"accounts.firefox.com":       true,
	"oauth.accounts.firefox.com": true,
	"fonts.gstatic.com":          true,
	"fonts.googleapis.com":       true,
}

// The domains a sign-in on accounts.firefox.com lives in, the domain itself
// included: the page, its API, its OAuth endpoint and its bundles are spread
// over subdomains, and an exact-host list starves the real page into a blank
// screen.
var signInDomains = []string{
	"firefox.com",
	"mozilla.com",
	"mozilla.org",
	"mozilla.net",
}

// Named outside hosts the sign-in page itself offers: the "continue with
// Google / Apple" routes and the font and script CDNs they need.
var partnerHosts = map[string]bool{
	"accounts.google.com":       true,
	"accounts.youtube.com":      true,
	"apis.google.com":           true,
	"play.google.com":           true,
	"www.google.com":            true,
	"ssl.gstatic.com":           true,
	"www.gstatic.com":           true,
	"fonts.gstatic.com":         true,
	"fonts.googleapis.com":      true,
	"lh3.googleusercontent.com": true,
	"appleid.apple.com":         true,
	"appleid.cdn-apple.com":     true,
	"idmsa.apple.com":           true,
	"iforgot.apple.com":         true,
	"www.apple.com":             true,
}

// Proxy is one loopback listener plus the tunnels it accepted. Start is
// idempotent (the web view environment of a process is made once and keeps
// the port it was made with), Stop closes everything and is safe twice.
type Proxy struct {
	trace func(string)

	mu       sync.Mutex
	listener net.Listener
	stopped  bool
	conns    map[net.Conn]struct{}
	seq      int
}

// New makes a proxy that is not listening yet. trace receives one line per
// decision, shaped for a debug log and never carrying secrets.
func New(trace func(string)) *Proxy {
	if trace == nil {
		trace = func(string) {}
	}
	return &Proxy{trace: trace, conns: make(map[net.Conn]struct{})}
}

// Start binds an ephemeral loopback port and accepts in the background.
// Calling it twice keeps the first listener and reports its port again.
func (p *Proxy) Start() (int, error) {
	p.mu.Lock()
	defer p.mu.Unlock()
	if p.listener != nil {
		return p.listener.Addr().(*net.TCPAddr).Port, nil
	}
	// IPv4 loopback, named: the web view is told "127.0.0.1", so the
	// listener must not depend on which loopback the platform prefers.
	listener, err := net.Listen("tcp4", "127.0.0.1:0")
	if err != nil {
		return 0, err
	}
	p.listener = listener
	p.stopped = false
	go p.accept(listener)
	p.trace(fmt.Sprintf("listening port=%d", listener.Addr().(*net.TCPAddr).Port))
	return listener.Addr().(*net.TCPAddr).Port, nil
}

// Stop closes the listener and every open tunnel. A proxy that leaked its
// tunnels would keep the web view hanging after the sign-in closed.
func (p *Proxy) Stop() {
	p.mu.Lock()
	if p.stopped && p.listener == nil {
		p.mu.Unlock()
		return
	}
	p.stopped = true
	listener := p.listener
	p.listener = nil
	open := make([]net.Conn, 0, len(p.conns))
	for conn := range p.conns {
		open = append(open, conn)
	}
	p.conns = make(map[net.Conn]struct{})
	p.mu.Unlock()
	if listener != nil {
		listener.Close()
	}
	for _, conn := range open {
		conn.Close()
	}
	p.trace("stopped")
}

func (p *Proxy) track(conn net.Conn) {
	p.mu.Lock()
	p.conns[conn] = struct{}{}
	p.mu.Unlock()
}

func (p *Proxy) untrack(conn net.Conn) {
	p.mu.Lock()
	delete(p.conns, conn)
	p.mu.Unlock()
}

func (p *Proxy) accept(listener net.Listener) {
	for {
		client, err := listener.Accept()
		if err != nil {
			return
		}
		p.mu.Lock()
		p.seq++
		id := p.seq
		p.mu.Unlock()
		p.track(client)
		go p.handle(client, id)
	}
}

func (p *Proxy) handle(client net.Conn, id int) {
	defer func() {
		client.Close()
		p.untrack(client)
	}()
	client.SetReadDeadline(time.Now().Add(handshakeRead))
	host, port, ok := readRequest(client)
	if !ok {
		p.trace(fmt.Sprintf("#%d handshake unreadable", id))
		return
	}
	p.trace(fmt.Sprintf("#%d connect %s:%d", id, host, port))
	if port != tlsPort {
		p.trace(fmt.Sprintf("#%d refused port=%d", id, port))
		answer(client, 403)
		return
	}
	if !permits(host) {
		p.trace(fmt.Sprintf("#%d blocked host=%s", id, host))
		answer(client, 403)
		return
	}
	target := p.dial(host, port, id)
	if target == nil {
		answer(client, 504)
		return
	}
	p.track(target)
	client.SetReadDeadline(time.Time{})
	answer(client, 200)
	p.tunnel(client, target, host, id)
	target.Close()
	p.untrack(target)
}

// permits reports whether rawHost belongs to the sign-in. An explicitly
// injected host passes as named; after that, addresses never pass (a sign-in
// names its hosts), a partner host passes as named, and any host inside an
// allowed domain passes. Comparison is case-folded and a trailing dot is not
// a difference; a lookalike ("accounts.firefox.com.evil.example") is not a
// suffix match.
func permits(rawHost string) bool {
	host := strings.ToLower(strings.TrimSuffix(strings.TrimSpace(rawHost), "."))
	if host == "" {
		return false
	}
	if signInHosts[host] {
		return true
	}
	if isAddress(host) {
		return false
	}
	if partnerHosts[host] {
		return true
	}
	for _, domain := range signInDomains {
		if host == domain || strings.HasSuffix(host, "."+domain) {
			return true
		}
	}
	return false
}

// isAddress is true for anything shaped like a literal address, not a name.
func isAddress(host string) bool {
	if strings.Contains(host, ":") {
		return true
	}
	for _, r := range host {
		if r != '.' && (r < '0' || r > '9') {
			return false
		}
	}
	return host != ""
}

// readRequest parses `CONNECT host:port`; ok is false when the bytes are not
// one request. Reading stops at the blank line or at the size cap.
func readRequest(conn net.Conn) (host string, port int, ok bool) {
	var buffer bytes.Buffer
	one := make([]byte, 1)
	for buffer.Len() < maxRequestBytes {
		n, err := conn.Read(one)
		if err != nil || n == 0 {
			return "", 0, false
		}
		buffer.WriteByte(one[0])
		if filled := buffer.Bytes(); len(filled) >= 4 && bytes.HasSuffix(filled, []byte("\r\n\r\n")) {
			break
		}
	}
	line, _, _ := strings.Cut(buffer.String(), "\r\n")
	fields := strings.Split(line, " ")
	if len(fields) < 2 || !strings.EqualFold(fields[0], "CONNECT") {
		return "", 0, false
	}
	authority := fields[1]
	colon := strings.LastIndex(authority, ":")
	if colon < 0 {
		return "", 0, false
	}
	host = strings.ToLower(strings.TrimSuffix(authority[:colon], "."))
	port, err := strconv.Atoi(authority[colon+1:])
	if err != nil || port <= 0 || port > 65535 {
		return "", 0, false
	}
	return host, port, true
}

// dial races every address of the name at once inside the budget. The first
// connection wins; the rest are closed behind it. Nil when no address
// answered in time, which the caller reports as 504.
func (p *Proxy) dial(host string, port, id int) net.Conn {
	lookup, err := net.DefaultResolver.LookupIPAddr(context.Background(), host)
	if err != nil {
		p.trace(fmt.Sprintf("#%d name-unresolved host=%s", id, host))
		lookup = nil
	}
	extra := p.resolveIndependently(host, id)
	seen := make(map[string]bool)
	var addresses []string
	for _, group := range [][]net.IPAddr{toIPAddrs(lookup), extra} {
		for _, addr := range group {
			name := addr.IP.String()
			if seen[name] {
				continue
			}
			seen[name] = true
			addresses = append(addresses, net.JoinHostPort(name, strconv.Itoa(port)))
		}
	}
	if len(addresses) == 0 {
		return nil
	}
	p.trace(fmt.Sprintf("#%d racing %d address(es) for %s", id, len(addresses), host))

	ctx, cancel := context.WithTimeout(context.Background(), dialBudget)
	defer cancel()
	dialer := &net.Dialer{}
	// Buffered to the racer count: a winner never blocks on the report, and
	// whoever loses finds the context cancelled and closes its own socket.
	winner := make(chan net.Conn, len(addresses))
	var racers sync.WaitGroup
	var remaining atomic.Int32
	remaining.Store(int32(len(addresses)))
	allAnswered := make(chan struct{})
	for _, address := range addresses {
		racers.Add(1)
		go func(address string) {
			defer racers.Done()
			conn, err := dialer.DialContext(ctx, "tcp", address)
			if err != nil {
				// A refused or unreachable address is an answer too: when
				// every address has answered without a winner the wait ends
				// now instead of at the budget.
				p.trace(fmt.Sprintf("#%d dial-failed %s", id, address))
				if remaining.Add(-1) == 0 {
					close(allAnswered)
				}
				return
			}
			if ctx.Err() != nil {
				conn.Close()
				return
			}
			winner <- conn
		}(address)
	}
	go func() {
		racers.Wait()
		close(allAnswered)
	}()

	var got net.Conn
	select {
	case got = <-winner:
		p.trace(fmt.Sprintf("#%d dialed %s", id, got.RemoteAddr()))
	case <-allAnswered:
		// Every racer finished; one of them may have won in the same moment.
		select {
		case got = <-winner:
		default:
		}
	case <-ctx.Done():
	}
	cancel()
	// Anything still in flight closes itself on the cancelled context;
	// whatever already landed here is closed by hand.
	for {
		select {
		case late := <-winner:
			if late != got {
				late.Close()
			}
		default:
			return got
		}
	}
}

func toIPAddrs(found []net.IPAddr) []net.IPAddr { return found }

// resolveIndependently asks public resolvers over plain DNS, in parallel
// with everything else, because the system resolver is exactly what a
// filtering network poisons first. Every failure here is silent in effect:
// the union with the system answers is what gets raced, and a resolver that
// cannot be reached simply adds nothing.
func (p *Proxy) resolveIndependently(host string, id int) []net.IPAddr {
	// An IP literal needs no resolver, poisoned or otherwise.
	if host == "" || isAddress(host) {
		return nil
	}
	servers := []string{"1.1.1.1", "8.8.8.8"}
	var mu sync.Mutex
	var found []net.IPAddr
	var workers sync.WaitGroup
	for _, server := range servers {
		workers.Add(2)
		go func(server string) {
			defer workers.Done()
			answers, err := queryUDP(server, buildQuery(host))
			if err == nil {
				mu.Lock()
				found = append(found, answers...)
				mu.Unlock()
			}
		}(server)
		go func(server string) {
			defer workers.Done()
			answers, err := queryTCP(server, buildQuery(host))
			if err == nil {
				mu.Lock()
				found = append(found, answers...)
				mu.Unlock()
			}
		}(server)
	}
	done := make(chan struct{})
	go func() {
		workers.Wait()
		close(done)
	}()
	select {
	case <-done:
	case <-time.After(resolverBudget):
	}
	mu.Lock()
	defer mu.Unlock()
	if len(found) > 0 {
		names := make([]string, 0, len(found))
		for _, addr := range found {
			names = append(names, addr.IP.String())
		}
		p.trace(fmt.Sprintf("#%d independent-resolver %s", id, strings.Join(names, ",")))
	}
	return found
}

// buildQuery is a minimal A-record query: one question, recursion desired.
func buildQuery(host string) []byte {
	var out bytes.Buffer
	out.Write([]byte{0x4f, 0x2a, 0x01, 0x00, 0x00, 0x01, 0, 0, 0, 0, 0, 0})
	for _, label := range strings.Split(host, ".") {
		out.WriteByte(byte(len(label)))
		out.WriteString(label)
	}
	out.WriteByte(0)
	out.Write([]byte{0, 1, 0, 1})
	return out.Bytes()
}

// parseAnswers returns the A answers of a response, ignoring everything else.
func parseAnswers(message []byte) []net.IPAddr {
	if len(message) < 12 {
		return nil
	}
	questions := int(message[4])<<8 | int(message[5])
	answers := int(message[6])<<8 | int(message[7])
	at := 12
	for i := 0; i < questions; i++ {
		at = skipName(message, at)
		at += 4
	}
	var found []net.IPAddr
	for i := 0; i < answers; i++ {
		if at >= len(message) {
			return found
		}
		at = skipName(message, at)
		if at+10 > len(message) {
			return found
		}
		kind := int(message[at])<<8 | int(message[at+1])
		length := int(message[at+8])<<8 | int(message[at+9])
		at += 10
		if at+length > len(message) {
			return found
		}
		if kind == 1 && length == 4 {
			ip := net.IP(append([]byte(nil), message[at:at+4]...))
			if addr, err := net.ResolveIPAddr("ip4", ip.String()); err == nil {
				found = append(found, *addr)
			}
		}
		at += length
	}
	return found
}

func skipName(message []byte, from int) int {
	at := from
	for at < len(message) {
		length := int(message[at])
		if length == 0 {
			return at + 1
		}
		if length&0xc0 == 0xc0 {
			return at + 2
		}
		at += length + 1
	}
	return len(message)
}

func queryUDP(server string, query []byte) ([]net.IPAddr, error) {
	conn, err := net.Dial("udp", net.JoinHostPort(server, "53"))
	if err != nil {
		return nil, err
	}
	defer conn.Close()
	conn.SetDeadline(time.Now().Add(resolverBudget))
	if _, err := conn.Write(query); err != nil {
		return nil, err
	}
	back := make([]byte, 4096)
	n, err := conn.Read(back)
	if err != nil {
		return nil, err
	}
	return parseAnswers(back[:n]), nil
}

func queryTCP(server string, query []byte) ([]net.IPAddr, error) {
	conn, err := net.DialTimeout("tcp", net.JoinHostPort(server, "53"), resolverBudget)
	if err != nil {
		return nil, err
	}
	defer conn.Close()
	conn.SetDeadline(time.Now().Add(resolverBudget))
	length := []byte{byte(len(query) >> 8), byte(len(query))}
	if _, err := conn.Write(length); err != nil {
		return nil, err
	}
	if _, err := conn.Write(query); err != nil {
		return nil, err
	}
	head := make([]byte, 2)
	if _, err := io.ReadFull(conn, head); err != nil {
		return nil, err
	}
	size := int(head[0])<<8 | int(head[1])
	if size <= 0 || size > 64*1024 {
		return nil, io.ErrUnexpectedEOF
	}
	back := make([]byte, size)
	if _, err := io.ReadFull(conn, back); err != nil {
		return nil, err
	}
	return parseAnswers(back), nil
}

// tunnel copies both ways, but the first client block - the ClientHello,
// still plain bytes at this layer - goes out in two fragments split inside
// the SNI name when one is present.
func (p *Proxy) tunnel(client, target net.Conn, host string, id int) {
	if tcp, ok := target.(*net.TCPConn); ok {
		tcp.SetNoDelay(true)
	}
	block := make([]byte, pumpBlock)
	client.SetReadDeadline(time.Now().Add(handshakeRead))
	read, err := client.Read(block)
	client.SetReadDeadline(time.Time{})
	if err != nil && read == 0 {
		return
	}
	if read > 0 {
		at := splitPoint(block[:read], host)
		target.Write(block[:at])
		target.Write(block[at:read])
		p.trace(fmt.Sprintf("#%d hello split at %d of %d", id, at, read))
	}
	up := make(chan struct{})
	go func() {
		defer close(up)
		io.Copy(target, client)
		if tcp, ok := target.(*net.TCPConn); ok {
			tcp.CloseWrite()
		}
	}()
	io.Copy(client, target)
	<-up
}

// splitPoint splits inside the server name so no single fragment carries it
// whole. Falls back to the middle of a handshake-shaped block, and to no
// split at all for anything too small to matter.
func splitPoint(block []byte, host string) int {
	if len(block) < 16 {
		return len(block)
	}
	if at := bytes.Index(block, []byte(host)); at >= 0 && at+sniPrefix < len(block) {
		return at + sniPrefix
	}
	if block[0] == 0x16 {
		return len(block) / 2
	}
	return len(block)
}

func answer(conn net.Conn, status int) {
	reason := "Gateway Timeout"
	switch status {
	case 200:
		reason = "Connection Established"
	case 403:
		reason = "Forbidden"
	}
	conn.Write([]byte(fmt.Sprintf("HTTP/1.1 %d %s\r\n\r\n", status, reason)))
}
