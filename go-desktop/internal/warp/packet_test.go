package warp

import (
	"bytes"
	"encoding/binary"
	"io"
	"net/netip"
	"testing"
)

func TestBareIPCapsuleAndUDPChecksum(t *testing.T) {
	source, target := netip.MustParseAddr("172.16.0.2"), netip.MustParseAddr("1.1.1.1")
	for _, payload := range [][]byte{nil, []byte("abc"), bytes.Repeat([]byte{42}, MaxPayload)} {
		packet, err := udpPacket(source, target, 40000, 53, payload, 123)
		if err != nil {
			t.Fatal(err)
		}
		parsed, err := parseUDP(packet)
		if err != nil || parsed.source != source || parsed.target != target || parsed.sourcePort != 40000 || parsed.targetPort != 53 || !bytes.Equal(parsed.payload, payload) {
			t.Fatalf("packet roundtrip failed: %v", err)
		}
		encoded, err := encodePacket(packet)
		if err != nil {
			t.Fatal(err)
		}
		// Raw packet immediately follows capsule header: no context-ID byte.
		decoded, err := readPacket(bytes.NewReader(encoded))
		if err != nil || !bytes.Equal(decoded, packet) {
			t.Fatalf("capsule mismatch: %v", err)
		}
	}
	if _, err := udpPacket(source, target, 1, 53, make([]byte, MaxPayload+1), 0); err == nil {
		t.Fatal("oversized UDP packet accepted")
	}
}

func TestBadChecksumsFragmentsAndTruncationAreRejected(t *testing.T) {
	source, target := netip.MustParseAddr("172.16.0.2"), netip.MustParseAddr("1.1.1.1")
	packet, _ := udpPacket(source, target, 40000, 53, []byte("abc"), 123)
	bad := append([]byte{}, packet...)
	bad[len(bad)-1] ^= 1
	if _, err := parseUDP(bad); err == nil {
		t.Fatal("bad UDP checksum accepted")
	}
	bad = append([]byte{}, packet...)
	bad[8] ^= 1
	if _, err := parseUDP(bad); err == nil {
		t.Fatal("bad IP checksum accepted")
	}
	bad = append([]byte{}, packet...)
	binary.BigEndian.PutUint16(bad[6:8], 0x2000)
	binary.BigEndian.PutUint16(bad[10:12], 0)
	binary.BigEndian.PutUint16(bad[10:12], fold(sum(bad[:20], 0)))
	if _, err := parseUDP(bad); err == nil {
		t.Fatal("fragment accepted")
	}
	for _, raw := range [][]byte{{0}, {0, 0x40}, {0, 0x40, 4, 1}} {
		if _, err := readPacket(bytes.NewReader(raw)); err == nil || err == io.EOF {
			t.Fatal("truncated capsule accepted")
		}
	}
}

func TestUnknownCapsuleIsSkipped(t *testing.T) {
	raw := []byte{3, 2, 1, 2, 0, 1, 0x45}
	value, err := readPacket(bytes.NewReader(raw))
	if err != nil || !bytes.Equal(value, []byte{0x45}) {
		t.Fatalf("unknown capsule: %v", err)
	}
}
