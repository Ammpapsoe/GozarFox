//go:build windows

package tundev

// Direct calls into the Windows IP Helper API.
//
// The tunnel used to configure itself by spawning netsh and route. That was
// fragile in four ways, and each one showed up as "it says connected but
// nothing loads":
//
//   - netsh is a separate process per call; a slow or policy-blocked binary
//     (Defender scanning, an application-control policy) costs seconds per
//     setting and can leave the interface half configured.
//   - its output is localised, so the parsed interface index came back empty on
//     a non-English Windows.
//   - `route delete` needs the exact gateway that added the route, so when DHCP
//     renewed the lease mid-session the delete missed and split routes leaked
//     past the end of the session.
//   - the IPv6 blackhole routes were added and removed by prefix text, so a
//     prefix that failed to add could still be "deleted".
//
// IP Helper answers all four: CreateIpForwardEntry2/DeleteIpForwardEntry2 and
// CreateUnicastIpAddressEntry/DeleteUnicastIpAddressEntry are in-process
// syscalls that return a status code and take structured rows, so there is no
// process to spawn and nothing to parse. golang.org/x/sys/windows already
// declares the row types but not the create/delete entry points, and a newer
// x/sys would move the module past the Go version the release toolchain pins
// (it requires 1.26). The declarations below therefore mirror the documented C
// layouts from netioapi.h; the size assertions at the bottom of the file turn a
// mistaken layout into a build failure instead of a corrupted route table.
//
// Everything is guarded by a build tag, so the package still compiles for the
// other platforms.

import (
	"errors"
	"net/netip"
	"syscall"
	"unsafe"

	"golang.org/x/sys/windows"
)

var (
	modiphlpapi                     = windows.NewLazySystemDLL("iphlpapi.dll")
	procCreateIpForwardEntry2       = modiphlpapi.NewProc("CreateIpForwardEntry2")
	procDeleteIpForwardEntry2       = modiphlpapi.NewProc("DeleteIpForwardEntry2")
	procInitializeIpForwardEntry    = modiphlpapi.NewProc("InitializeIpForwardEntry")
	procCreateUnicastIpAddressEntry = modiphlpapi.NewProc("CreateUnicastIpAddressEntry")
	procDeleteUnicastIpAddressEntry = modiphlpapi.NewProc("DeleteUnicastIpAddressEntry")
	procInitializeUnicastIpAddr     = modiphlpapi.NewProc("InitializeUnicastIpAddressEntry")
	procSetIpInterfaceEntry         = modiphlpapi.NewProc("SetIpInterfaceEntry")
	procGetIpInterfaceEntry         = modiphlpapi.NewProc("GetIpInterfaceEntry")
	procGetIpForwardTable2          = modiphlpapi.NewProc("GetIpForwardTable2")
	procSetInterfaceDnsSettings     = modiphlpapi.NewProc("SetInterfaceDnsSettings")
	procFreeMibTable                = modiphlpapi.NewProc("FreeMibTable")
)

// NL_ROUTE_PROTOCOL: a manually configured route. Windows shows these as
// "static" and never removes them on its own, which is what the two split
// defaults need.
const ipProtoNetMgmt = 3

// MIB_IPFORWARD_ROW2.
type mibIpForwardRow2 struct {
	InterfaceLuid        uint64
	InterfaceIndex       uint32
	DestinationPrefix    ipAddressPrefix
	NextHop              rawSockaddrInet
	SitePrefixLength     uint8
	ValidLifetime        uint32
	PreferredLifetime    uint32
	Metric               uint32
	Protocol             uint32
	Loopback             uint8
	AutoconfigureAddress uint8
	Publish              uint8
	Immortal             uint8
	Age                  uint32
	Origin               uint32
}

// IP_ADDRESS_PREFIX.
type ipAddressPrefix struct {
	Prefix       rawSockaddrInet
	PrefixLength uint8
}

// SOCKADDR_INET: the union of SOCKADDR_IN and SOCKADDR_IN6. SOCKADDR_IN6 is the
// larger of the two at 28 bytes, so the union is 28 bytes and the field layout
// below matches SOCKADDR_IN6.
//
// The two views do not agree on where the address lives, which is the trap:
//
//	SOCKADDR_IN  { family, port, addr[4], zero[8] }        -> addr at byte 4
//	SOCKADDR_IN6 { family, port, flowinfo, addr[16], scope } -> addr at byte 8
//
// So an IPv4 address belongs in Port+Flowinfo (bytes 4..7) and an IPv6 address
// in Addr (bytes 8..23). Writing an IPv4 address into Addr instead leaves
// sin_addr zero and puts the bytes in sin_zero, which IP Helper rejects with
// ERROR_INVALID_PARAMETER ("The parameter is incorrect") rather than anything
// that names the field.
type rawSockaddrInet struct {
	Family   uint16
	Port     uint16
	Flowinfo uint32
	Addr     [16]byte
	Scope    uint32
}

// MIB_UNICASTIPADDRESS_ROW.
type mibUnicastIpAddressRow struct {
	Address            rawSockaddrInet
	InterfaceLuid      uint64
	InterfaceIndex     uint32
	PrefixOrigin       uint32
	SuffixOrigin       uint32
	ValidLifetime      uint32
	PreferredLifetime  uint32
	OnLinkPrefixLength uint8
	SkipAsSource       uint8
	DadState           uint32
	ScopeId            uint32
	CreationTimeStamp  int64
}

// MIB_IPINTERFACE_ROW. Only the fields the tunnel touches are named; the rest
// exist to keep the layout aligned with the C struct.
type mibIpInterfaceRow struct {
	Family                               uint16
	InterfaceLuid                        uint64
	InterfaceIndex                       uint32
	MaxReassemblySize                    uint32
	InterfaceIdentifier                  uint64
	MinRouterAdvertisementInterval       uint32
	MaxRouterAdvertisementInterval       uint32
	AdvertisingEnabled                   uint8
	ForwardingEnabled                    uint8
	WeakHostSend                         uint8
	WeakHostReceive                      uint8
	UseAutomaticMetric                   uint8
	UseNeighborUnreachabilityDetection   uint8
	ManagedAddressConfigurationSupported uint8
	OtherStatefulConfigurationSupported  uint8
	AdvertiseDefaultRoute                uint8
	RouterDiscoveryBehavior              uint32
	DadTransmits                         uint32
	BaseReachableTime                    uint32
	RetransmitTime                       uint32
	PathMtuDiscoveryTimeout              uint32
	LinkLocalAddressBehavior             uint32
	LinkLocalAddressTimeout              uint32
	ZoneIndices                          [16]uint32
	SitePrefixLength                     uint32
	Metric                               uint32
	NlMtu                                uint32
	Connected                            uint8
	SupportsWakeUpPatterns               uint8
	SupportsNeighborDiscovery            uint8
	SupportsRouterDiscovery              uint8
	ReachableTime                        uint32
}

// The published sizes of the three structures on every Windows targeted by the
// desktop build (amd64 and arm64 both use this packing).
var (
	_ [unsafe.Sizeof(mibIpForwardRow2{})]byte       = [104]byte{}
	_ [unsafe.Sizeof(ipAddressPrefix{})]byte        = [32]byte{}
	_ [unsafe.Sizeof(mibUnicastIpAddressRow{})]byte = [80]byte{}
	_ [unsafe.Sizeof(mibIpInterfaceRow{})]byte      = [168]byte{}
)

// sockaddrOf builds a SOCKADDR_INET for addr.
//
// The bytes are written through a byte view of the struct instead of through
// the Port/Flowinfo/Addr fields. That is deliberate: the v4 view keeps sin_addr
// at byte 4, which lands inside the uint32 the v6 view calls sin6_flowinfo, so
// naming fields here means remembering which of two layouts wins. Writing the
// address at the documented offset cannot drift, and the tests in
// iphelper_windows_test.go assert exactly those offsets.
func sockaddrOf(addr netip.Addr) rawSockaddrInet {
	var s rawSockaddrInet
	raw := unsafe.Slice((*byte)(unsafe.Pointer(&s)), int(unsafe.Sizeof(s)))
	if addr.Is4() {
		s.Family = syscall.AF_INET
		v4 := addr.As4()
		// SOCKADDR_IN: family 0..1, port 2..3, sin_addr 4..7, sin_zero 8..15.
		copy(raw[4:8], v4[:])
		return s
	}
	s.Family = syscall.AF_INET6
	// SOCKADDR_IN6: family 0..1, port 2..3, flowinfo 4..7, sin6_addr 8..23.
	v6 := addr.As16()
	copy(raw[8:24], v6[:])
	return s
}

// addInterfaceAddress assigns a static address to the tunnel interface.
// A /32 is used deliberately: the tunnel needs one source address, not a
// subnet, and a wider mask would let Windows install a connected route that
// competes with the two split-default routes below.
func addInterfaceAddress(index uint32, addr netip.Addr) error {
	var row mibUnicastIpAddressRow
	if err := procInitializeUnicastIpAddr.Find(); err == nil {
		procInitializeUnicastIpAddr.Call(uintptr(unsafe.Pointer(&row)))
	}
	row.InterfaceIndex = index
	row.Address = sockaddrOf(addr)
	row.OnLinkPrefixLength = 32
	row.PrefixOrigin = 1 // IpPrefixOriginManual
	row.SuffixOrigin = 1 // IpSuffixOriginManual
	row.ValidLifetime = 0xffffffff
	row.PreferredLifetime = 0xffffffff
	// SkipAsSource stays 0: traffic bound to the wildcard must be allowed to
	// use this address as its source.
	if err := createAddress(&row); err != nil && !errors.Is(err, windows.ERROR_OBJECT_ALREADY_EXISTS) {
		return err
	}
	return nil
}

func deleteInterfaceAddress(index uint32, addr netip.Addr) error {
	row := mibUnicastIpAddressRow{InterfaceIndex: index, OnLinkPrefixLength: 32}
	row.Address = sockaddrOf(addr)
	return deleteAddress(&row)
}

// addRoute installs one static route whose next hop is the tunnel's own link.
// This is the structured equivalent of `netsh interface ipv4 add route <prefix>
// <iface>` and of `route add <host> mask 255.255.255.255 <gateway> if <index>`.
func addRoute(index uint32, destination netip.Prefix, metric uint32) error {
	row := forwardRow(index, destination, metric)
	if err := createRoute(&row); err != nil && !errors.Is(err, windows.ERROR_OBJECT_ALREADY_EXISTS) {
		return err
	}
	return nil
}

// addHostRoute pins one address to a next hop on another interface, which is
// how the proxy host and the sign-in endpoints stay reachable while the split
// defaults are in place.
func addHostRoute(index uint32, host netip.Addr, nextHop netip.Addr, metric uint32) error {
	row := forwardRow(index, netip.PrefixFrom(host, host.BitLen()), metric)
	row.NextHop = sockaddrOf(nextHop)
	if err := createRoute(&row); err != nil && !errors.Is(err, windows.ERROR_OBJECT_ALREADY_EXISTS) {
		return err
	}
	return nil
}

func removeRoute(index uint32, destination netip.Prefix, nextHop netip.Addr) error {
	row := forwardRow(index, destination, 0)
	if nextHop.IsValid() {
		row.NextHop = sockaddrOf(nextHop)
	}
	return deleteRoute(&row)
}

func forwardRow(index uint32, destination netip.Prefix, metric uint32) mibIpForwardRow2 {
	var row mibIpForwardRow2
	if err := procInitializeIpForwardEntry.Find(); err == nil {
		procInitializeIpForwardEntry.Call(uintptr(unsafe.Pointer(&row)))
	}
	row.InterfaceIndex = index
	row.DestinationPrefix.Prefix = sockaddrOf(destination.Masked().Addr())
	row.DestinationPrefix.PrefixLength = uint8(destination.Bits())
	// On-link: the next hop is the unspecified address of the same family.
	if destination.Addr().Is4() {
		row.NextHop = sockaddrOf(netip.IPv4Unspecified())
	} else {
		row.NextHop = sockaddrOf(netip.IPv6Unspecified())
	}
	row.Metric = metric
	row.Protocol = ipProtoNetMgmt
	row.ValidLifetime = 0xffffffff
	row.PreferredLifetime = 0xffffffff
	return row
}

func createRoute(row *mibIpForwardRow2) error {
	if code, _, _ := procCreateIpForwardEntry2.Call(uintptr(unsafe.Pointer(row))); code != 0 {
		return windows.Errno(code)
	}
	return nil
}

func deleteRoute(row *mibIpForwardRow2) error {
	if code, _, _ := procDeleteIpForwardEntry2.Call(uintptr(unsafe.Pointer(row))); code != 0 {
		return windows.Errno(code)
	}
	return nil
}

func createAddress(row *mibUnicastIpAddressRow) error {
	if code, _, _ := procCreateUnicastIpAddressEntry.Call(uintptr(unsafe.Pointer(row))); code != 0 {
		return windows.Errno(code)
	}
	return nil
}

func deleteAddress(row *mibUnicastIpAddressRow) error {
	if code, _, _ := procDeleteUnicastIpAddressEntry.Call(uintptr(unsafe.Pointer(row))); code != 0 {
		return windows.Errno(code)
	}
	return nil
}

// setInterfaceMetric lowers the tunnel's interface metric so its routes win
// against the physical adapter. With UseAutomaticMetric cleared Windows honours
// this value directly instead of deriving one from the link speed.
func setInterfaceMetric(index uint32, ipv6 bool, metric uint32) error {
	row := mibIpInterfaceRow{}
	row.Family = uint16(syscall.AF_INET)
	if ipv6 {
		row.Family = uint16(syscall.AF_INET6)
	}
	row.InterfaceIndex = index
	if code, _, _ := procGetIpInterfaceEntry.Call(uintptr(unsafe.Pointer(&row))); code != 0 {
		return windows.Errno(code)
	}
	row.InterfaceIndex = index
	row.UseAutomaticMetric = 0
	row.Metric = metric
	if code, _, _ := procSetIpInterfaceEntry.Call(uintptr(unsafe.Pointer(&row))); code != 0 {
		return windows.Errno(code)
	}
	return nil
}

// MIB_IPFORWARD_TABLE2 is a variable-length table: a count followed by that many
// MIB_IPFORWARD_ROW2. GetIpForwardTable2 allocates it, so it is treated as a
// header plus a pointer into the allocation rather than copied.
type mibIpForwardTable2 struct {
	NumEntries uint32
	Table      [1]mibIpForwardRow2
}

// forwardTable2 asks IP Helper for the v4 forwarding table and reports its rows.
// The returned table must be freed with freeIpForwardTable.
func forwardTable2() (*mibIpForwardTable2, error) {
	var table *mibIpForwardTable2
	if code, _, _ := procGetIpForwardTable2.Call(
		uintptr(windows.AF_INET),
		uintptr(unsafe.Pointer(&table)),
		0,
	); code != 0 {
		return nil, windows.Errno(code)
	}
	return table, nil
}

// forwardRows projects the table into its rows. The row size is asserted at
// compile time, so as long as that assertion holds the stride is exact.
func forwardRows(table *mibIpForwardTable2) []mibIpForwardRow2 {
	if table == nil || table.NumEntries == 0 {
		return nil
	}
	return unsafe.Slice(&table.Table[0], table.NumEntries)
}

func freeIpForwardTable(table *mibIpForwardTable2) {
	if table != nil {
		procFreeMibTable.Call(uintptr(unsafe.Pointer(table)))
	}
}

// DNS_INTERFACE_SETTINGS, as documented for SetInterfaceDnsSettings. Only the
// name-server field is used; the rest must be present because Windows reads the
// whole struct, and Version tells it which fields are meaningful.
type dnsInterfaceSettings struct {
	Version             uint32
	Flags               uint64
	Domain              *uint16
	NameServer          *uint16
	SearchList          *uint16
	RegistrationEnabled uint32
	RegisterAdapterName uint32
	EnableLLMNR         uint32
	QueryAdapterName    uint32
	ProfileNameServer   *uint16
}

const (
	dnsSettingVersion    = 1
	dnsSettingNameServer = 1 // DNS_SETTING_NAMESERVER
)

// setInterfaceDNS points the tunnel interface at one resolver. It is the
// structured replacement for `netsh interface ipv4 set dnsservers`. The
// interface is identified by LUID, which is the key the API expects; the index
// used by the route calls is not accepted here.
func setInterfaceDNS(index uint32, server netip.Addr) error {
	luid, err := interfaceLUID(index)
	if err != nil {
		return err
	}
	text, err := windows.UTF16PtrFromString(server.String())
	if err != nil {
		return err
	}
	settings := dnsInterfaceSettings{
		Version:    dnsSettingVersion,
		Flags:      dnsSettingNameServer,
		NameServer: text,
	}
	if code, _, _ := procSetInterfaceDnsSettings.Call(
		uintptr(unsafe.Pointer(&luid)),
		uintptr(unsafe.Pointer(&settings)),
	); code != 0 {
		return windows.Errno(code)
	}
	return nil
}

// interfaceLUID converts an interface index to the LUID the DNS API needs.
func interfaceLUID(index uint32) (uint64, error) {
	row := windows.MibIfRow2{InterfaceIndex: index}
	if err := windows.GetIfEntry2Ex(windows.MibIfEntryNormalWithoutStatistics, &row); err != nil {
		return 0, err
	}
	return row.InterfaceLuid, nil
}
