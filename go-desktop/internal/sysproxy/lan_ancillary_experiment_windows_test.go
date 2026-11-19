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

func TestLANExperimentAncillaryOptions(t *testing.T) {
	requireDisposableLAN(t)
	store := experimentalLANStore{}
	original, err := store.ReadLAN()
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() {
		if err := store.WriteLAN(original); err != nil {
			t.Errorf("restore runner LAN: %v", err)
		}
	})
	seed := LANProxySettings{Flags: 13, Server: "prior.example:8080", Bypass: "*.example", PAC: "https://example.invalid/proxy.pac"}
	if err := store.WriteLAN(seed); err != nil {
		t.Fatal(err)
	}
	before, err := queryAncillaryLAN()
	if err != nil {
		t.Fatal(err)
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
