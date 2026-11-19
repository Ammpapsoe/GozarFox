//go:build windows

package sysproxy

import (
	"errors"
	"fmt"
	"runtime"
	"unsafe"

	"golang.org/x/sys/windows"
)

const (
	internetOptionPerConnection = 75
	perConnFlags                = 1
	perConnProxyServer          = 2
	perConnProxyBypass          = 3
	perConnAutoconfigURL        = 4
)

var (
	queryInternetOption = windows.NewLazySystemDLL("wininet.dll").NewProc("InternetQueryOptionW")
	globalFreeMemory    = windows.NewLazySystemDLL("kernel32.dll").NewProc("GlobalFree")
)

// LANProxySettings represents the documented WinINet per-connection options
// for the default LAN connection, not raw registry values or WinHTTP state.
type LANProxySettings struct {
	Flags  uint32
	Server string
	Bypass string
	PAC    string
}

// On 64-bit Windows the option value union occupies 8 bytes and is 8-byte
// aligned. Refuse other architectures rather than using an incorrect ABI.
type internetPerConnOption struct {
	Option uint32
	Value  uintptr
}

type internetPerConnOptionList struct {
	Size       uint32
	Connection *uint16
	Count      uint32
	Error      uint32
	Options    *internetPerConnOption
}

// queryLANProxy reads the documented per-connection configuration. Returned
// strings are allocated by WinINet and must be freed with GlobalFree. This is
// deliberately read-only and does not assume the raw connection blobs' layout.
func queryLANProxy() (result LANProxySettings, resultErr error) {
	if unsafe.Sizeof(uintptr(0)) != 8 {
		return result, errors.New("WinINet per-connection query requires 64-bit Windows")
	}
	if err := queryInternetOption.Find(); err != nil {
		return result, err
	}
	if err := globalFreeMemory.Find(); err != nil {
		return result, err
	}
	options := []internetPerConnOption{
		{Option: perConnFlags}, {Option: perConnProxyServer},
		{Option: perConnProxyBypass}, {Option: perConnAutoconfigURL},
	}
	list := internetPerConnOptionList{
		Size:  uint32(unsafe.Sizeof(internetPerConnOptionList{})),
		Count: uint32(len(options)), Options: &options[0],
	}
	size := list.Size
	ok, _, callErr := queryInternetOption.Call(0, internetOptionPerConnection, uintptr(unsafe.Pointer(&list)), uintptr(unsafe.Pointer(&size)))
	runtime.KeepAlive(options)
	// Query can allocate some strings even when it fails. Free every non-null
	// returned string exactly once, including that failure path.
	defer func() {
		for i := 1; i < len(options); i++ {
			if options[i].Value != 0 {
				remaining, _, err := globalFreeMemory.Call(options[i].Value)
				if remaining != 0 {
					resultErr = errors.Join(resultErr, fmt.Errorf("free WinINet option: %w", err))
				}
			}
		}
	}()
	if ok == 0 {
		return result, fmt.Errorf("query WinINet per-connection option %d: %w", list.Error, callErr)
	}
	read := func(option *internetPerConnOption) string {
		if option.Value == 0 {
			return ""
		}
		return windows.UTF16PtrToString(*(**uint16)(unsafe.Pointer(&option.Value)))
	}
	result = LANProxySettings{Flags: uint32(options[0].Value), Server: read(&options[1]), Bypass: read(&options[2]), PAC: read(&options[3])}
	return result, nil
}
