package tundev

import (
	"errors"
	"net"
	"net/netip"
	"time"

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

// gateway returns the current default next hop and the interface index it lives
// on. Both come from the IP Helper table instead of `route print`, so the
// values do not depend on the console code page or on parsing localised text.
func gateway() (string, string) {
	interfaces, err := net.Interfaces()
	if err != nil {
		return "", ""
	}
	byIndex := make(map[uint32]bool, len(interfaces))
	for _, iface := range interfaces {
		byIndex[uint32(iface.Index)] = true
	}
	table, err := forwardTable2()
	if err != nil {
		return "", ""
	}
	defer freeIpForwardTable(table)
	var best *mibIpForwardRow2
	for _, row := range forwardRows(table) {
		// The default route is the one whose destination is a /0 prefix.
		if row.DestinationPrefix.PrefixLength != 0 {
			continue
		}
		if row.NextHop.Family != windows.AF_INET {
			continue
		}
		if !byIndex[row.InterfaceIndex] {
			continue
		}
		candidate := row
		if best == nil || candidate.Metric < best.Metric {
			best = &candidate
		}
	}
	if best == nil {
		return "", ""
	}
	nextHop := netip.AddrFrom4([4]byte{
		best.NextHop.Addr[0], best.NextHop.Addr[1], best.NextHop.Addr[2], best.NextHop.Addr[3],
	})
	if !nextHop.IsValid() || nextHop.IsUnspecified() {
		return "", ""
	}
	return nextHop.String(), itoa(int(best.InterfaceIndex))
}

func interfaceIndex(name string) (uint32, error) {
	iface, err := net.InterfaceByName(name)
	if err != nil {
		return 0, err
	}
	return uint32(iface.Index), nil
}

func waitWindowsReady(name string) error {
	index, err := interfaceIndex(name)
	if err != nil {
		return err
	}
	deadline := time.Now().Add(12 * time.Second)
	for time.Now().Before(deadline) {
		link := windows.MibIfRow2{InterfaceIndex: index}
		address := windows.MibUnicastIpAddressRow{InterfaceIndex: index}
		linkErr := windows.GetIfEntry2Ex(windows.MibIfEntryNormalWithoutStatistics, &link)
		addrErr := windows.GetUnicastIpAddressEntry(&address)
		if addrErr == nil && address.DadState == windows.IpDadStateDuplicate {
			return errors.New("the tunnel address conflicts with another network interface")
		}
		if linkErr == nil && link.OperStatus == windows.IfOperStatusUp && addrErr == nil && address.DadState == windows.IpDadStatePreferred {
			return nil
		}
		time.Sleep(150 * time.Millisecond)
	}
	return errors.New("the Windows tunnel interface did not become ready")
}

var (
	splitV4 = [2]netip.Prefix{
		netip.MustParsePrefix("0.0.0.0/1"),
		netip.MustParsePrefix("128.0.0.0/1"),
	}
	splitV6 = [2]netip.Prefix{
		netip.MustParsePrefix("::/1"),
		netip.MustParsePrefix("8000::/1"),
	}
)

func configure(cfg *Config) error {
	if !isWindowsAdmin() {
		return errors.New("the tunnel needs administrator rights")
	}
	index, err := interfaceIndex(cfg.Interface)
	if err != nil {
		return err
	}
	cfg.windowsIndex = itoa(int(index))
	if err := addInterfaceAddress(index, addressV4()); err != nil {
		return err
	}
	if err := waitWindowsReady(cfg.Interface); err != nil {
		return err
	}
	// The metric is set before the routes, otherwise Windows may rank the
	// physical adapter's default above the tunnel for the moment between the
	// two calls and a burst of traffic escapes unencrypted.
	if err := setInterfaceMetric(index, false, 1); err != nil {
		return err
	}
	gate, gateIndex := gateway()
	if gate == "" || gateIndex == "" {
		return errors.New("no usable default gateway")
	}
	cfg.windowsGateway = gate
	nextHop := netip.MustParseAddr(gate)
	gateIndexValue, err := atoi(gateIndex)
	if err != nil {
		return errors.New("no usable default gateway")
	}
	for _, keep := range cfg.Keep {
		if !keep.Is4() {
			continue
		}
		if err := addHostRoute(uint32(gateIndexValue), keep, nextHop, 1); err != nil {
			return err
		}
		cfg.AddedKeep = append(cfg.AddedKeep, keep)
	}
	for slot, prefix := range splitV4 {
		if err := addRoute(index, prefix, 0); err != nil {
			return err
		}
		cfg.Routes[slot] = true
	}
	// IPv6 is blackholed rather than tunnelled: the local network is IPv4 only
	// for the relay, and leaving v6 at its default would leak every AAAA
	// connection around the tunnel. A /1 pair outranks the real default route
	// without deleting it, so restore is exact.
	for slot, prefix := range splitV6 {
		if err := addRoute(index, prefix, 0); err == nil {
			cfg.RoutesV6[slot] = true
		}
	}
	return nil
}

func restore(cfg *Config) {
	index, err := interfaceIndex(cfg.Interface)
	if err == nil {
		for slot, prefix := range splitV4 {
			if cfg.Routes[slot] {
				_ = removeRoute(index, prefix, netip.Addr{})
				cfg.Routes[slot] = false
			}
		}
		for slot, prefix := range splitV6 {
			if cfg.RoutesV6[slot] {
				_ = removeRoute(index, prefix, netip.Addr{})
				cfg.RoutesV6[slot] = false
			}
		}
	}
	// The keep routes live on the physical interface and are matched by their
	// next hop, which is the value they were created with. This is why the
	// gateway is stored instead of re-read: a DHCP renewal between connect and
	// disconnect must not strand them.
	if nextHop := cfg.windowsGateway; nextHop != "" {
		if gateIndex, err := atoi(cfg.windowsIndex); err == nil {
			for _, keep := range cfg.AddedKeep {
				_ = removeRoute(uint32(gateIndex), netip.PrefixFrom(keep, keep.BitLen()), netip.MustParseAddr(nextHop))
			}
		}
	}
	cfg.AddedKeep = nil
	cfg.windowsGateway, cfg.windowsIndex = "", ""
}

func privileged() bool { return isWindowsAdmin() }

func keepOther(cfg *Config, address netip.Addr) error {
	if cfg.windowsGateway == "" || cfg.windowsIndex == "" {
		return errors.New("no usable default gateway")
	}
	for _, existing := range cfg.AddedKeep {
		if existing == address {
			return nil
		}
	}
	index, err := atoi(cfg.windowsIndex)
	if err != nil {
		return errors.New("no usable default gateway")
	}
	if err := addHostRoute(uint32(index), address, netip.MustParseAddr(cfg.windowsGateway), 1); err != nil {
		return err
	}
	cfg.AddedKeep = append(cfg.AddedKeep, address)
	return nil
}

// keep pins one address outside the tunnel. IPv6 addresses are accepted now
// because routing them is no longer done with `route add`.
func keep(cfg *Config, address netip.Addr) error {
	return keepOther(cfg, address)
}
