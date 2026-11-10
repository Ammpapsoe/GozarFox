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

func (c *Config) sealTokens() {
	c.Access = seal(c.Access)
	c.Refresh = seal(c.Refresh)
	for index := range c.Accounts {
		c.Accounts[index].Access = seal(c.Accounts[index].Access)
		c.Accounts[index].Refresh = seal(c.Accounts[index].Refresh)
		// Proxy passes are new: unlike legacy account tokens, never fall back
		// to plaintext if the OS/keystore cannot encrypt this short-lived secret.
		value := c.Accounts[index].ProxyPass
		if value != "" && !strings.HasPrefix(value, sealedPrefix) {
			packed, err := protect([]byte(value))
			if err != nil || packed == "" {
				value = ""
			} else {
				value = sealedPrefix + packed
			}
		}
		c.Accounts[index].ProxyPass = value
	}
}

func (c *Config) unsealTokens() {
	c.Access = unseal(c.Access)
	c.Refresh = unseal(c.Refresh)
	for index := range c.Accounts {
		c.Accounts[index].Access = unseal(c.Accounts[index].Access)
		c.Accounts[index].Refresh = unseal(c.Accounts[index].Refresh)
		if strings.HasPrefix(c.Accounts[index].ProxyPass, sealedPrefix) {
			c.Accounts[index].ProxyPass = unseal(c.Accounts[index].ProxyPass)
		} else {
			// Reject injected or legacy plaintext pass values.
			c.Accounts[index].ProxyPass = ""
		}
	}
}
