//go:build windows && proxyexperiment

package sysproxy

import (
	"fmt"
	"runtime"
	"testing"
	"unsafe"

	"golang.org/x/sys/windows"
)

type ancillaryLAN struct {
	DiscoveryFlags uint32
	SecondaryURL   string
	ReloadMinutes  uint32
}

func queryAncillaryLAN() (ancillaryLAN, error) {
	var result ancillaryLAN
	options := []internetPerConnOption{{Option: 5}, {Option: 6}, {Option: 7}}
	list := internetPerConnOptionList{Size: uint32(unsafe.Sizeof(internetPerConnOptionList{})), Count: uint32(len(options)), Options: &options[0]}
	size := list.Size
	ok, _, callErr := queryInternetOption.Call(0, internetOptionPerConnection, uintptr(unsafe.Pointer(&list)), uintptr(unsafe.Pointer(&size)))
	runtime.KeepAlive(options)
	defer func() {
		if options[1].Value != 0 {
			globalFreeMemory.Call(options[1].Value)
		}
	}()
	if ok == 0 {
		return result, fmt.Errorf("query ancillary LAN option %d: %w", list.Error, callErr)
	}
	result.DiscoveryFlags = uint32(options[0].Value)
	if options[1].Value != 0 {
		result.SecondaryURL = windows.UTF16PtrToString(*(**uint16)(unsafe.Pointer(&options[1].Value)))
	}
	result.ReloadMinutes = uint32(options[2].Value)
	return result, nil
}

func setAncillaryLAN(p ancillaryLAN) error {
	secondary, err := windows.UTF16PtrFromString(p.SecondaryURL)
	if err != nil {
		return err
	}
	options := []internetPerConnOption{
		{Option: 5, Value: uintptr(p.DiscoveryFlags)},
		{Option: 6, Value: uintptr(unsafe.Pointer(secondary))},
		{Option: 7, Value: uintptr(p.ReloadMinutes)},
	}
	list := internetPerConnOptionList{Size: uint32(unsafe.Sizeof(internetPerConnOptionList{})), Count: uint32(len(options)), Options: &options[0]}
	ok, _, callErr := internetSetOption.Call(0, internetOptionPerConnection, uintptr(unsafe.Pointer(&list)), uintptr(list.Size))
	runtime.KeepAlive(secondary)
	runtime.KeepAlive(options)
	if ok == 0 {
		return fmt.Errorf("set ancillary LAN option %d: %w", list.Error, callErr)
	}
	return notifyWinINET()
}

func TestLANExperimentAncillaryOptions(t *testing.T) {
	requireDisposableLAN(t)
	store := wininetLANStore{}
	original, err := store.ReadLAN()
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() {
		if err := store.WriteLAN(original); err != nil {
			t.Errorf("restore runner LAN: %v", err)
		}
	})
	originalAncillary, err := queryAncillaryLAN()
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() {
		if err := setAncillaryLAN(originalAncillary); err != nil {
			t.Errorf("restore runner ancillary LAN settings: %v", err)
		}
	})
	seed := LANProxySettings{Flags: 13, Server: "prior.example:8080", Bypass: "*.example", PAC: "https://example.invalid/proxy.pac"}
	if err := store.WriteLAN(seed); err != nil {
		t.Fatal(err)
	}
	custom := ancillaryLAN{DiscoveryFlags: 3, SecondaryURL: "https://example.invalid/secondary.pac", ReloadMinutes: 27}
	if err := setAncillaryLAN(custom); err != nil {
		t.Fatalf("seed non-default ancillary settings: %v", err)
	}
	before, err := queryAncillaryLAN()
	if err != nil {
		t.Fatal(err)
	}
	if before != custom {
		t.Fatalf("could not seed non-default ancillary settings: got %#v, want %#v", before, custom)
	}
	if err := store.WriteLAN(desiredLAN(23456)); err != nil {
		t.Fatal(err)
	}
	if err := store.WriteLAN(seed); err != nil {
		t.Fatal(err)
	}
	after, err := queryAncillaryLAN()
	if err != nil {
		t.Fatal(err)
	}
	if before != after {
		t.Fatalf("ancillary WinINet settings changed by round-trip: before %#v, after %#v", before, after)
	}
	t.Logf("ancillary WinINet settings round-tripped: %#v", after)
}
