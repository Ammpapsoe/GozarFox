package sysproxy

import (
	"errors"
	"reflect"
	"testing"
)

type memoryJournal struct {
	snapshot  *ProxySnapshot
	failLoad  bool
	failSave  bool
	failClear bool
}

func (j *memoryJournal) Load() (*ProxySnapshot, error) {
	if j.failLoad {
		return nil, errors.New("load failure")
	}
	return j.snapshot, nil
}
func (j *memoryJournal) Save(s ProxySnapshot) error {
	if j.failSave {
		return errors.New("save failure")
	}
	if j.snapshot != nil {
		return errors.New("journal already occupied")
	}
	j.snapshot = &s
	return nil
}
func (j *memoryJournal) Clear() error {
	if j.failClear {
		return errors.New("clear failure")
	}
	j.snapshot = nil
	return nil
}

func TestRecoveryAfterActivation(t *testing.T) {
	store := newMemoryProxy()
	original := copyValues(store.values)
	journal := &memoryJournal{}
	if err := activateProxy(store, journal, 10810); err != nil {
		t.Fatal(err)
	}
	if journal.snapshot == nil {
		t.Fatal("activation did not retain backup")
	}
	// A later process can recover after termination between activation and close.
	if err := recoverProxy(store, journal); err != nil {
		t.Fatal(err)
	}
	if !reflect.DeepEqual(store.values, original) || journal.snapshot != nil {
		t.Fatalf("recovery incomplete: %#v", store.values)
	}
}

func TestRecoverySaveFailureDoesNotMutateSettings(t *testing.T) {
	for _, journal := range []*memoryJournal{{failLoad: true}, {failSave: true}} {
		store := newMemoryProxy()
		original := copyValues(store.values)
		if err := activateProxy(store, journal, 10810); err == nil {
			t.Fatal("expected journal failure")
		}
		if !reflect.DeepEqual(store.values, original) {
			t.Fatal("journal failure changed proxy settings")
		}
	}
}

func TestRecoveryKeepsBackupAfterApplyFailure(t *testing.T) {
	store := newMemoryProxy()
	original := copyValues(store.values)
	store.failOn = "ProxyOverride"
	journal := &memoryJournal{}
	if err := activateProxy(store, journal, 10810); err == nil {
		t.Fatal("expected apply failure")
	}
	if journal.snapshot == nil {
		t.Fatal("failed activation discarded backup")
	}
	if err := recoverProxy(store, journal); err != nil {
		t.Fatal(err)
	}
	if !reflect.DeepEqual(store.values, original) || journal.snapshot != nil {
		t.Fatal("failed activation was not recovered")
	}
}

func TestRecoveryConflictPreservesBackup(t *testing.T) {
	store := newMemoryProxy()
	journal := &memoryJournal{}
	if err := activateProxy(store, journal, 10810); err != nil {
		t.Fatal(err)
	}
	store.values["ProxyServer"] = text("other.example:8080")
	before := copyValues(store.values)
	if err := activateProxy(store, journal, 10811); err == nil {
		t.Fatal("conflict did not prevent activation")
	}
	if !reflect.DeepEqual(store.values, before) || journal.snapshot == nil {
		t.Fatal("conflict lost settings or backup")
	}
}

func TestRecoveryClearFailureCanRetry(t *testing.T) {
	store := newMemoryProxy()
	original := copyValues(store.values)
	journal := &memoryJournal{}
	if err := activateProxy(store, journal, 10810); err != nil {
		t.Fatal(err)
	}
	journal.failClear = true
	if err := recoverProxy(store, journal); err == nil || journal.snapshot == nil {
		t.Fatal("failed clear discarded backup")
	}
	journal.failClear = false
	if err := recoverProxy(store, journal); err != nil {
		t.Fatal(err)
	}
	if !reflect.DeepEqual(store.values, original) || journal.snapshot != nil {
		t.Fatal("retry did not finish recovery")
	}
}
