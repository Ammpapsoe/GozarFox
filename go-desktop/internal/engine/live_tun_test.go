package engine

import (
	"bytes"
	"crypto/sha256"
	"encoding/binary"
	"fmt"
	"io"
	"net"
	"net/netip"
	"os"
	"os/exec"
	"runtime"
	"strings"
	"sync"
	"testing"
	"time"

	"github.com/bardia049b-stack/Gozar1/go-desktop/internal/dnsproxy"
	"github.com/bardia049b-stack/Gozar1/go-desktop/internal/wintun"
	"golang.org/x/net/dns/dnsmessage"
)

type liveResolver struct{}

func (liveResolver) Resolve(query []byte) ([]byte, error) {
	var request dnsmessage.Message
	if err := request.Unpack(query); err != nil || len(request.Questions) != 1 {
		return nil, fmt.Errorf("invalid test query")
	}
	question := request.Questions[0]
	return (&dnsmessage.Message{
		Header:    dnsmessage.Header{ID: request.Header.ID, Response: true, RecursionAvailable: true},
		Questions: []dnsmessage.Question{question},
		Answers: []dnsmessage.Resource{{
			Header: dnsmessage.ResourceHeader{Name: question.Name, Type: dnsmessage.TypeA, Class: dnsmessage.ClassINET, TTL: 60},
			Body:   &dnsmessage.AResource{A: [4]byte{203, 0, 113, 7}},
		}},
	}).Pack()
}

func (liveResolver) Close() {}

func liveDNSMessage(t *testing.T) []byte {
	t.Helper()
	name, err := dnsmessage.NewName("example.test.")
	if err != nil {
		t.Fatal(err)
	}
	packet, err := (&dnsmessage.Message{
		Header:    dnsmessage.Header{ID: 0x4a32, RecursionDesired: true},
		Questions: []dnsmessage.Question{{Name: name, Type: dnsmessage.TypeA, Class: dnsmessage.ClassINET}},
	}).Pack()
	if err != nil {
		t.Fatal(err)
	}
	return packet
}

func checkLiveDNS(t *testing.T, network string, query []byte) {
	t.Helper()
	conn, err := net.DialTimeout(network, net.JoinHostPort(dnsproxy.Address, "53"), 5*time.Second)
	if err != nil {
		t.Fatal(err)
	}
	defer conn.Close()
	_ = conn.SetDeadline(time.Now().Add(8 * time.Second))
	payload := query
	if network == "tcp4" {
		payload = append([]byte{byte(len(query) >> 8), byte(len(query))}, query...)
	}
	if _, err := conn.Write(payload); err != nil {
		t.Fatal(err)
	}
	var answer []byte
	if network == "tcp4" {
		header := make([]byte, 2)
		if _, err := io.ReadFull(conn, header); err != nil {
			t.Fatal(err)
		}
		answer = make([]byte, int(binary.BigEndian.Uint16(header)))
		if _, err := io.ReadFull(conn, answer); err != nil {
			t.Fatal(err)
		}
	} else {
		buf := make([]byte, 4096)
		count, err := conn.Read(buf)
		if err != nil {
			t.Fatal(err)
		}
		answer = buf[:count]
	}
	var response dnsmessage.Message
	if err := response.Unpack(answer); err != nil {
		t.Fatal(err)
	}
	if response.Header.ID != 0x4a32 || !response.Header.Response || len(response.Answers) != 1 {
		t.Fatalf("unexpected %s DNS response: %+v", network, response)
	}
	record, ok := response.Answers[0].Body.(*dnsmessage.AResource)
	if !ok || record.A != [4]byte{203, 0, 113, 7} {
		t.Fatalf("unexpected answer: %v", response.Answers)
	}
}

func windowsHostRoute(address netip.Addr) (bool, error) {
	output, err := exec.Command("route", "print", "-4", address.String()).CombinedOutput()
	if err != nil {
		return false, err
	}
	for _, line := range strings.Split(string(output), "\n") {
		fields := strings.Fields(line)
		if len(fields) >= 2 && fields[0] == address.String() && fields[1] == "255.255.255.255" {
			return true, nil
		}
	}
	return false, nil
}

func darwinHostRoute(address netip.Addr) (bool, error) {
	output, err := exec.Command("netstat", "-rn", "-f", "inet").CombinedOutput()
	if err != nil {
		return false, err
	}
	for _, line := range strings.Split(string(output), "\n") {
		fields := strings.Fields(line)
		if len(fields) >= 2 && (fields[0] == address.String() || fields[0] == address.String()+"/32") {
			return true, nil
		}
	}
	return false, nil
}

func windowsProxyState() string {
	var state strings.Builder
	for _, value := range []string{"ProxyEnable", "ProxyServer", "AutoConfigURL"} {
		out, err := exec.Command("reg", "query", `HKCU\Software\Microsoft\Windows\CurrentVersion\Internet Settings`, "/v", value).CombinedOutput()
		fmt.Fprintf(&state, "%s\n%v\n", out, err)
	}
	out, err := exec.Command("netsh", "winhttp", "show", "proxy").CombinedOutput()
	fmt.Fprintf(&state, "%s\n%v", out, err)
	return state.String()
}

func liveNetworkDiagnostics(t *testing.T, name string) {
	t.Helper()
	switch runtime.GOOS {
	case "linux":
		for _, args := range [][]string{{"-d", "address", "show", "dev", name}, {"-s", "link", "show", "dev", name}, {"route", "get", "198.18.0.80"}} {
			out, _ := exec.Command("ip", args...).CombinedOutput()
			t.Logf("%s", out)
		}
	case "windows":
		commands := []string{
			"Get-NetIPInterface -InterfaceAlias '" + name + "' | Format-List InterfaceAlias,AddressFamily,InterfaceIndex,ConnectionState,InterfaceMetric",
			"Get-NetIPAddress -InterfaceAlias '" + name + "' -AddressFamily IPv4 | Format-List IPAddress,PrefixLength,AddressState",
			"Get-NetRoute -AddressFamily IPv4 | Where-Object { $_.DestinationPrefix -in @('0.0.0.0/0','0.0.0.0/1','128.0.0.0/1') } | Format-Table -AutoSize DestinationPrefix,InterfaceAlias,NextHop,RouteMetric,State",
			"Find-NetRoute -RemoteIPAddress '198.18.0.80' | Format-List",
		}
		for _, script := range commands {
			out, _ := exec.Command("powershell.exe", "-NoProfile", "-NonInteractive", "-Command", script).CombinedOutput()
			t.Logf("%s", out)
		}
	}
}

func TestLiveKernelTunnel(t *testing.T) {
	if os.Getenv("GOZAR_LIVE_TUN_TEST") != "1" {
		t.Skip("requires an isolated, privileged kernel tunnel test environment")
	}
	if err := wintun.Ensure(); err != nil {
		t.Fatal(err)
	}
	var beforeProxy string
	if runtime.GOOS == "windows" {
		beforeProxy = windowsProxyState()
	}
	listener, err := net.Listen("tcp4", "127.0.0.1:0")
	if err != nil {
		t.Fatal(err)
	}
	defer listener.Close()
	go func() {
		for {
			conn, err := listener.Accept()
			if err != nil {
				return
			}
			go func() {
				defer conn.Close()
				_ = conn.SetDeadline(time.Now().Add(15 * time.Second))
				hash := sha256.New()
				if _, err := io.Copy(hash, conn); err == nil {
					_, _ = conn.Write(hash.Sum(nil))
				}
				if tcp, ok := conn.(*net.TCPConn); ok {
					_ = tcp.CloseWrite()
				}
			}()
		}
	}()
	dial := func(host string, port int) (io.ReadWriteCloser, error) {
		t.Logf("mock upstream requested %s:%d", host, port)
		if host != "198.18.0.80" || port != 443 {
			return nil, fmt.Errorf("unexpected destination %s:%d", host, port)
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
	if len(keep) > 0 && (runtime.GOOS == "windows" || runtime.GOOS == "darwin") {
		present, err := false, error(nil)
		if runtime.GOOS == "windows" {
			present, err = windowsHostRoute(keep[0])
		} else {
			present, err = darwinHostRoute(keep[0])
		}
		if err != nil || !present {
			t.Fatalf("protected proxy route was not installed: %v", err)
		}
	}
	if runtime.GOOS == "linux" && len(keep) > 0 && os.Getenv("GOZAR_EXPECT_PROTECT_IF") != "" {
		output, err := exec.Command("ip", "-4", "route", "get", keep[0].String()).CombinedOutput()
		if err != nil || !strings.Contains(string(output), "dev "+os.Getenv("GOZAR_EXPECT_PROTECT_IF")) {
			t.Fatalf("protected proxy route used the wrong interface: %s, %v", output, err)
		}
	}
	payload := bytes.Repeat([]byte("Gozar-real-TUN-test-"), 32768)
	expected := sha256.Sum256(payload)
	const count = 4
	results := make(chan error, count)
	var ready sync.WaitGroup
	ready.Add(count)
	for i := 0; i < count; i++ {
		go func() {
			ready.Done()
			conn, err := net.DialTimeout("tcp4", "198.18.0.80:443", 8*time.Second)
			if err != nil {
				results <- err
				return
			}
			defer conn.Close()
			_ = conn.SetDeadline(time.Now().Add(20 * time.Second))
			if _, err = conn.Write(payload); err != nil {
				results <- err
				return
			}
			tcp, ok := conn.(*net.TCPConn)
			if !ok {
				results <- fmt.Errorf("not TCP")
				return
			}
			if err = tcp.CloseWrite(); err != nil {
				results <- err
				return
			}
			response, err := io.ReadAll(conn)
			if err != nil {
				results <- err
				return
			}
			if !bytes.Equal(response, expected[:]) {
				results <- fmt.Errorf("upload or response was corrupted")
				return
			}
			results <- nil
		}()
	}
	ready.Wait()
	for i := 0; i < count; i++ {
		if err := <-results; err != nil {
			liveNetworkDiagnostics(t, e.Device())
			t.Fatal(err)
		}
	}
	query := liveDNSMessage(t)
	checkLiveDNS(t, "udp4", query)
	checkLiveDNS(t, "tcp4", query)
	down, up := e.Counters()
	if up < int64(count*len(payload)) || down < int64(count*len(expected)) {
		t.Fatalf("unexpected byte counters: down=%d up=%d", down, up)
	}
	name := e.Device()
	var changedService string
	if os.Getenv("GOZAR_LIVE_DNS_CHANGE_TEST") == "1" {
		if runtime.GOOS == "linux" {
			if err := os.WriteFile("/etc/resolv.conf", []byte("nameserver 192.0.2.99\n"), 0o644); err != nil {
				t.Fatal(err)
			}
		} else if runtime.GOOS == "darwin" {
			if len(e.config.ChangedServices) == 0 {
				t.Fatal("no macOS DNS service to test")
			}
			changedService = e.config.ChangedServices[0]
			original := append([]string(nil), e.config.ServiceDNS[changedService]...)
			t.Cleanup(func() {
				if len(original) == 0 {
					original = []string{"Empty"}
				}
				args := append([]string{"-setdnsservers", changedService}, original...)
				_ = exec.Command("networksetup", args...).Run()
			})
			if out, err := exec.Command("networksetup", "-setdnsservers", changedService, "9.9.9.9").CombinedOutput(); err != nil {
				t.Fatalf("change macOS DNS: %v: %s", err, out)
			}
		}
	}
	e.Stop()
	if runtime.GOOS == "windows" && beforeProxy != windowsProxyState() {
		t.Fatal("system proxy settings changed")
	}
	if len(keep) > 0 && (runtime.GOOS == "windows" || runtime.GOOS == "darwin") {
		present, err := false, error(nil)
		if runtime.GOOS == "windows" {
			present, err = windowsHostRoute(keep[0])
		} else {
			present, err = darwinHostRoute(keep[0])
		}
		if err != nil || present {
			t.Fatalf("protected proxy route was not removed: %v", err)
		}
	}
	if changedService != "" {
		out, err := exec.Command("networksetup", "-getdnsservers", changedService).CombinedOutput()
		if err != nil || strings.TrimSpace(string(out)) != "9.9.9.9" {
			t.Fatalf("newer macOS DNS was overwritten: %v: %s", err, out)
		}
	}
	if runtime.GOOS == "linux" && os.Getenv("GOZAR_LIVE_DNS_CHANGE_TEST") == "1" {
		current, err := os.ReadFile("/etc/resolv.conf")
		if err != nil || string(current) != "nameserver 192.0.2.99\n" {
			t.Fatalf("newer DNS settings were overwritten: %q %v", current, err)
		}
	}
	t.Logf("real TUN %s passed concurrent TCP upload, half-close, UDP DNS, TCP DNS and shutdown", name)
}
