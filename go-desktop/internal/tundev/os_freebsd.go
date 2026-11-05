package tundev

import (
	"errors"
	"net/netip"
	"os"
	"strings"
)

const freebsdResolvConf = "/etc/resolv.conf"

func configure(cfg *Config) error {
	if !isRoot() {
		return errors.New("the tunnel needs administrator rights")
	}
	if err := mustRun("ifconfig", cfg.Interface, "inet", Address.String(), Address.String(),
		"netmask", "255.255.255.255", "up"); err != nil {
		return err
	}
	out, err := command("route", "-n", "get", "default")
	if err != nil {
		return err
	}
	var gateway string
	for _, line := range strings.Split(out, "\n") {
		fields := strings.Fields(line)
		if len(fields) == 2 && fields[0] == "gateway:" {
			gateway = fields[1]
			break
		}
	}
	if gateway == "" {
		return errors.New("no usable default gateway")
	}
	cfg.darwinGateway = gateway
	for _, keep := range cfg.Keep {
		if err := mustRun("route", "-n", "add", "-host", keep.String(), gateway); err != nil {
			return err
		}
		cfg.AddedKeep = append(cfg.AddedKeep, keep)
	}
	if err := mustRun("route", "-n", "add", "-net", "0.0.0.0/1", "-interface", cfg.Interface); err != nil {
		return err
	}
	cfg.Routes[0] = true
	if err := mustRun("route", "-n", "add", "-net", "128.0.0.0/1", "-interface", cfg.Interface); err != nil {
		return err
	}
	cfg.Routes[1] = true
	if before, err := os.ReadFile(freebsdResolvConf); err == nil {
		cfg.DNSBefore = before
		cfg.DNSChanged = true
		if err := writeFreeBSDResolvConf(cfg.DNS); err != nil {
			return err
		}
	}
	return nil
}

func writeFreeBSDResolvConf(server string) error {
	body := []byte("nameserver " + server + "\n")
	temporary := freebsdResolvConf + ".gozar"
	if err := os.WriteFile(temporary, body, 0o644); err != nil {
		return err
	}
	return os.Rename(temporary, freebsdResolvConf)
}

func restore(cfg *Config) {
	for index, prefix := range [2]string{"0.0.0.0/1", "128.0.0.0/1"} {
		if cfg.Routes[index] {
			mustRun("route", "-n", "delete", "-net", prefix, "-interface", cfg.Interface)
			cfg.Routes[index] = false
		}
	}
	for _, keep := range cfg.AddedKeep {
		if cfg.darwinGateway == "" {
			continue
		}
		mustRun("route", "-n", "delete", "-host", keep.String(), cfg.darwinGateway)
	}
	cfg.AddedKeep = nil
	cfg.darwinGateway = ""
	if cfg.DNSChanged {
		if len(cfg.DNSBefore) > 0 {
			temporary := freebsdResolvConf + ".gozar"
			if err := os.WriteFile(temporary, cfg.DNSBefore, 0o644); err == nil {
				_ = os.Rename(temporary, freebsdResolvConf)
			}
		}
		cfg.DNSBefore = nil
		cfg.DNSChanged = false
	}
}

func privileged() bool { return isRoot() }

func keep(cfg *Config, address netip.Addr) error {
	if !address.Is4() {
		return errors.New("direct split currently supports IPv4")
	}
	if cfg.darwinGateway == "" {
		return errors.New("no usable default gateway")
	}
	for _, existing := range cfg.AddedKeep {
		if existing == address {
			return nil
		}
	}
	if err := mustRun("route", "-n", "add", "-host", address.String(), cfg.darwinGateway); err != nil {
		return err
	}
	cfg.AddedKeep = append(cfg.AddedKeep, address)
	return nil
}
