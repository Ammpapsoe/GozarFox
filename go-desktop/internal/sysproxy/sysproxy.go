package sysproxy

import (
	"context"
	"net"
	"os/exec"
	"runtime"
	"strconv"
	"strings"
	"time"

	"github.com/Ammpapsoe/GozarFox/go-desktop/internal/quiet"
)

func Set(port int) error {
	switch platform() {
	case "windows":
		if err := reg("ProxyServer", "127.0.0.1:"+itoa(port)); err != nil {
			return err
		}
		if err := reg("ProxyOverride", "localhost;127.*;<local>"); err != nil {
			return err
		}
		if err := dword("ProxyEnable", "1"); err != nil {
			return err
		}
		run("netsh", "winhttp", "set", "proxy", "127.0.0.1:"+itoa(port))
	case "darwin":
		for _, service := range networkServices() {
			run("networksetup", "-setwebproxy", service, "127.0.0.1", itoa(port))
			run("networksetup", "-setsecurewebproxy", service, "127.0.0.1", itoa(port))
			run("networksetup", "-setsocksfirewallproxy", service, "127.0.0.1", itoa(port))
		}
	default:
		gnome("mode", "manual")
		gnome("http", "host", "127.0.0.1")
		gnome("http", "port", itoa(port))
		gnome("https", "host", "127.0.0.1")
		gnome("https", "port", itoa(port))
		gnome("socks", "host", "127.0.0.1")
		gnome("socks", "port", itoa(port))
	}
	return nil
}

func Clear() error {
	switch platform() {
	case "windows":
		if err := dword("ProxyEnable", "0"); err != nil {
			return err
		}
		run("netsh", "winhttp", "reset", "proxy")
	case "darwin":
		for _, service := range networkServices() {
			run("networksetup", "-setwebproxystate", service, "off")
			run("networksetup", "-setsecurewebproxystate", service, "off")
			run("networksetup", "-setsocksfirewallproxystate", service, "off")
		}
	default:
		gnome("mode", "none")
	}
	return nil
}

func Stale() bool {
	port := Current()
	if port < 10810 || port > 10830 {
		return false
	}
	return !listening(port)
}

func platform() string {
	switch runtime.GOOS {
	case "windows":
		return "windows"
	case "darwin":
		return "darwin"
	}
	return "linux"
}

func networkServices() []string {
	out, err := exec.Command("networksetup", "-listallnetworkservices").Output()
	if err != nil {
		return nil
	}
	var services []string
	for _, line := range strings.Split(string(out), "\n") {
		line = strings.TrimSpace(line)
		if line == "" || strings.HasPrefix(line, "*") || strings.Contains(strings.ToLower(line), "denotes") ||
			strings.EqualFold(line, "An asterisk") {
			continue
		}
		services = append(services, line)
	}
	return services
}

func gnome(values ...string) bool {
	args := append([]string{"set"}, values...)
	return run("gsettings", args...)
}

func run(name string, args ...string) bool {
	ctx, cancel := context.WithTimeout(context.Background(), 20*time.Second)
	defer cancel()
	return quiet.CommandContext(ctx, name, args...).Run() == nil
}

func itoa(value int) string { return strconv.Itoa(value) }

func listening(port int) bool {
	conn, err := net.DialTimeout("tcp", "127.0.0.1:"+itoa(port), 400*time.Millisecond)
	if err != nil {
		return false
	}
	conn.Close()
	return true
}
