package masque

import (
	"bytes"
	"errors"
	"io"
	"testing"
)

type shortReader struct{ r io.Reader }

func (s shortReader) Read(p []byte) (int, error) {
	if len(p) > 1 {
		p = p[:1]
	}
	return s.r.Read(p)
}

func TestDatagramsHaveCapsuleFramingAndPreserveEmptyPayload(t *testing.T) {
	for _, tc := range []struct {
		payload []byte
		wire    []byte
	}{
		{[]byte{}, []byte{0, 1, 0}},
		{[]byte("abc"), []byte{0, 4, 0, 'a', 'b', 'c'}},
	} {
		wire, err := EncodeDatagram(tc.payload)
		if err != nil || !bytes.Equal(wire, tc.wire) {
			t.Fatalf("encoded %x as %x, err=%v", tc.payload, wire, err)
		}
		got, err := ReadDatagram(shortReader{bytes.NewReader(wire)})
		if err != nil || !bytes.Equal(got, tc.payload) {
			t.Fatalf("decoded %x as %x, err=%v", wire, got, err)
		}
	}
	if _, err := ReadDatagram(bytes.NewReader(nil)); !errors.Is(err, io.EOF) {
		t.Fatalf("expected clean EOF, got %v", err)
	}
}

func TestVariableLengthIntegerBoundaries(t *testing.T) {
	cases := []struct {
		value uint64
		wire  []byte
	}{
		{63, []byte{0x3f}},
		{64, []byte{0x40, 0x40}},
		{16383, []byte{0x7f, 0xff}},
		{16384, []byte{0x80, 0, 0x40, 0}},
		{1<<30 - 1, []byte{0xbf, 0xff, 0xff, 0xff}},
		{1 << 30, []byte{0xc0, 0, 0, 0, 0x40, 0, 0, 0}},
		{1<<62 - 1, []byte{0xff, 0xff, 0xff, 0xff, 0xff, 0xff, 0xff, 0xff}},
	}
	for _, tc := range cases {
		got := appendVarint(nil, tc.value)
		if !bytes.Equal(got, tc.wire) {
			t.Fatalf("integer %d encoded as %x, want %x", tc.value, got, tc.wire)
		}
		value, width, err := readVarint(shortReader{bytes.NewReader(got)})
		if err != nil || value != tc.value || width != len(tc.wire) {
			t.Fatalf("integer %x decoded as %d width %d, err=%v", got, value, width, err)
		}
	}
}

func TestUnknownCapsulesAndContextsAreSkipped(t *testing.T) {
	unknownCapsule := []byte{5, 3, 0xaa, 0xbb, 0xcc}
	unknownContext := []byte{0, 2, 17, 0x99}
	// Nonminimal two-byte encodings for type, length, and context zero.
	padded := []byte{0x40, 0, 0x40, 4, 0x40, 0, 'A', 'B'}
	wire := append(append(unknownCapsule, unknownContext...), padded...)
	r := shortReader{bytes.NewReader(wire)}
	got, err := ReadDatagram(r)
	if err != nil || !bytes.Equal(got, []byte("AB")) {
		t.Fatalf("got %x, err=%v", got, err)
	}
	if _, err := ReadDatagram(r); !errors.Is(err, io.EOF) {
		t.Fatalf("expected clean EOF, got %v", err)
	}
}

func TestTruncationAndExcessiveLengthsFailClosed(t *testing.T) {
	for _, wire := range [][]byte{{0}, {0, 0}, {0, 1}, {0, 1, 0x40}, {0, 3, 0, 1}, {0x40}, {0, 0x40}} {
		if _, err := ReadDatagram(shortReader{bytes.NewReader(wire)}); err == nil {
			t.Fatalf("truncated capsule %x accepted", wire)
		}
	}
	bigUnknown := appendVarint([]byte{5}, maxCapsuleValue+1)
	if _, err := ReadDatagram(bytes.NewReader(bigUnknown)); !errors.Is(err, ErrProtocol) {
		t.Fatalf("large unknown capsule: %v", err)
	}
	bigUDP := append(appendVarint([]byte{0}, MaxUDPPayload+2), 0)
	if _, err := ReadDatagram(bytes.NewReader(bigUDP)); !errors.Is(err, ErrProtocol) {
		t.Fatalf("large UDP capsule: %v", err)
	}
	if _, err := EncodeDatagram(make([]byte, MaxUDPPayload+1)); !errors.Is(err, ErrProtocol) {
		t.Fatalf("large outgoing payload: %v", err)
	}
}

func TestMaximumUDPPayloadRoundTripsAcrossShortReads(t *testing.T) {
	payload := make([]byte, MaxUDPPayload)
	for i := range payload {
		payload[i] = byte(i % 251)
	}
	wire, err := EncodeDatagram(payload)
	if err != nil {
		t.Fatal(err)
	}
	got, err := ReadDatagram(shortReader{bytes.NewReader(wire)})
	if err != nil || !bytes.Equal(got, payload) {
		t.Fatalf("maximum payload mismatch: len=%d err=%v", len(got), err)
	}
}
