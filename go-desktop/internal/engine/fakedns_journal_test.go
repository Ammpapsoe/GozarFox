package engine

import (
	"errors"
	"testing"
)

type memoryDNSJournal struct {
	start           uint32
	names           []string
	exists          bool
	failLoad        bool
	failInit        bool
	failAppend      bool
	commitOnFailure bool
}

func (j *memoryDNSJournal) Load() (uint32, []string, bool, error) {
	if j.failLoad {
		return 0, nil, false, errors.New("journal unavailable")
	}
	return j.start, append([]string(nil), j.names...), j.exists, nil
}
func (j *memoryDNSJournal) Initialize(start uint32) error {
	if j.failInit || j.exists {
		return errors.New("initialization failed")
	}
	j.start, j.exists = start, true
	return nil
}
func (j *memoryDNSJournal) Append(name string) error {
	if j.failAppend {
		if j.commitOnFailure {
			j.names = append(j.names, name)
		}
		return errors.New("uncertain write")
	}
	j.names = append(j.names, name)
	return nil
}

// Independent stores do not establish a machine-wide identity: if their
// offset overlaps, the second can interpret a stale address as another name.
// This documents a blocker to production activation, not desirable behavior.
func TestJournalFakeDNSIndependentNamespacesCanAlias(t *testing.T) {
	first, err := newJournalFakeDNS(&memoryDNSJournal{start: 42, exists: true}, nil)
	if err != nil {
		t.Fatal(err)
	}
	second, err := newJournalFakeDNS(&memoryDNSJournal{start: 42, exists: true}, nil)
	if err != nil {
		t.Fatal(err)
	}
	stale, err := first.address("first.example")
	if err != nil {
		t.Fatal(err)
	}
	reused, err := second.address("second.example")
	if err != nil {
		t.Fatal(err)
	}
	name, synthetic, known := second.lookup(stale)
	if stale != reused || !synthetic || !known || name != "second.example" {
		t.Fatal("test did not reproduce independent namespace aliasing")
	}
}

func TestJournalFakeDNSPreservesIdentityAcrossReopen(t *testing.T) {
	journal := &memoryDNSJournal{}
	first, err := newJournalFakeDNS(journal, nil)
	if err != nil {
		t.Fatal(err)
	}
	a, err := first.address("one.example")
	if err != nil {
		t.Fatal(err)
	}
	b, err := first.address("two.example")
	if err != nil || a == b {
		t.Fatal("allocation failed or reused an address")
	}
	second, err := newJournalFakeDNS(journal, nil)
	if err != nil {
		t.Fatal(err)
	}
	if again, err := second.address("one.example"); err != nil || again != a {
		t.Fatal("first mapping changed after reopen")
	}
	if name, synthetic, ok := second.lookup(b); !synthetic || !ok || name != "two.example" {
		t.Fatal("second mapping was not restored")
	}
	c, err := second.address("three.example")
	if err != nil || c == a || c == b || len(journal.names) != 3 {
		t.Fatal("new mapping reused an earlier address")
	}
}

func TestJournalFakeDNSStopsAfterUncertainWrite(t *testing.T) {
	for _, committed := range []bool{false, true} {
		journal := &memoryDNSJournal{}
		f, err := newJournalFakeDNS(journal, nil)
		if err != nil {
			t.Fatal(err)
		}
		stable, err := f.address("stable.example")
		if err != nil {
			t.Fatal(err)
		}
		journal.failAppend, journal.commitOnFailure = true, committed
		if _, err := f.address("uncertain.example"); err == nil {
			t.Fatal("uncertain write returned an address")
		}
		journal.failAppend = false
		if _, err := f.address("other.example"); err == nil {
			t.Fatal("allocation continued after uncertain write")
		}
		if got, err := f.address("stable.example"); err != nil || got != stable {
			t.Fatal("previously durable mapping was lost")
		}
		reopened, err := newJournalFakeDNS(journal, nil)
		if err != nil {
			t.Fatal(err)
		}
		other, err := reopened.address("other.example")
		if err != nil || other == stable {
			t.Fatal("reopen did not preserve prior allocations")
		}
		if committed {
			uncertain, err := reopened.address("uncertain.example")
			if err != nil || uncertain == stable || uncertain == other {
				t.Fatal("committed record was reused")
			}
		}
	}
}

func TestJournalFakeDNSRejectsInvalidHistory(t *testing.T) {
	cases := []*memoryDNSJournal{
		{failLoad: true},
		{failInit: true},
		{exists: false, names: []string{"example.org"}},
		{exists: true, start: fakeCount},
		{exists: true, names: []string{"example.org", "example.org"}},
		{exists: true, names: []string{"invalid name"}},
		{exists: true, names: []string{"Example.org"}},
	}
	for i, journal := range cases {
		if _, err := newJournalFakeDNS(journal, nil); err == nil {
			t.Errorf("case %d accepted invalid or unavailable history", i)
		}
	}
}
