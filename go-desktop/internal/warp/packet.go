// Package warp implements an opt-in Cloudflare WARP CONNECT-IP transport.
// WARP runs inside the Mozilla tunnel; application UDP never falls back to a
// direct socket when WARP or the authenticated upstream is unavailable.
package warp

import (
	"encoding/binary"
	"errors"
	"net/netip"
)

const (
	// WARP's default inner link MTU. No IPv4 fragments are accepted or emitted.
	MTU        = 1280
	MaxPayload = MTU - 20 - 8
)

var errPacket = errors.New("invalid or unsupported WARP IP packet")

func sum(data []byte, total uint32) uint32 {
	for len(data) >= 2 {
		total += uint32(binary.BigEndian.Uint16(data[:2]))
		data = data[2:]
	}
	if len(data) == 1 {
		total += uint32(data[0]) << 8
	}
	return total
}

func fold(total uint32) uint16 {
	for total>>16 != 0 {
		total = (total & 0xffff) + (total >> 16)
	}
	return ^uint16(total)
}

func udpPacket(source, target netip.Addr, sourcePort, targetPort uint16, payload []byte, id uint16) ([]byte, error) {
	if !source.Is4() || !target.Is4() || sourcePort == 0 || targetPort == 0 || len(payload) > MaxPayload {
		return nil, errPacket
	}
	packet := make([]byte, 28+len(payload))
	packet[0] = 0x45
	binary.BigEndian.PutUint16(packet[2:4], uint16(len(packet)))
	binary.BigEndian.PutUint16(packet[4:6], id)
	binary.BigEndian.PutUint16(packet[6:8], 0x4000) // don't fragment
	packet[8], packet[9] = 64, 17
	s, d := source.As4(), target.As4()
	copy(packet[12:16], s[:])
	copy(packet[16:20], d[:])
	binary.BigEndian.PutUint16(packet[10:12], fold(sum(packet[:20], 0)))
	binary.BigEndian.PutUint16(packet[20:22], sourcePort)
	binary.BigEndian.PutUint16(packet[22:24], targetPort)
	binary.BigEndian.PutUint16(packet[24:26], uint16(8+len(payload)))
	copy(packet[28:], payload)
	pseudo := sum(packet[12:20], uint32(17+8+len(payload)))
	checksum := fold(sum(packet[20:], pseudo))
	if checksum == 0 {
		checksum = 0xffff
	}
	binary.BigEndian.PutUint16(packet[26:28], checksum)
	return packet, nil
}

type parsedUDP struct {
	source, target         netip.Addr
	sourcePort, targetPort uint16
	payload                []byte
}

func parseUDP(packet []byte) (parsedUDP, error) {
	var result parsedUDP
	if len(packet) < 28 || packet[0]>>4 != 4 || packet[9] != 17 {
		return result, errPacket
	}
	hlen := int(packet[0]&15) * 4
	if hlen < 20 || hlen+8 > len(packet) || int(binary.BigEndian.Uint16(packet[2:4])) != len(packet) ||
		binary.BigEndian.Uint16(packet[6:8])&0xbfff != 0 || fold(sum(packet[:hlen], 0)) != 0 {
		return result, errPacket
	}
	udp := packet[hlen:]
	length := int(binary.BigEndian.Uint16(udp[4:6]))
	if length != len(udp) || len(packet) > MTU || length < 8 {
		return result, errPacket
	}
	if binary.BigEndian.Uint16(udp[6:8]) != 0 {
		pseudo := sum(packet[12:20], uint32(17+length))
		if fold(sum(udp, pseudo)) != 0 {
			return result, errPacket
		}
	}
	var src, dst [4]byte
	copy(src[:], packet[12:16])
	copy(dst[:], packet[16:20])
	result.source, result.target = netip.AddrFrom4(src), netip.AddrFrom4(dst)
	result.sourcePort, result.targetPort = binary.BigEndian.Uint16(udp[:2]), binary.BigEndian.Uint16(udp[2:4])
	result.payload = append([]byte(nil), udp[8:]...)
	return result, nil
}
