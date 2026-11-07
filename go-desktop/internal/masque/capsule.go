// Package masque contains bounded Capsule Protocol framing for prospective
// CONNECT-UDP support. It does not enable UDP routing or establish a proxy
// stream; a caller must negotiate an authenticated CONNECT-UDP tunnel first.
package masque

import (
	"errors"
	"fmt"
	"io"
)

const (
	MaxUDPPayload   = 65527
	maxCapsuleValue = 1 << 20
	datagramType    = 0
)

var ErrProtocol = errors.New("invalid or excessive capsule")

// EncodeDatagram encodes one UDP payload, including an empty payload. The
// caller is responsible for serializing writes to the negotiated HTTP stream.
func EncodeDatagram(payload []byte) ([]byte, error) {
	if len(payload) > MaxUDPPayload {
		return nil, fmt.Errorf("%w: UDP payload is too large", ErrProtocol)
	}
	out := make([]byte, 0, len(payload)+10)
	out = appendVarint(out, datagramType)
	out = appendVarint(out, uint64(len(payload)+1)) // one-byte context ID zero
	out = appendVarint(out, 0)
	return append(out, payload...), nil
}

// ReadDatagram reads the next context-zero payload. Unknown capsule types and
// contexts are skipped without buffering. io.EOF means a clean end between
// capsules; truncated fields and excessive lengths are errors. One goroutine
// must own reads for a given stream.
func ReadDatagram(r io.Reader) ([]byte, error) {
	for {
		typ, _, err := readVarint(r)
		if err != nil {
			return nil, err
		}
		length, _, err := readVarint(r)
		if err != nil {
			return nil, truncated(err)
		}
		if length > maxCapsuleValue {
			return nil, fmt.Errorf("%w: capsule value exceeds the configured limit", ErrProtocol)
		}
		if typ != datagramType {
			if err := skip(r, length); err != nil {
				return nil, err
			}
			continue
		}
		if length == 0 {
			return nil, fmt.Errorf("%w: missing context ID", ErrProtocol)
		}
		// Read the first byte separately so a context integer cannot consume
		// bytes from the following capsule when its declared length is short.
		var first [1]byte
		if _, err := io.ReadFull(r, first[:]); err != nil {
			return nil, truncated(err)
		}
		width := 1 << (first[0] >> 6)
		if uint64(width) > length {
			return nil, fmt.Errorf("%w: context exceeds capsule length", ErrProtocol)
		}
		context, err := readVarintRest(r, first[0], width)
		if err != nil {
			return nil, err
		}
		remaining := length - uint64(width)
		if context != 0 {
			if err := skip(r, remaining); err != nil {
				return nil, err
			}
			continue
		}
		if remaining > MaxUDPPayload {
			return nil, fmt.Errorf("%w: UDP payload exceeds RFC 9298 limit", ErrProtocol)
		}
		payload := make([]byte, int(remaining))
		if _, err := io.ReadFull(r, payload); err != nil {
			return nil, truncated(err)
		}
		return payload, nil
	}
}

func appendVarint(dst []byte, n uint64) []byte {
	if n >= 1<<62 {
		panic("QUIC integer out of range") // internal callers use bounded values
	}
	width, prefix := 1, byte(0)
	switch {
	case n < 1<<6:
	case n < 1<<14:
		width, prefix = 2, 0x40
	case n < 1<<30:
		width, prefix = 4, 0x80
	default:
		width, prefix = 8, 0xc0
	}
	start := len(dst)
	dst = append(dst, make([]byte, width)...)
	for i := width - 1; i >= 0; i-- {
		dst[start+i] = byte(n)
		n >>= 8
	}
	dst[start] |= prefix
	return dst
}

func readVarint(r io.Reader) (uint64, int, error) {
	var first [1]byte
	if _, err := io.ReadFull(r, first[:]); err != nil {
		return 0, 0, err
	}
	width := 1 << (first[0] >> 6)
	value, err := readVarintRest(r, first[0], width)
	return value, width, err
}

func readVarintRest(r io.Reader, first byte, width int) (uint64, error) {
	value := uint64(first & 0x3f)
	var rest [7]byte
	if _, err := io.ReadFull(r, rest[:width-1]); err != nil {
		return 0, truncated(err)
	}
	for _, b := range rest[:width-1] {
		value = value<<8 | uint64(b)
	}
	return value, nil
}

func skip(r io.Reader, length uint64) error {
	if _, err := io.CopyN(io.Discard, r, int64(length)); err != nil {
		return truncated(err)
	}
	return nil
}

func truncated(err error) error {
	if errors.Is(err, io.EOF) {
		return io.ErrUnexpectedEOF
	}
	return err
}
