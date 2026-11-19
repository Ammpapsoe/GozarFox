package engine

import (
	"bytes"
	"crypto/sha256"
	"encoding/binary"
	"reflect"
	"testing"
)

func TestFakeDNSHistoryRoundTripAndStableAddresses(t *testing.T) {
	names := []string{"one.example", "two.example", "three.example"}
	encoded, checkpoint, err := encodeFakeDNSHistory(123, names)
	if err != nil {
		t.Fatal(err)
	}
	start, restored, err := decodeFakeDNSHistory(encoded, checkpoint)
	if err != nil || start != 123 || !reflect.DeepEqual(restored, names) {
		t.Fatalf("history did not round-trip: %v", err)
	}
	for i, name := range restored {
		if name != names[i] || fakeAddressAt(start, uint32(i)) != fakeAddressAt(123, uint32(i)) {
			t.Fatal("mapping identity changed")
		}
	}
}

func TestFakeDNSHistoryRejectsDamageRollbackAndInvalidNames(t *testing.T) {
	old, _, err := encodeFakeDNSHistory(1, []string{"one.example"})
	if err != nil {
		t.Fatal(err)
	}
	newer, checkpoint, err := encodeFakeDNSHistory(1, []string{"one.example", "two.example"})
	if err != nil {
		t.Fatal(err)
	}
	for name, data := range map[string][]byte{
		"rollback":  old,
		"truncated": newer[:len(newer)-1],
		"extra":     append(bytes.Clone(newer), 0),
		"damaged":   func() []byte { b := bytes.Clone(newer); b[16] ^= 1; return b }(),
	} {
		t.Run(name, func(t *testing.T) {
			if _, _, err := decodeFakeDNSHistory(data, checkpoint); err == nil {
				t.Fatal("invalid history accepted")
			}
		})
	}
	for _, names := range [][]string{{"example.org", "example.org"}, {"Example.org"}, {"invalid"}, {"localhost"}} {
		if _, _, err := encodeFakeDNSHistory(0, names); err == nil {
			t.Fatalf("accepted invalid names %#v", names)
		}
	}
	if _, _, err := encodeFakeDNSHistory(fakeCount, nil); err == nil {
		t.Fatal("accepted invalid start offset")
	}
	// Even with a matching checksum and count, internal structure is checked.
	malformed := bytes.Clone(newer)
	binary.BigEndian.PutUint32(malformed[12:16], fakeCount)
	matching := fakeDNSCheckpoint{Count: fakeCount, Digest: sha256.Sum256(malformed)}
	if _, _, err := decodeFakeDNSHistory(malformed, matching); err == nil {
		t.Fatal("accepted impossible record count")
	}
}
