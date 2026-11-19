package engine

import (
	"errors"
	"os"
	"path/filepath"
	"testing"
)

func openTestDNSFiles(t *testing.T, dir string) *fileDNSJournal {
	t.Helper()
	open := func(name string) *os.File {
		f, err := os.OpenFile(filepath.Join(dir, name), os.O_CREATE|os.O_RDWR, 0600)
		if err != nil {
			t.Fatal(err)
		}
		t.Cleanup(func() { _ = f.Close() })
		return f
	}
	return &fileDNSJournal{history: open("history"), anchor: open("checkpoint")}
}

func TestFakeDNSFileJournalDurableIdentity(t *testing.T) {
	dir := t.TempDir()
	first := openTestDNSFiles(t, dir)
	f, err := newJournalFakeDNS(first, nil)
	if err != nil {
		t.Fatal(err)
	}
	one, err := f.address("one.example")
	if err != nil {
		t.Fatal(err)
	}
	two, err := f.address("two.example")
	if err != nil {
		t.Fatal(err)
	}
	_ = first.history.Close()
	_ = first.anchor.Close()
	second := openTestDNSFiles(t, dir)
	reopened, err := newJournalFakeDNS(second, nil)
	if err != nil {
		t.Fatal(err)
	}
	if got, err := reopened.address("one.example"); err != nil || got != one {
		t.Fatalf("identity changed: %s, %v", got, err)
	}
	if name, synthetic, known := reopened.lookup(two); !synthetic || !known || name != "two.example" {
		t.Fatal("second identity not restored")
	}
	three, err := reopened.address("three.example")
	if err != nil || three == one || three == two {
		t.Fatal("address reused", err)
	}
}

func TestFakeDNSFileJournalRecoverAheadOfCheckpoint(t *testing.T) {
	dir := t.TempDir()
	j := openTestDNSFiles(t, dir)
	f, err := newJournalFakeDNS(j, nil)
	if err != nil {
		t.Fatal(err)
	}
	one, err := f.address("one.example")
	if err != nil {
		t.Fatal(err)
	}
	two, err := f.address("two.example")
	if err != nil {
		t.Fatal(err)
	}
	// Emulate death after flushing the last data record but before its anchor.
	info, err := j.anchor.Stat()
	if err != nil {
		t.Fatal(err)
	}
	if err := j.anchor.Truncate(info.Size() - dnsCheckpointSize); err != nil {
		t.Fatal(err)
	}
	_ = j.history.Close()
	_ = j.anchor.Close()
	reopened, err := newJournalFakeDNS(openTestDNSFiles(t, dir), nil)
	if err != nil {
		t.Fatal(err)
	}
	if got, err := reopened.address("one.example"); err != nil || got != one {
		t.Fatal("first identity lost", err)
	}
	if got, err := reopened.address("two.example"); err != nil || got != two {
		t.Fatal("unanchored identity reused", err)
	}
	three, err := reopened.address("three.example")
	if err != nil || three == one || three == two {
		t.Fatal("recovery reused identity", err)
	}
}

func TestFakeDNSFileJournalRejectsIncompleteAndRollback(t *testing.T) {
	for _, scenario := range []string{"truncated-data", "truncated-anchor", "older-data", "both-empty", "corrupt"} {
		t.Run(scenario, func(t *testing.T) {
			dir := t.TempDir()
			j := openTestDNSFiles(t, dir)
			f, err := newJournalFakeDNS(j, nil)
			if err != nil {
				t.Fatal(err)
			}
			if _, err := f.address("one.example"); err != nil {
				t.Fatal(err)
			}
			previous, err := j.history.Stat()
			if err != nil {
				t.Fatal(err)
			}
			if _, err := f.address("two.example"); err != nil {
				t.Fatal(err)
			}
			switch scenario {
			case "truncated-data":
				info, _ := j.history.Stat()
				err = j.history.Truncate(info.Size() - 1)
			case "truncated-anchor":
				info, _ := j.anchor.Stat()
				err = j.anchor.Truncate(info.Size() - 1)
			case "older-data":
				err = j.history.Truncate(previous.Size())
			case "both-empty":
				if err = j.history.Truncate(0); err == nil {
					err = j.anchor.Truncate(0)
				}
				if err != nil {
					t.Fatal(err)
				}
				if _, _, _, loadErr := j.Load(); loadErr == nil {
					t.Fatal("accepted removed live journal")
				}
				return
			case "corrupt":
				_, err = j.history.WriteAt([]byte{0}, 13)
			}
			if err != nil {
				t.Fatal(err)
			}
			_ = j.history.Close()
			_ = j.anchor.Close()
			if _, err := newJournalFakeDNS(openTestDNSFiles(t, dir), nil); err == nil {
				t.Fatal("accepted damaged journal")
			}
		})
	}
}

func TestFakeDNSFileJournalUncertainWriteStopsAllocation(t *testing.T) {
	dir := t.TempDir()
	j := openTestDNSFiles(t, dir)
	f, err := newJournalFakeDNS(j, nil)
	if err != nil {
		t.Fatal(err)
	}
	stable, err := f.address("stable.example")
	if err != nil {
		t.Fatal(err)
	}
	j.syncFile = func(file *os.File) error {
		if file == j.anchor {
			return errors.New("simulated checkpoint sync failure")
		}
		return file.Sync()
	}
	if _, err := f.address("uncertain.example"); err == nil {
		t.Fatal("returned uncertain identity")
	}
	if _, err := f.address("later.example"); err == nil {
		t.Fatal("continued after uncertain write")
	}
	_ = j.history.Close()
	_ = j.anchor.Close()
	reopened, err := newJournalFakeDNS(openTestDNSFiles(t, dir), nil)
	if err != nil {
		t.Fatal(err)
	}
	if got, err := reopened.address("stable.example"); err != nil || got != stable {
		t.Fatal("durable identity changed", err)
	}
	uncertain, err := reopened.address("uncertain.example")
	if err != nil || uncertain == stable {
		t.Fatal("uncertain identity reused", err)
	}
}
