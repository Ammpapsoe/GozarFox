package tundev

import (
	"context"
	"errors"
	"net"
	"net/netip"
	"strconv"
	"strings"
	"time"

	"github.com/bardia049b-stack/Gozar1/go-desktop/internal/quiet"
)

type Config struct {
	Interface       string
	DNS             string
	Keep            []netip.Addr
	AddedKeep       []netip.Addr
	Routes          [2]bool
	RoutesV6        [2]bool
	DNSBefore       []byte
	DNSChanged      bool
	ServiceDNS      map[string][]string
	ChangedServices []string
	windowsGateway  string
	windowsIndex    string
	linuxKeepRoutes [][]string
	darwinGateway   string
}

func Configure(cfg *Config) error {
	if err := configure(cfg); err != nil {
		restore(cfg)
		return err
	}
	return nil
}

func Restore(cfg *Config) {
	restore(cfg)
}

func Neighbours(host string) []netip.Addr {
	if address, err := netip.ParseAddr(host); err == nil {
		return []netip.Addr{address.Unmap()}
	}
	found, err := net.LookupIP(host)
	if err != nil {
		return nil
	}
	var out []netip.Addr
	for _, address := range found {
		if parsed, ok := netip.AddrFromSlice(address); ok && parsed.Unmap().Is4() {
			out = append(out, parsed.Unmap())
		}
	}
	return out
}

func Privileged() bool {
	return privileged()
}

func isRoot() bool {
	out, err := command("id", "-u")
	if err != nil {
		return false
	}
	return strings.TrimSpace(out) == "0"
}

func command(name string, args ...string) (string, error) {
	ctx, cancel := context.WithTimeout(context.Background(), 20*time.Second)
	defer cancel()
	cmd := quiet.CommandContext(ctx, name, args...)
	out, err := cmd.CombinedOutput()
	if errors.Is(ctx.Err(), context.DeadlineExceeded) {
		return "", errors.New(name + " timed out")
	}
	return string(out), err
}

func mustRun(name string, args ...string) error {
	if _, err := command(name, args...); err != nil {
		return errors.New(strings.Join(append([]string{name}, args...), " ") + " failed")
	}
	return nil
}

func itoa(value int) string { return strconv.Itoa(value) }

func blackhole6(name string, args ...string) error {
	_, err := command(name, args...)
	return err
}
