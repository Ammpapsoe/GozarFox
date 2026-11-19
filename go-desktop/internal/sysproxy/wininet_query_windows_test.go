//go:build windows

package sysproxy

import (
	"testing"
	"unsafe"
)

func TestWinINetLANQuery(t *testing.T) {
	if unsafe.Sizeof(uintptr(0)) != 8 {
		t.Skip("only the 64-bit Windows ABI is implemented")
	}
	if unsafe.Sizeof(internetPerConnOption{}) != 16 || unsafe.Offsetof(internetPerConnOption{}.Value) != 8 || unsafe.Sizeof(internetPerConnOptionList{}) != 32 || unsafe.Offsetof(internetPerConnOptionList{}.Options) != 24 {
		t.Fatal("unexpected WinINet structure layout")
	}
	first, err := queryLANProxy()
	if err != nil {
		t.Fatal(err)
	}
	for i := 0; i < 20; i++ {
		got, err := queryLANProxy()
		if err != nil {
			t.Fatal(err)
		}
		if got != first {
			t.Fatalf("LAN settings changed during read-only query: first %#v, got %#v", first, got)
		}
	}
}
