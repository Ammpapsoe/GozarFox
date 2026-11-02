package tundev

import (
	"errors"
	"net"
	"strings"
)

func configure(cfg *Config) error {
	if !isRoot() {
		return errors.New("the tunnel needs administrator rights")
	}
	if err := mustRun("ifconfig", cfg.Interface, Address.String(), Address.String(),
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
	for index, prefix := range [2]string{"::/1", "8000::/1"} {
		if err := blackhole6("route", "-n", "add", "-net", prefix, "-interface", cfg.Interface); err == nil {
			cfg.RoutesV6[index] = true
		}
	}
	cfg.ServiceDNS = make(map[string][]string)
	list, err := services()
	if err != nil {
		return err
	}
	for _, service := range list {
		before, err := command("networksetup", "-getdnsservers", service)
		if err != nil {
			return err
		}
		cfg.ServiceDNS[service] = serviceDNS(before)
		if err := mustRun("networksetup", "-setdnsservers", service, cfg.DNS); err != nil {
			return err
		}
		cfg.ChangedServices = append(cfg.ChangedServices, service)
	}
	return nil
}

func restore(cfg *Config) {
	for _, service := range cfg.ChangedServices {
		current, err := command("networksetup", "-getdnsservers", service)
		if err != nil {
			continue
		}
		addresses := serviceDNS(current)
		if len(addresses) != 1 || addresses[0] != cfg.DNS {
			continue
		}
		values := cfg.ServiceDNS[service]
		if len(values) == 0 {
			values = []string{"Empty"}
		}
		args := append([]string{"-setdnsservers", service}, values...)
		mustRun("networksetup", args...)
	}
	cfg.ChangedServices = nil
	if cfg.Routes[0] {
		mustRun("route", "-n", "delete", "-net", "0.0.0.0/1", "-interface", cfg.Interface)
		cfg.Routes[0] = false
	}
	if cfg.Routes[1] {
		mustRun("route", "-n", "delete", "-net", "128.0.0.0/1", "-interface", cfg.Interface)
		cfg.Routes[1] = false
	}
	for index, prefix := range [2]string{"::/1", "8000::/1"} {
		if cfg.RoutesV6[index] {
			mustRun("route", "-n", "delete", "-net", prefix, "-interface", cfg.Interface)
			cfg.RoutesV6[index] = false
		}
	}
	for _, keep := range cfg.AddedKeep {
		out, err := command("route", "-n", "get", "-host", keep.String())
		if err != nil {
			continue
		}
		for _, line := range strings.Split(out, "\n") {
			fields := strings.Fields(line)
			if len(fields) == 2 && fields[0] == "gateway:" && fields[1] == cfg.darwinGateway {
				mustRun("route", "-n", "delete", "-host", keep.String(), cfg.darwinGateway)
				break
			}
		}
	}
	cfg.AddedKeep = nil
	cfg.darwinGateway = ""
}

func serviceDNS(output string) []string {
	var addresses []string
	for _, line := range strings.Split(output, "\n") {
		value := strings.TrimSpace(line)
		if net.ParseIP(strings.Split(value, "%")[0]) != nil {
			addresses = append(addresses, value)
		}
	}
	return addresses
}

func services() ([]string, error) {
	out, err := command("networksetup", "-listallnetworkservices")
	if err != nil {
		return nil, err
	}
	var list []string
	for _, line := range strings.Split(out, "\n") {
		line = strings.TrimSpace(line)
		if line == "" || strings.HasPrefix(line, "*") || strings.Contains(line, "denotes") ||
			strings.Contains(strings.ToLower(line), "asterisk") {
			continue
		}
		list = append(list, line)
	}
	return list, nil
}

func privileged() bool { return isRoot() }
