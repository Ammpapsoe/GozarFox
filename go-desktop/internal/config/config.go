package config

import (
	"encoding/json"
	"os"
	"path/filepath"
	"strings"
)

type Account struct {
	Email     string `json:"email"`
	UID       string `json:"uid"`
	Access    string `json:"access"`
	Refresh   string `json:"refresh"`
	ProxyPass string `json:"proxy_pass,omitempty"`
	ExpiresAt int64  `json:"expires_at"`
	Limit     int64  `json:"limit,omitempty"`
	Remaining int64  `json:"remaining,omitempty"`
	Reset     string `json:"reset,omitempty"`
}

func (a Account) ID() string {
	if a.UID != "" {
		return "uid:" + a.UID
	}
	if a.Email != "" {
		return "email:" + strings.ToLower(a.Email)
	}
	return ""
}

type Config struct {
	Email           string    `json:"email"`
	UID             string    `json:"uid"`
	Access          string    `json:"access"`
	Refresh         string    `json:"refresh"`
	ExpiresAt       int64     `json:"expires_at"`
	Accounts        []Account `json:"accounts,omitempty"`
	Server          string    `json:"server"`
	Language        string    `json:"language"`
	Theme           string    `json:"theme"`
	Recents         bool      `json:"recents"`
	LastServer      string    `json:"last_server"`
	SettingsVersion int       `json:"settings_version"`
	FullTunnel      bool      `json:"full_tunnel"`
	KillSwitch      bool      `json:"kill_switch"`
	SplitTunnel     bool      `json:"split_tunnel"`
	BypassLAN       bool      `json:"bypass_lan"`
	WarpUDP         bool      `json:"warp_udp"`
	Bypass          []string  `json:"bypass"`
	Chain           []string  `json:"chain"`
	Port            int       `json:"port"`
}

func Dir() string {
	base, err := os.UserConfigDir()
	if err != nil {
		base = os.Getenv("HOME")
	}
	dir := filepath.Join(base, "Gozar")
	os.MkdirAll(dir, 0o700)
	return dir
}

func path() string { return filepath.Join(Dir(), "config.json") }

func defaults() Config {
	return Config{FullTunnel: true, KillSwitch: true, SplitTunnel: true, Theme: "system", SettingsVersion: 1, Port: 10810}
}

func (c Config) ActiveAccount() Account {
	a := Account{Email: c.Email, UID: c.UID, Access: c.Access, Refresh: c.Refresh, ExpiresAt: c.ExpiresAt}
	for _, saved := range c.Accounts {
		if saved.ID() == a.ID() && a.ID() != "" {
			a.Limit, a.Remaining, a.Reset, a.ProxyPass = saved.Limit, saved.Remaining, saved.Reset, saved.ProxyPass
			break
		}
	}
	return a
}

func (c Config) WithCurrentAccount() Config {
	c.Accounts = append([]Account(nil), c.Accounts...)
	current := c.ActiveAccount()
	if current.ID() == "" || (current.Refresh == "" && current.Access == "") {
		return c
	}
	found := false
	filtered := make([]Account, 0, len(c.Accounts)+1)
	for _, saved := range c.Accounts {
		if saved.ID() == current.ID() || strings.EqualFold(saved.Email, current.Email) {
			if !found {
				filtered = append(filtered, current)
				found = true
			}
		} else {
			filtered = append(filtered, saved)
		}
	}
	if !found {
		filtered = append(filtered, current)
	}
	c.Accounts = filtered
	return c
}

func (c Config) PutAccount(account Account) Config {
	c = c.WithCurrentAccount()
	filtered := make([]Account, 0, len(c.Accounts)+1)
	for _, saved := range c.Accounts {
		if saved.ID() != account.ID() && !strings.EqualFold(saved.Email, account.Email) {
			filtered = append(filtered, saved)
		}
	}
	c.Accounts = append(filtered, account)
	c.Email, c.UID, c.Access, c.Refresh, c.ExpiresAt = account.Email, account.UID, account.Access, account.Refresh, account.ExpiresAt
	return c
}

func (c Config) SwitchAccount(id string) (Config, bool) {
	c = c.WithCurrentAccount()
	for _, account := range c.Accounts {
		if account.ID() == id && (account.Refresh != "" || account.Access != "") {
			c.Email, c.UID, c.Access, c.Refresh, c.ExpiresAt = account.Email, account.UID, account.Access, account.Refresh, account.ExpiresAt
			return c, true
		}
	}
	return c, false
}

func (c Config) RemoveActive() Config {
	c = c.WithCurrentAccount()
	id := c.ActiveAccount().ID()
	filtered := make([]Account, 0, len(c.Accounts))
	for _, account := range c.Accounts {
		if account.ID() != id {
			filtered = append(filtered, account)
		}
	}
	c.Accounts = filtered
	c.Email, c.UID, c.Access, c.Refresh, c.ExpiresAt = "", "", "", "", 0
	return c
}

func Load() Config {
	fresh := defaults()
	data, err := os.ReadFile(path())
	if err != nil {
		return fresh
	}
	var loaded Config
	if json.Unmarshal(data, &loaded) != nil {
		return fresh
	}
	loaded.unsealTokens()
	if loaded.Port == 0 {
		loaded.Port = fresh.Port
	}
	if loaded.SettingsVersion < 1 {
		loaded.Chain = nil
		loaded.Bypass = nil
		loaded.BypassLAN = false
		loaded.SettingsVersion = 1
	}
	if loaded.SettingsVersion < 2 {
		loaded.KillSwitch = true
		loaded.SplitTunnel = true
	}
	if loaded.Theme != "light" && loaded.Theme != "dark" {
		loaded.Theme = "system"
	}
	return loaded.WithCurrentAccount()
}

func Save(current Config) error {
	final := current.WithCurrentAccount()
	final.sealTokens()
	data, err := json.MarshalIndent(final, "", "  ")
	if err != nil {
		return err
	}
	file, err := os.CreateTemp(Dir(), ".config-*")
	if err != nil {
		return err
	}
	name := file.Name()
	defer os.Remove(name)
	if err := file.Chmod(0o600); err != nil {
		file.Close()
		return err
	}
	if _, err := file.Write(data); err != nil {
		file.Close()
		return err
	}
	if err := file.Sync(); err != nil {
		file.Close()
		return err
	}
	if err := file.Close(); err != nil {
		return err
	}
	return os.Rename(name, path())
}

func (c Config) SignedIn() bool { return c.Email != "" && (c.Refresh != "" || c.Access != "") }
