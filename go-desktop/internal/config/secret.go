package config

import "strings"

const sealedPrefix = "enc1:"

func seal(value string) string {
	if value == "" || strings.HasPrefix(value, sealedPrefix) {
		return value
	}
	packed, err := protect([]byte(value))
	if err != nil {
		return value
	}
	return sealedPrefix + packed
}

func unseal(value string) string {
	if !strings.HasPrefix(value, sealedPrefix) {
		return value
	}
	plain, err := unprotect(strings.TrimPrefix(value, sealedPrefix))
	if err != nil {
		return ""
	}
	return string(plain)
}

// New security-sensitive values never fall back to plaintext, unlike legacy
// account tokens. This also authenticates cached official relay locations.
func sealStrict(value string) string {
	if value == "" || strings.HasPrefix(value, sealedPrefix) {
		return value
	}
	packed, err := protect([]byte(value))
	if err != nil || packed == "" {
		return ""
	}
	return sealedPrefix + packed
}

func unsealStrict(value string) string {
	if !strings.HasPrefix(value, sealedPrefix) {
		return ""
	}
	return unseal(value)
}

func (c *Config) sealTokens() {
	c.Access = seal(c.Access)
	c.Refresh = seal(c.Refresh)
	c.ServerCache = sealStrict(c.ServerCache)
	c.RelayCache = sealStrict(c.RelayCache)
	for index := range c.Accounts {
		c.Accounts[index].Access = seal(c.Accounts[index].Access)
		c.Accounts[index].Refresh = seal(c.Accounts[index].Refresh)
		c.Accounts[index].ProxyPass = sealStrict(c.Accounts[index].ProxyPass)
	}
}

func (c *Config) unsealTokens() {
	c.Access = unseal(c.Access)
	c.Refresh = unseal(c.Refresh)
	c.ServerCache = unsealStrict(c.ServerCache)
	c.RelayCache = unsealStrict(c.RelayCache)
	for index := range c.Accounts {
		c.Accounts[index].Access = unseal(c.Accounts[index].Access)
		c.Accounts[index].Refresh = unseal(c.Accounts[index].Refresh)
		c.Accounts[index].ProxyPass = unsealStrict(c.Accounts[index].ProxyPass)
	}
}
