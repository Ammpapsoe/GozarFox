//go:build darwin

package killswitch

import (
	"fmt"
	"os"
	"strings"
)

var pfWasEnabled bool

func darwinRules(cfg Config) string {
	iface := strings.ReplaceAll(cfg.Interface, `"`, "")
	v4, v6 := proxyLists(cfg)
	ir4, ir6 := irPrefixes()
	var b strings.Builder
	if cfg.AllowDirect {
		b.WriteString("table <gozar_ir4> persist { " + ir4 + " }\n")
		b.WriteString("table <gozar_ir6> persist { " + ir6 + " }\n")
	}
	b.WriteString("pass out quick on lo0 all\n")
	b.WriteString("pass out quick on " + iface + " all\n")
	if v4 != "" {
		b.WriteString("pass out quick inet proto { tcp udp } from any to { " + v4 + " }\n")
	}
	if v6 != "" {
		b.WriteString("pass out quick inet6 proto { tcp udp } from any to { " + v6 + " }\n")
	}
	b.WriteString("pass out quick inet proto udp from any port 68 to any port 67\n")
	b.WriteString("pass out quick inet6 proto udp from any port 546 to any port 547\n")
	b.WriteString("pass out quick inet from any to { 10.0.0.0/8, 172.16.0.0/12, 192.168.0.0/16, 169.254.0.0/16 }\n")
	b.WriteString("pass out quick inet6 from any to { fe80::/10, fc00::/7 }\n")
	if cfg.AllowDirect {
		b.WriteString("pass out quick inet from any to <gozar_ir4>\n")
		b.WriteString("pass out quick inet6 from any to <gozar_ir6>\n")
	}
	b.WriteString("block drop out quick all\n")
	return b.String()
}

func enableDarwin(cfg Config) error {
	info, _ := commandOutput("pfctl", "-s", "info")
	pfWasEnabled = strings.Contains(info, "Status: Enabled")
	content := "anchor \"gozar\" {\n" + darwinRules(cfg) + "}\ninclude \"/etc/pf.conf\"\n"
	path, err := tempFile("gozar-pf-*.conf", content)
	if err != nil {
		return err
	}
	defer os.Remove(path)
	if err := run("pfctl", "-f", path); err != nil {
		return fmt.Errorf("enable macOS kill switch: %w", err)
	}
	runIgnore("pfctl", "-e")
	return nil
}

func disableDarwin() error {
	runIgnore("pfctl", "-a", "gozar", "-F", "all")
	runIgnore("pfctl", "-f", "/etc/pf.conf")
	if !pfWasEnabled {
		runIgnore("pfctl", "-d")
	}
	return nil
}

func enableOS(cfg Config) error { return enableDarwin(cfg) }
func disableOS() error { return disableDarwin() }
