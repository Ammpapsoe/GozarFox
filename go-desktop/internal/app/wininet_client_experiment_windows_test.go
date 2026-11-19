//go:build windows && proxyexperiment

package app

import (
	"testing"
	"unsafe"

	"golang.org/x/sys/windows"
)

// This client uses InternetOpen's PRECONFIG mode: it does not specify a proxy
// address, so the request must follow the actual Windows LAN configuration.
func exerciseSystemWinINetTraffic(t *testing.T) {
	t.Helper()
	dll := windows.NewLazySystemDLL("wininet.dll")
	open := dll.NewProc("InternetOpenW")
	openURL := dll.NewProc("InternetOpenUrlW")
	read := dll.NewProc("InternetReadFile")
	closeHandle := dll.NewProc("InternetCloseHandle")
	setOption := dll.NewProc("InternetSetOptionW")
	for _, proc := range []*windows.LazyProc{open, openURL, read, closeHandle, setOption} {
		if err := proc.Find(); err != nil {
			t.Fatal(err)
		}
	}
	agent, _ := windows.UTF16PtrFromString("GozarFox-Disposable-CI")
	h, _, err := open.Call(uintptr(unsafe.Pointer(agent)), 0, 0, 0, 0) // PRECONFIG
	if h == 0 {
		t.Fatalf("InternetOpen: %v", err)
	}
	defer closeHandle.Call(h)
	timeout := uint32(5000)
	for _, option := range []uintptr{2, 5, 6} { // connect, send and receive timeouts
		ok, _, err := setOption.Call(h, option, uintptr(unsafe.Pointer(&timeout)), unsafe.Sizeof(timeout))
		if ok == 0 {
			t.Fatalf("set WinINet timeout %d: %v", option, err)
		}
	}
	url, _ := windows.UTF16PtrFromString("http://origin.example/check")
	request, _, err := openURL.Call(h, uintptr(unsafe.Pointer(url)), 0, 0, 0x80000000|0x04000000, 0)
	if request == 0 {
		t.Fatalf("WinINet PRECONFIG request: %v", err)
	}
	defer closeHandle.Call(request)
	buf := make([]byte, 1024)
	var result []byte
	for {
		var n uint32
		ok, _, err := read.Call(request, uintptr(unsafe.Pointer(&buf[0])), uintptr(len(buf)), uintptr(unsafe.Pointer(&n)))
		if ok == 0 {
			t.Fatalf("WinINet read: %v", err)
		}
		if n == 0 {
			break
		}
		result = append(result, buf[:n]...)
		if len(result) > 4096 {
			t.Fatal("unexpectedly large WinINet response")
		}
	}
	if string(result) != "proxy lifecycle ok" {
		t.Fatalf("WinINet response %q", result)
	}
}
