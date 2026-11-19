//go:build windows && proxyexperiment

package sysproxy

import (
	"errors"
	"fmt"
	"runtime"
	"unsafe"

	"golang.org/x/sys/windows"
)

// setExperimentalLANProxy is intentionally not a production implementation.
// It has no crash journal, ownership check, or conflict protection. It is used
// only by an explicitly opted-in test on a disposable Windows runner.
func setExperimentalLANProxy(p LANProxySettings) error {
	if unsafe.Sizeof(uintptr(0)) != 8 {
		return errors.New("WinINet per-connection setter requires 64-bit Windows")
	}
	if err := internetSetOption.Find(); err != nil {
		return err
	}
	server, err := windows.UTF16PtrFromString(p.Server)
	if err != nil {
		return err
	}
	bypass, err := windows.UTF16PtrFromString(p.Bypass)
	if err != nil {
		return err
	}
	pac, err := windows.UTF16PtrFromString(p.PAC)
	if err != nil {
		return err
	}
	options := []internetPerConnOption{
		{Option: perConnFlags, Value: uintptr(p.Flags)},
		{Option: perConnProxyServer, Value: uintptr(unsafe.Pointer(server))},
		{Option: perConnProxyBypass, Value: uintptr(unsafe.Pointer(bypass))},
		{Option: perConnAutoconfigURL, Value: uintptr(unsafe.Pointer(pac))},
	}
	list := internetPerConnOptionList{Size: uint32(unsafe.Sizeof(internetPerConnOptionList{})), Count: uint32(len(options)), Options: &options[0]}
	ok, _, callErr := internetSetOption.Call(0, internetOptionPerConnection, uintptr(unsafe.Pointer(&list)), uintptr(list.Size))
	runtime.KeepAlive(server)
	runtime.KeepAlive(bypass)
	runtime.KeepAlive(pac)
	runtime.KeepAlive(options)
	if ok == 0 {
		return fmt.Errorf("set WinINet per-connection option %d: %w", list.Error, callErr)
	}
	return notifyWinINET()
}
