//go:build windows

package tundev

import (
	"net/netip"
	"syscall"
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
	raw := sockaddrOf(netip.MustParseAddr("10.111.0.2"))
	if raw.Family != syscall.AF_INET {
		t.Fatalf("family is %d, want AF_INET", raw.Family)
	}
	if raw.Port != 0x0a6f { // 10.111 in network order, read as a uint16
		t.Fatalf("sin_addr high half is %#x, want 0x0a6f", raw.Port)
	}
	if raw.Flowinfo != 0x0002_0000 { // 0.2 then the sin_zero padding
		t.Fatalf("sin_addr low half is %#x", raw.Flowinfo)
	}
	bytes := rawBytesOf(netip.MustParseAddr("10.111.0.2"))
	// sin_family at 0..1, sin_port at 2..3, sin_addr at 4..7 — this is the
	// assertion that would have caught the original mistake.
	if bytes[4] != 10 || bytes[5] != 111 || bytes[6] != 0 || bytes[7] != 2 {
		t.Fatalf("sin_addr is not at byte 4: % x", bytes[:16])
	}
	for index := 8; index < 16; index++ {
		if bytes[index] != 0 {
			t.Fatalf("sin_zero must stay zero, byte %d is %d", index, bytes[index])
		}
	}
	if len(bytes) != 28 {
		t.Fatalf("SOCKADDR_INET is %d bytes, want 28", len(bytes))
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
