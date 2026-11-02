//go:build windows

package config

import (
	"encoding/base64"
	"errors"
	"unsafe"

	"golang.org/x/sys/windows"
)

type dataBlob struct {
	size uint32
	data *byte
}

var (
	crypt32          = windows.NewLazySystemDLL("crypt32.dll")
	kernel32         = windows.NewLazySystemDLL("kernel32.dll")
	procProtect      = crypt32.NewProc("CryptProtectData")
	procUnprotect    = crypt32.NewProc("CryptUnprotectData")
	procLocalFree    = kernel32.NewProc("LocalFree")
	entropy          = []byte("gozar.session.v1")
	errProtectFailed = errors.New("the session key could not be protected")
)

func blob(data []byte) dataBlob {
	if len(data) == 0 {
		return dataBlob{}
	}
	return dataBlob{size: uint32(len(data)), data: &data[0]}
}

func protect(plain []byte) (string, error) {
	var in = blob(plain)
	var salt = blob(entropy)
	var out dataBlob
	ok, _, _ := procProtect.Call(
		uintptr(unsafe.Pointer(&in)),
		0,
		uintptr(unsafe.Pointer(&salt)),
		0,
		0,
		windows.CRYPTPROTECT_UI_FORBIDDEN,
		uintptr(unsafe.Pointer(&out)),
	)
	if ok == 0 {
		return "", errProtectFailed
	}
	defer procLocalFree.Call(uintptr(unsafe.Pointer(out.data)))
	return base64.RawStdEncoding.EncodeToString(unsafe.Slice(out.data, out.size)), nil
}

func unprotect(text string) ([]byte, error) {
	raw, err := base64.RawStdEncoding.DecodeString(text)
	if err != nil {
		return nil, err
	}
	var in = blob(raw)
	var salt = blob(entropy)
	var out dataBlob
	ok, _, _ := procUnprotect.Call(
		uintptr(unsafe.Pointer(&in)),
		0,
		uintptr(unsafe.Pointer(&salt)),
		0,
		0,
		windows.CRYPTPROTECT_UI_FORBIDDEN,
		uintptr(unsafe.Pointer(&out)),
	)
	if ok == 0 {
		return nil, errProtectFailed
	}
	defer procLocalFree.Call(uintptr(unsafe.Pointer(out.data)))
	return append([]byte(nil), unsafe.Slice(out.data, out.size)...), nil
}
