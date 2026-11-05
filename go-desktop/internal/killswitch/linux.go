//go:build linux

package killswitch

import (
	"fmt"
	"strings"
)

func linuxRules(cfg Config) string {
	iface := strings.ReplaceAll(cfg.Interface, `"`, `\"`)
	v4, v6 := proxyLists(cfg)
	ir4, ir6 := irPrefixes()
	var b strings.Builder
	b.WriteString("table inet gozar {\n")
	if cfg.AllowDirect {
		b.WriteString("\tset ir4 { type ipv4_addr; flags interval; elements = { " + ir4 + " } }\n")
		b.WriteString("\tset ir6 { type ipv6_addr; flags interval; elements = { " + ir6 + " } }\n")
	}
	b.WriteString("\tchain output {\n\t\ttype filter hook output priority filter; policy drop;\n")
	b.WriteString("\t\toifname \"lo\" accept\n")
	b.WriteString("\t\toifname \"" + iface + "\" accept\n")
	if v4 != "" {
		b.WriteString("\t\tip daddr { " + v4 + " } accept\n")
	}
	if v6 != "" {
		b.WriteString("\t\tip6 daddr { " + v6 + " } accept\n")
	}
	b.WriteString("\t\tudp sport 68 udp dport 67 accept\n")
	b.WriteString("\t\tudp sport 546 udp dport 547 accept\n")
	b.WriteString("\t\tip daddr { 10.0.0.0/8, 172.16.0.0/12, 192.168.0.0/16, 169.254.0.0/16 } accept\n")
	b.WriteString("\t\tip6 daddr { fe80::/10, fc00::/7 } accept\n")
	if cfg.AllowDirect {
		b.WriteString("\t\tip daddr @ir4 accept\n\t\tip6 daddr @ir6 accept\n")
	}
	b.WriteString("\t\tcounter drop\n\t}\n}\n")
	return b.String()
}

func enableLinux(cfg Config) error {
	runIgnore("nft", "delete", "table", "inet", "gozar")
	if err := runStdin("nft", linuxRules(cfg), "-f", "-"); err != nil {
		return fmt.Errorf("enable Linux kill switch: %w", err)
	}
	return nil
}

func disableLinux() error {
	runIgnore("nft", "delete", "table", "inet", "gozar")
	return nil
}

func enableOS(cfg Config) error { return enableLinux(cfg) }
func disableOS() error { return disableLinux() }
