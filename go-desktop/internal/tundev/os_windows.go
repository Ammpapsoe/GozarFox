package tundev

import (
	"errors"
	"fmt"
	"net"
	"net/netip"
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
	nextHop := addrOf(best.NextHop)
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
		// GetUnicastIpAddressEntry looks the row up by interface *and* address,
		// so the address has to be filled in before the call. Leaving it zero
		// asks about 0.0.0.0, which never exists, and the probe then waited the
		// full twelve seconds and failed even though the interface was ready.
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
		return fmt.Errorf("assigning %s to %s failed: %w", addressV4(), cfg.Interface, err)
	}
	if err := waitWindowsReady(cfg.Interface); err != nil {
		return fmt.Errorf("waiting for %s to come up failed: %w", cfg.Interface, err)
	}
	// The metric is set before the routes, otherwise Windows may rank the
	// physical adapter's default above the tunnel for the moment between the
	// two calls and a burst of traffic escapes unencrypted.
	// The metric is a preference, not a requirement: the two /1 routes added
	// below are more specific than any /0 default, so they win on their own.
	// This call is also the most environment-sensitive of the set (some
	// adapters and third-party network filters refuse it), and blocking a
	// working tunnel over a preference is the wrong trade.
	_ = setInterfaceMetric(index, false, 1)
	// The resolver is the proxy's own DNS listener. Setting it on the interface
	// is what makes applications that read the OS resolver (rather than the
	// VPN's) use the tunnel instead of the carrier's DNS.
	//
	// A failure here is not fatal. If the API is missing on this build, or the
	// adapter refuses the row (some third-party LSPs and metered-connection
	// policies do), the interface resolver simply stays unset and the proxy's
	// own DNS listener still answers: a connection that works without the OS
	// resolver is better than a connect that aborts over a name server.
	if cfg.DNS != "" {
		if address, err := netip.ParseAddr(cfg.DNS); err == nil {
			_, _ = setInterfaceDNS(index, address)
		}
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
			return fmt.Errorf("pinning %s outside the tunnel failed: %w", keep, err)
		}
		cfg.AddedKeep = append(cfg.AddedKeep, keep)
	}
	for slot, prefix := range splitV4 {
		if err := addRoute(index, prefix, 0); err != nil {
			return fmt.Errorf("adding the %s route failed: %w", prefix, err)
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
	// The keep routes live on the physical interface. They are matched by the
	// route protocol this package sets when it creates them (MIB_IPPROTO_NETMGMT,
	// "static" to Windows), not by their next hop: `route delete <host> mask ...
	// <gateway>` used to miss whenever DHCP renewed the lease between connect and
	// disconnect, and CreateIpForwardEntry2 needs a row that includes the protocol
	// to find the entry again.
	if gateIndex, err := atoi(cfg.windowsIndex); err == nil {
		nextHop := netip.Addr{}
		if cfg.windowsGateway != "" {
			nextHop = netip.MustParseAddr(cfg.windowsGateway)
		}
		for _, keep := range cfg.AddedKeep {
			_ = removeRoute(uint32(gateIndex), netip.PrefixFrom(keep, keep.BitLen()), nextHop)
			// DeleteIpForwardEntry2 matches on the whole row, and an entry that
			// does not match exactly is reported as not found and left in place.
			// The next hop above is the one the route was created with, but a
			// delete by prefix alone is tried too: whoever added the route last
			// (a previous run, an operator, the app before a restart) may have
			// used a different gateway, and a stale pin means the proxy stays
			// unreachable after the tunnel is gone.
			_ = removeRoute(uint32(gateIndex), netip.PrefixFrom(keep, keep.BitLen()), netip.Addr{})
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
