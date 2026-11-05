package tundev

import (
	"bytes"
	"errors"
	"net/netip"
	"os"
	"strings"
)

const resolvConf = "/etc/resolv.conf"

func configure(cfg *Config) error {
	if !isRoot() {
		return errors.New("the tunnel needs root")
	}
	if err := mustRun("ip", "link", "set", "dev", cfg.Interface, "up"); err != nil {
		return err
	}
	if err := mustRun("ip", "addr", "add", Address.String()+"/32", "dev", cfg.Interface); err != nil {
		return err
	}
	for _, keep := range cfg.Keep {
		existing, err := command("ip", "-4", "route", "show", "exact", keep.String()+"/32")
		if err != nil {
			return err
		}
		if strings.TrimSpace(existing) != "" {
			continue
		}
		out, err := command("ip", "-4", "route", "get", keep.String())
		if err != nil {
			return err
		}
		args, needed, err := routeForKeep(keep, out)
		if err != nil {
			return err
		}
		if !needed {
			continue
		}
		if err := mustRun("ip", args...); err != nil {
			return err
		}
		remove := append([]string(nil), args...)
		remove[2] = "del"
		cfg.linuxKeepRoutes = append(cfg.linuxKeepRoutes, remove)
	}
	if err := mustRun("ip", "route", "add", "0.0.0.0/1", "dev", cfg.Interface); err != nil {
		return err
	}
	cfg.Routes[0] = true
	if err := mustRun("ip", "route", "add", "128.0.0.0/1", "dev", cfg.Interface); err != nil {
		return err
	}
	cfg.Routes[1] = true
	for index, prefix := range [2]string{"::/1", "8000::/1"} {
		if err := blackhole6("ip", "-6", "route", "add", prefix, "dev", cfg.Interface); err == nil {
			cfg.RoutesV6[index] = true
		}
	}
	before, err := os.ReadFile(resolvConf)
	if err != nil {
		return err
	}
	cfg.DNSBefore = before
	if err := os.WriteFile(resolvConf, []byte("nameserver "+cfg.DNS+"\n"), 0o644); err != nil {
		_ = os.WriteFile(resolvConf, before, 0o644)
		return err
	}
	cfg.DNSChanged = true
	return nil
}

func routeForKeep(address netip.Addr, output string) ([]string, bool, error) {
	fields := strings.Fields(strings.SplitN(output, "\n", 2)[0])
	if len(fields) == 0 {
		return nil, false, errors.New("no route to the proxy")
	}
	if fields[0] == "local" {
		return nil, false, nil
	}
	var device string
	var gateway []string
	for index, field := range fields {
		if field == "dev" && index+1 < len(fields) {
			device = fields[index+1]
		}
		if field == "via" && index+1 < len(fields) {
			gateway = []string{"via", fields[index+1]}
			if (fields[index+1] == "inet" || fields[index+1] == "inet6") && index+2 < len(fields) {
				gateway = append(gateway, fields[index+2])
			}
		}
	}
	if device == "" {
		return nil, false, errors.New("no usable route to the proxy")
	}
	args := []string{"-4", "route", "add", address.String() + "/32"}
	args = append(args, gateway...)
	args = append(args, "dev", device)
	return args, true, nil
}

func restore(cfg *Config) {
	if cfg.DNSChanged {
		current, err := os.ReadFile(resolvConf)
		if err == nil && bytes.Equal(current, []byte("nameserver "+cfg.DNS+"\n")) {
			_ = os.WriteFile(resolvConf, cfg.DNSBefore, 0o644)
		}
		cfg.DNSChanged = false
	}
	if cfg.Routes[0] {
		mustRun("ip", "route", "delete", "0.0.0.0/1", "dev", cfg.Interface)
		cfg.Routes[0] = false
	}
	if cfg.Routes[1] {
		mustRun("ip", "route", "delete", "128.0.0.0/1", "dev", cfg.Interface)
		cfg.Routes[1] = false
	}
	for index, prefix := range [2]string{"::/1", "8000::/1"} {
		if cfg.RoutesV6[index] {
			mustRun("ip", "-6", "route", "delete", prefix, "dev", cfg.Interface)
			cfg.RoutesV6[index] = false
		}
	}
	for _, route := range cfg.linuxKeepRoutes {
		_ = mustRun("ip", route...)
	}
	cfg.linuxKeepRoutes = nil
}

func privileged() bool { return isRoot() }

func keep(cfg *Config, address netip.Addr) error {
	if !address.Is4() {
		return errors.New("direct split currently supports IPv4")
	}
	for _, existing := range cfg.AddedKeep {
		if existing == address {
			return nil
		}
	}
	existing, err := command("ip", "-4", "route", "show", "exact", address.String()+"/32")
	if err != nil {
		return err
	}
	if strings.TrimSpace(existing) != "" {
		return nil
	}
	out, err := command("ip", "-4", "route", "get", address.String())
	if err != nil {
		return err
	}
	args, needed, err := routeForKeep(address, out)
	if err != nil || !needed {
		return err
	}
	if err := mustRun("ip", args...); err != nil {
		return err
	}
	remove := append([]string(nil), args...)
	remove[2] = "del"
	cfg.linuxKeepRoutes = append(cfg.linuxKeepRoutes, remove)
	cfg.AddedKeep = append(cfg.AddedKeep, address)
	return nil
}
