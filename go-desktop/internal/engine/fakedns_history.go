package engine

import (
	"bytes"
	"crypto/sha256"
	"encoding/binary"
	"errors"
)

// fakeDNSHistory is a bounded serialization format for a future durable
// journal. The separate checkpoint must be stored durably and independently;
// a checksum alone cannot detect simultaneous deletion or rollback of both.
// This codec provides neither storage, OS locking, access control, nor crash
// consistency. It is deliberately not wired into the production engine.
const fakeDNSHistoryLimit = 16 << 20

var fakeDNSHistoryMagic = [8]byte{'G', 'Z', 'D', 'N', 'S', '0', '0', '1'}

type fakeDNSCheckpoint struct {
	Count  uint32
	Digest [sha256.Size]byte
}

func encodeFakeDNSHistory(start uint32, names []string) ([]byte, fakeDNSCheckpoint, error) {
	var checkpoint fakeDNSCheckpoint
	if start >= fakeCount || len(names) > int(fakeCount) {
		return nil, checkpoint, errors.New("invalid synthetic DNS history size")
	}
	var out bytes.Buffer
	out.Write(fakeDNSHistoryMagic[:])
	var word [4]byte
	binary.BigEndian.PutUint32(word[:], start)
	out.Write(word[:])
	binary.BigEndian.PutUint32(word[:], uint32(len(names)))
	out.Write(word[:])
	seen := make(map[string]struct{}, len(names))
	for _, name := range names {
		if !fakeDomain(name) {
			return nil, checkpoint, errors.New("invalid synthetic DNS history name")
		}
		if _, ok := seen[name]; ok {
			return nil, checkpoint, errors.New("duplicate synthetic DNS history name")
		}
		seen[name] = struct{}{}
		if out.Len()+1+len(name) > fakeDNSHistoryLimit {
			return nil, checkpoint, errors.New("synthetic DNS history exceeds size limit")
		}
		out.WriteByte(byte(len(name)))
		out.WriteString(name)
	}
	encoded := out.Bytes()
	checkpoint.Count = uint32(len(names))
	checkpoint.Digest = sha256.Sum256(encoded)
	return encoded, checkpoint, nil
}

func decodeFakeDNSHistory(data []byte, checkpoint fakeDNSCheckpoint) (uint32, []string, error) {
	if len(data) < 16 || len(data) > fakeDNSHistoryLimit || !bytes.Equal(data[:8], fakeDNSHistoryMagic[:]) {
		return 0, nil, errors.New("invalid synthetic DNS history header")
	}
	start, count := binary.BigEndian.Uint32(data[8:12]), binary.BigEndian.Uint32(data[12:16])
	if start >= fakeCount || count > fakeCount || count != checkpoint.Count || sha256.Sum256(data) != checkpoint.Digest {
		return 0, nil, errors.New("synthetic DNS history does not match checkpoint")
	}
	// Each name uses at least two bytes (length plus contents); avoid trusting
	// a corrupted count as a large allocation before checking input bounds.
	if uint64(count)*2 > uint64(len(data)-16) {
		return 0, nil, errors.New("truncated synthetic DNS history")
	}
	names := make([]string, 0, int(count))
	seen := make(map[string]struct{}, int(count))
	position := 16
	for i := uint32(0); i < count; i++ {
		if position >= len(data) {
			return 0, nil, errors.New("truncated synthetic DNS history")
		}
		length := int(data[position])
		position++
		if length == 0 || length > len(data)-position {
			return 0, nil, errors.New("invalid synthetic DNS history record")
		}
		name := string(data[position : position+length])
		position += length
		if !fakeDomain(name) {
			return 0, nil, errors.New("invalid synthetic DNS history name")
		}
		if _, ok := seen[name]; ok {
			return 0, nil, errors.New("duplicate synthetic DNS history name")
		}
		seen[name] = struct{}{}
		names = append(names, name)
	}
	if position != len(data) {
		return 0, nil, errors.New("trailing synthetic DNS history data")
	}
	return start, names, nil
}
