//go:build windows

package tundev

import (
	"net/netip"
	"testing"
	"unsafe"
)

// These tests run on the Windows job in CI. They exist because a wrong byte
// layout in SOCKADDR_INET is silent on other platforms and only shows up as
// ERROR_INVALID_PARAMETER from IP Helper at connect time, which is how the
// first version of this file failed.

func rawBytesOf(addr netip.Addr) []byte {
	raw := sockaddrOf(addr)
	return unsafe.Slice((*byte)(unsafe.Pointer(&raw)), int(unsafe.Sizeof(raw)))
}

func TestSockaddrInet4MatchesTheCStruct(t *testing.T) {
	bytes := rawBytesOf(netip.MustParseAddr("10.111.0.2"))
	if len(bytes) != 28 {
		t.Fatalf("SOCKADDR_INET is %d bytes, want 28", len(bytes))
	}
	if bytes[0] != 2 { // AF_INET on Windows
		t.Fatalf("sin_family is %d, want AF_INET", bytes[0])
	}
	// sin_port must stay zero: an address written into the port field is how
	// the first version of this file failed, with ERROR_INVALID_PARAMETER.
	if bytes[2] != 0 || bytes[3] != 0 {
		t.Fatalf("sin_port is % x, want zero", bytes[2:4])
	}
	// sin_addr at byte 4, in network order.
	if bytes[4] != 10 || bytes[5] != 111 || bytes[6] != 0 || bytes[7] != 2 {
		t.Fatalf("sin_addr is not at byte 4: % x", bytes[:16])
	}
	for index := 8; index < 16; index++ {
		if bytes[index] != 0 {
			t.Fatalf("sin_zero must stay zero, byte %d is %d", index, bytes[index])
		}
	}
}

func TestSockaddrInet6MatchesTheCStruct(t *testing.T) {
	bytes := rawBytesOf(netip.MustParseAddr("2001:db8::1"))
	if bytes[0] != 23 { // AF_INET6 on Windows
		t.Fatalf("si_family is %d, want AF_INET6", bytes[0])
	}
	if bytes[8] != 0x20 || bytes[9] != 0x01 || bytes[10] != 0x0d || bytes[11] != 0xb8 {
		t.Fatalf("sin6_addr is not at byte 8: % x", bytes[:24])
	}
	if bytes[23] != 1 {
		t.Fatalf("the address tail is wrong: % x", bytes[8:24])
	}
}

func TestSockaddrRoundTripsThroughTheUnion(t *testing.T) {
	for _, text := range []string{"10.111.0.2", "8.8.8.8", "2001:4860:4860::8888", "fd00:d11::2"} {
		want := netip.MustParseAddr(text)
		if got := addrOf(sockaddrOf(want)); got != want {
			t.Fatalf("%s came back as %s", want, got)
		}
	}
	// A default route's next hop is the value gateway() reads out of the
	// forwarding table; reading it from the wrong offset yields 0.0.0.0.
	if got := addrOf(sockaddrOf(netip.MustParseAddr("192.168.1.1"))); got.IsUnspecified() || got.String() != "192.168.1.1" {
		t.Fatalf("the v4 next hop did not survive the union: %v", got)
	}
}
