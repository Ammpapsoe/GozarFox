package tundev

import (
	"errors"
	"net"
	"time"
	"unsafe"

	"golang.org/x/sys/windows"
)

func isWindowsAdmin() bool {
	var sid *windows.SID
	err := windows.AllocateAndInitializeSid(&windows.SECURITY_NT_AUTHORITY, 2,
		windows.SECURITY_BUILTIN_DOMAIN_RID, windows.DOMAIN_ALIAS_RID_ADMINS,
		0, 0, 0, 0, 0, 0, &sid)
	if err != nil {
		return false
	}
	defer windows.FreeSid(sid)
	token := windows.Token(0)
	member, err := token.IsMember(sid)
	return err == nil && member
}

func gateway() (string, string) {
	out, err := command("route", "print", "-4", "0.0.0.0")
	if err != nil {
		return "", ""
	}
	interfaces, err := net.Interfaces()
	if err != nil {
		return "", ""
	}
	indexes := make(map[string]int)
	for _, iface := range interfaces {
		addresses, err := iface.Addrs()
		if err != nil {
			continue
		}
		for _, address := range addresses {
			candidate, _, err := net.ParseCIDR(address.String())
			if err == nil && candidate.To4() != nil {
				indexes[candidate.String()] = iface.Index
			}
		}
	}
	return windowsDefaultRoute(out, indexes)
}

func waitWindowsReady(name string) error {
	deadline := time.Now().Add(12 * time.Second)
	for time.Now().Before(deadline) {
		iface, err := net.InterfaceByName(name)
		if err == nil {
			link := windows.MibIfRow2{InterfaceIndex: uint32(iface.Index)}
			address := windows.MibUnicastIpAddressRow{InterfaceIndex: uint32(iface.Index)}
			ipv4 := (*windows.RawSockaddrInet4)(unsafe.Pointer(&address.Address))
			ipv4.Family = windows.AF_INET
			ipv4.Addr = [4]byte{10, 111, 0, 2}
			linkErr := windows.GetIfEntry2Ex(windows.MibIfEntryNormalWithoutStatistics, &link)
			addrErr := windows.GetUnicastIpAddressEntry(&address)
			if addrErr == nil && address.DadState == windows.IpDadStateDuplicate {
				return errors.New("the tunnel address conflicts with another network interface")
			}
			if linkErr == nil && link.OperStatus == windows.IfOperStatusUp && addrErr == nil && address.DadState == windows.IpDadStatePreferred {
				return nil
			}
		}
		time.Sleep(150 * time.Millisecond)
	}
	return errors.New("the Windows tunnel interface did not become ready")
}

func configure(cfg *Config) error {
	if !isWindowsAdmin() {
		return errors.New("the tunnel needs administrator rights")
	}
	if err := mustRun("netsh", "interface", "ipv4", "set", "address", "name="+cfg.Interface,
		"static", Address.String(), "255.255.255.255"); err != nil {
		return err
	}
	if err := waitWindowsReady(cfg.Interface); err != nil {
		return err
	}
	if err := mustRun("netsh", "interface", "ipv4", "set", "dnsservers", "name="+cfg.Interface,
		"static", cfg.DNS, "primary"); err != nil {
		return err
	}
	gate, index := gateway()
	if gate == "" || index == "" {
		return errors.New("no usable default gateway")
	}
	cfg.windowsGateway, cfg.windowsIndex = gate, index
	for _, keep := range cfg.Keep {
		if err := mustRun("route", "add", keep.String(), "mask", "255.255.255.255", gate, "metric", "1", "if", index); err != nil {
			return err
		}
		cfg.AddedKeep = append(cfg.AddedKeep, keep)
	}
	if err := mustRun("netsh", "interface", "ipv4", "set", "interface", cfg.Interface, "metric=1"); err != nil {
		return err
	}
	if err := mustRun("netsh", "interface", "ipv4", "add", "route", "0.0.0.0/1", cfg.Interface); err != nil {
		return err
	}
	cfg.Routes[0] = true
	if err := mustRun("netsh", "interface", "ipv4", "add", "route", "128.0.0.0/1", cfg.Interface); err != nil {
		return err
	}
	cfg.Routes[1] = true
	for index, prefix := range [2]string{"::/1", "8000::/1"} {
		if err := blackhole6("netsh", "interface", "ipv6", "add", "route", prefix, cfg.Interface); err == nil {
			cfg.RoutesV6[index] = true
		}
	}
	return nil
}

func restore(cfg *Config) {
	if cfg.Routes[0] {
		mustRun("netsh", "interface", "ipv4", "delete", "route", "0.0.0.0/1", cfg.Interface)
		cfg.Routes[0] = false
	}
	if cfg.Routes[1] {
		mustRun("netsh", "interface", "ipv4", "delete", "route", "128.0.0.0/1", cfg.Interface)
		cfg.Routes[1] = false
	}
	for index, prefix := range [2]string{"::/1", "8000::/1"} {
		if cfg.RoutesV6[index] {
			mustRun("netsh", "interface", "ipv6", "delete", "route", prefix, cfg.Interface)
			cfg.RoutesV6[index] = false
		}
	}
	for _, keep := range cfg.AddedKeep {
		mustRun("route", "delete", keep.String(), "mask", "255.255.255.255", cfg.windowsGateway, "if", cfg.windowsIndex)
	}
	cfg.AddedKeep = nil
	cfg.windowsGateway, cfg.windowsIndex = "", ""
}

func privileged() bool { return isWindowsAdmin() }
