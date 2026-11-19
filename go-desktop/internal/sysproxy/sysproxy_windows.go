//go:build windows

package sysproxy

import (
	"strconv"
	"strings"

	"golang.org/x/sys/windows/registry"
)

const settingsKey = `Software\Microsoft\Windows\CurrentVersion\Internet Settings`

func reg(name, value string) error {
	key, _, err := registry.CreateKey(registry.CURRENT_USER, settingsKey, registry.SET_VALUE|registry.QUERY_VALUE)
	if err != nil {
		return err
	}
	defer key.Close()
	return key.SetStringValue(name, value)
}

func dword(name, value string) error {
	key, _, err := registry.CreateKey(registry.CURRENT_USER, settingsKey, registry.SET_VALUE|registry.QUERY_VALUE)
	if err != nil {
		return err
	}
	defer key.Close()
	number, err := strconv.ParseUint(value, 10, 32)
	if err != nil {
		return err
	}
	return key.SetDWordValue(name, uint32(number))
}

func Current() int {
	key, err := registry.OpenKey(registry.CURRENT_USER, settingsKey, registry.QUERY_VALUE)
	if err != nil {
		return 0
	}
	defer key.Close()
	enabled, _, err := key.GetIntegerValue("ProxyEnable")
	if err != nil || enabled == 0 {
		return 0
	}
	server, _, err := key.GetStringValue("ProxyServer")
	if err != nil {
		return 0
	}
	index := strings.LastIndex(server, ":")
	if index < 0 {
		return 0
	}
	port, err := strconv.Atoi(strings.TrimSpace(server[index+1:]))
	if err != nil {
		return 0
	}
	return port
}
