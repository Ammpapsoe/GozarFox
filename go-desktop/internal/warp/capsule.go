package warp

import (
	"encoding/binary"
	"errors"
	"io"
)

// Cloudflare's H2 dialect uses type 0 capsules with a bare IP packet, not the
// context-prefixed RFC 9298 CONNECT-UDP encoding used by the old relay code.
const maxCapsule = 65535

func encodePacket(packet []byte) ([]byte, error) {
	if len(packet) == 0 || len(packet) > MTU {
		return nil, errPacket
	}
	// The first varint (type 0) is one byte. Packet lengths here fit in 14 bits.
	out := make([]byte, 0, len(packet)+3)
	out = append(out, 0)
	if len(packet) < 64 {
		out = append(out, byte(len(packet)))
	} else {
		out = append(out, 0, 0)
		binary.BigEndian.PutUint16(out[len(out)-2:], uint16(len(packet))|0x4000)
	}
	return append(out, packet...), nil
}

func readVarint(r io.Reader) (uint64, error) {
	var first [1]byte
	if _, err := io.ReadFull(r, first[:]); err != nil {
		return 0, err
	}
	n := 1 << (first[0] >> 6)
	value := uint64(first[0] & 0x3f)
	var rest [7]byte
	if _, err := io.ReadFull(r, rest[:n-1]); err != nil {
		return 0, io.ErrUnexpectedEOF
	}
	for _, b := range rest[:n-1] {
		value = value<<8 | uint64(b)
	}
	return value, nil
}

func readPacket(r io.Reader) ([]byte, error) {
	for {
		typ, err := readVarint(r)
		if err != nil {
			return nil, err
		}
		length, err := readVarint(r)
		if err != nil {
			return nil, io.ErrUnexpectedEOF
		}
		if length > maxCapsule {
			return nil, errors.New("WARP capsule exceeds limit")
		}
		if typ != 0 {
			if _, err := io.CopyN(io.Discard, r, int64(length)); err != nil {
				return nil, io.ErrUnexpectedEOF
			}
			continue
		}
		if length == 0 || length > MTU {
			return nil, errPacket
		}
		packet := make([]byte, int(length))
		if _, err := io.ReadFull(r, packet); err != nil {
			return nil, io.ErrUnexpectedEOF
		}
		return packet, nil
	}
}
