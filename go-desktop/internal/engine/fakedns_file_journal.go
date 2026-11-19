package engine

import (
	"bytes"
	"crypto/sha256"
	"encoding/binary"
	"errors"
	"fmt"
	"io"
	"os"
)

// fileDNSJournal implements append-only ordered mappings and a separate
// checkpoint log. Callers MUST open both files inside a private directory,
// hold an OS cross-process lock for their entire lifetime, and independently
// distinguish first use from deletion of an existing journal. Neither
// checksums nor this type prevent malicious same-user edits or coordinated
// rollback of both files. No production opener or activation exists yet.
// File names are sensitive: access control is a prerequisite for deployment.
type fileDNSJournal struct {
	history     *os.File
	anchor      *os.File
	start       uint32
	hashes      [][sha256.Size]byte
	names       map[string]struct{}
	loaded      bool
	failed      bool
	historySize int64
	anchorSize  int64
	// For fault injection; nil selects the real durable Sync.
	syncFile func(*os.File) error
}

const dnsFileLimit int64 = 64 << 20
const dnsAnchorLimit int64 = 16 << 20
const dnsCheckpointSize = 4 + sha256.Size + sha256.Size

var dnsFileMagic = [8]byte{'G', 'Z', 'D', 'N', 'S', 'L', '0', '1'}
var dnsAnchorMagic = [8]byte{'G', 'Z', 'D', 'N', 'S', 'A', '0', '1'}

func (j *fileDNSJournal) sync(f *os.File) error {
	if j.syncFile != nil {
		return j.syncFile(f)
	}
	return f.Sync()
}

func dnsReadFile(f *os.File, limit int64) ([]byte, error) {
	if f == nil {
		return nil, errors.New("missing synthetic DNS journal file")
	}
	if _, err := f.Seek(0, io.SeekStart); err != nil {
		return nil, err
	}
	data, err := io.ReadAll(io.LimitReader(f, limit+1))
	if err != nil {
		return nil, err
	}
	if int64(len(data)) > limit {
		return nil, errors.New("synthetic DNS journal file too large")
	}
	return data, nil
}

func dnsAppend(f *os.File, data []byte) error {
	if _, err := f.Seek(0, io.SeekEnd); err != nil {
		return err
	}
	for len(data) > 0 {
		n, err := f.Write(data)
		if err != nil {
			return err
		}
		if n == 0 {
			return io.ErrShortWrite
		}
		data = data[n:]
	}
	return nil
}

func dnsEntryHash(previous [sha256.Size]byte, sequence uint32, name string) [sha256.Size]byte {
	h := sha256.New()
	_, _ = h.Write(previous[:])
	var count [4]byte
	binary.BigEndian.PutUint32(count[:], sequence)
	_, _ = h.Write(count[:])
	_, _ = h.Write([]byte{byte(len(name))})
	_, _ = h.Write([]byte(name))
	var result [sha256.Size]byte
	copy(result[:], h.Sum(nil))
	return result
}

func dnsCheckpoint(sequence uint32, hash [sha256.Size]byte) []byte {
	out := make([]byte, dnsCheckpointSize)
	binary.BigEndian.PutUint32(out[:4], sequence)
	copy(out[4:4+sha256.Size], hash[:])
	sum := sha256.Sum256(out[:4+sha256.Size])
	copy(out[4+sha256.Size:], sum[:])
	return out
}

// Load accepts complete history records after the last durable checkpoint,
// anchors them before returning, and never reuses those possibly-written
// identities. Truncated or inconsistent files fail closed.
func (j *fileDNSJournal) Load() (uint32, []string, bool, error) {
	if j == nil || j.failed {
		return 0, nil, false, errors.New("synthetic DNS journal unavailable")
	}
	data, err := dnsReadFile(j.history, dnsFileLimit)
	if err != nil {
		return 0, nil, false, err
	}
	anchor, err := dnsReadFile(j.anchor, dnsAnchorLimit)
	if err != nil {
		return 0, nil, false, err
	}
	if len(data) == 0 && len(anchor) == 0 {
		if j.loaded {
			return 0, nil, false, errors.New("synthetic DNS journal disappeared")
		}
		return 0, nil, false, nil
	}
	if len(data) < 12 || len(anchor) < 8+dnsCheckpointSize || !bytes.Equal(data[:8], dnsFileMagic[:]) || !bytes.Equal(anchor[:8], dnsAnchorMagic[:]) || (len(anchor)-8)%dnsCheckpointSize != 0 {
		return 0, nil, false, errors.New("incomplete synthetic DNS journal")
	}
	start := binary.BigEndian.Uint32(data[8:12])
	if start >= fakeCount {
		return 0, nil, false, errors.New("invalid synthetic DNS journal offset")
	}
	root := sha256.Sum256(data[:12])
	hashes := [][sha256.Size]byte{root}
	names := make([]string, 0)
	seen := make(map[string]struct{})
	for pos := 12; pos < len(data); {
		length := int(data[pos])
		pos++
		if length == 0 || length+sha256.Size > len(data)-pos {
			return 0, nil, false, errors.New("truncated synthetic DNS record")
		}
		name := string(data[pos : pos+length])
		pos += length
		if !fakeDomain(name) || len(names) >= int(fakeCount) {
			return 0, nil, false, errors.New("invalid synthetic DNS journal name")
		}
		if _, ok := seen[name]; ok {
			return 0, nil, false, errors.New("duplicate synthetic DNS journal name")
		}
		seen[name] = struct{}{}
		hash := dnsEntryHash(hashes[len(hashes)-1], uint32(len(names)+1), name)
		if !bytes.Equal(data[pos:pos+sha256.Size], hash[:]) {
			return 0, nil, false, errors.New("damaged synthetic DNS record")
		}
		pos += sha256.Size
		names = append(names, name)
		hashes = append(hashes, hash)
	}
	checkpoints := (len(anchor) - 8) / dnsCheckpointSize
	if checkpoints > len(hashes) {
		return 0, nil, false, errors.New("synthetic DNS history is older than its checkpoint")
	}
	for i := 0; i < checkpoints; i++ {
		entry := anchor[8+i*dnsCheckpointSize : 8+(i+1)*dnsCheckpointSize]
		if !bytes.Equal(entry, dnsCheckpoint(uint32(i), hashes[i])) {
			return 0, nil, false, errors.New("synthetic DNS checkpoint mismatch")
		}
	}
	// A crash after the data flush and before checkpoint flush leaves complete
	// additional records. Preserve and checkpoint them, rather than reassign.
	if checkpoints < len(hashes) {
		for i := checkpoints; i < len(hashes); i++ {
			if err := dnsAppend(j.anchor, dnsCheckpoint(uint32(i), hashes[i])); err != nil {
				j.failed = true
				return 0, nil, false, err
			}
		}
		if err := j.sync(j.anchor); err != nil {
			j.failed = true
			return 0, nil, false, err
		}
	}
	j.start = start
	j.hashes = hashes
	j.names = seen
	j.loaded = true
	j.historySize = int64(len(data))
	j.anchorSize = int64(8 + len(hashes)*dnsCheckpointSize)
	return start, names, true, nil
}

func (j *fileDNSJournal) Initialize(start uint32) error {
	if j == nil || j.failed || start >= fakeCount {
		return errors.New("invalid synthetic DNS journal initialization")
	}
	data, err := dnsReadFile(j.history, dnsFileLimit)
	if err != nil {
		return err
	}
	anchor, err := dnsReadFile(j.anchor, dnsAnchorLimit)
	if err != nil {
		return err
	}
	if len(data) != 0 || len(anchor) != 0 {
		return errors.New("synthetic DNS journal already exists or is incomplete")
	}
	header := make([]byte, 12)
	copy(header, dnsFileMagic[:])
	binary.BigEndian.PutUint32(header[8:], start)
	root := sha256.Sum256(header)
	if err = dnsAppend(j.history, header); err == nil {
		err = j.sync(j.history)
	}
	if err == nil {
		err = dnsAppend(j.anchor, dnsAnchorMagic[:])
	}
	if err == nil {
		err = dnsAppend(j.anchor, dnsCheckpoint(0, root))
	}
	if err == nil {
		err = j.sync(j.anchor)
	}
	if err != nil {
		j.failed = true
		return fmt.Errorf("initialize synthetic DNS journal: %w", err)
	}
	j.start = start
	j.hashes = [][sha256.Size]byte{root}
	j.names = make(map[string]struct{})
	j.loaded = true
	j.historySize = int64(len(header))
	j.anchorSize = int64(len(dnsAnchorMagic) + dnsCheckpointSize)
	return nil
}

func (j *fileDNSJournal) Append(name string) error {
	return j.AppendBatch([]string{name})
}

// AppendBatch flushes all history records, then their checkpoints, before
// acknowledging any name. Recovery either preserves every complete record or
// fails closed on a partial one. No address is returned on an uncertain write.
func (j *fileDNSJournal) AppendBatch(names []string) error {
	if j == nil || j.failed || !j.loaded {
		return errors.New("synthetic DNS journal is not ready")
	}
	if len(names) == 0 || len(names) > fakeDNSBatchLimit || len(j.names)+len(names) > int(fakeCount) {
		return errors.New("invalid synthetic DNS batch size")
	}
	info, err := j.history.Stat()
	if err != nil {
		return err
	}
	anchorInfo, err := j.anchor.Stat()
	if err != nil {
		return err
	}
	if info.Size() != j.historySize || anchorInfo.Size() != j.anchorSize {
		j.failed = true
		return errors.New("synthetic DNS journal changed unexpectedly")
	}
	seen := make(map[string]struct{}, len(names))
	var records, checkpoints []byte
	hashes := make([][sha256.Size]byte, 0, len(names))
	previous := j.hashes[len(j.hashes)-1]
	for i, name := range names {
		if !fakeDomain(name) {
			return errors.New("invalid synthetic DNS allocation")
		}
		if _, ok := j.names[name]; ok {
			return errors.New("duplicate synthetic DNS allocation")
		}
		if _, ok := seen[name]; ok {
			return errors.New("duplicate synthetic DNS batch allocation")
		}
		seen[name] = struct{}{}
		sequence := uint32(len(j.hashes) + i)
		hash := dnsEntryHash(previous, sequence, name)
		records = append(records, byte(len(name)))
		records = append(records, []byte(name)...)
		records = append(records, hash[:]...)
		checkpoints = append(checkpoints, dnsCheckpoint(sequence, hash)...)
		hashes = append(hashes, hash)
		previous = hash
	}
	if info.Size()+int64(len(records)) > dnsFileLimit || anchorInfo.Size()+int64(len(checkpoints)) > dnsAnchorLimit {
		return errors.New("synthetic DNS history size limit reached")
	}
	if err = dnsAppend(j.history, records); err == nil {
		err = j.sync(j.history)
	}
	if err == nil {
		err = dnsAppend(j.anchor, checkpoints)
	}
	if err == nil {
		err = j.sync(j.anchor)
	}
	if err != nil {
		j.failed = true
		return fmt.Errorf("persist synthetic DNS allocations: %w", err)
	}
	j.hashes = append(j.hashes, hashes...)
	for _, name := range names {
		j.names[name] = struct{}{}
	}
	j.historySize += int64(len(records))
	j.anchorSize += int64(len(checkpoints))
	return nil
}
