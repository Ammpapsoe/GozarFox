//go:build windows

package killswitch

import (
	"fmt"
	"strings"

	"github.com/bardia049b-stack/Gozar1/go-desktop/internal/geo"
)

func psQuote(value string) string {
	return "'" + strings.ReplaceAll(value, "'", "''") + "'"
}

func psArray(values []string) string {
	quoted := make([]string, 0, len(values))
	for _, value := range values {
		quoted = append(quoted, psQuote(value))
	}
	return "@(" + strings.Join(quoted, ",") + ")"
}

func windowsRules(cfg Config) string {
	var b strings.Builder
	b.WriteString("$ErrorActionPreference='Stop'\n")
	b.WriteString("Get-NetFirewallRule -DisplayName 'Gozar-*' -ErrorAction SilentlyContinue | Remove-NetFirewallRule\n")
	b.WriteString("$previous = Get-NetFirewallProfile | ForEach-Object { $_.Name + '=' + $_.DefaultOutboundAction }\n")
	b.WriteString("$previous -join ',' | Set-Content -Path (Join-Path $env:TEMP 'gozar-killswitch-previous.txt') -Encoding ASCII\n")
	b.WriteString("New-NetFirewallRule -DisplayName 'Gozar-Tunnel' -Direction Outbound -Action Allow -InterfaceAlias " + psQuote(cfg.Interface) + " -Profile Any | Out-Null\n")
	v4, v6 := proxyLists(cfg)
	var proxy []string
	if v4 != "" {
		proxy = append(proxy, strings.Split(v4, ", ")...)
	}
	if v6 != "" {
		proxy = append(proxy, strings.Split(v6, ", ")...)
	}
	if len(proxy) > 0 {
		b.WriteString("New-NetFirewallRule -DisplayName 'Gozar-Proxy' -Direction Outbound -Action Allow -RemoteAddress " + psArray(proxy) + " -Profile Any | Out-Null\n")
	}
	b.WriteString("New-NetFirewallRule -DisplayName 'Gozar-DHCP' -Direction Outbound -Action Allow -Protocol UDP -LocalPort 68 -RemotePort 67 -Profile Any | Out-Null\n")
	b.WriteString("New-NetFirewallRule -DisplayName 'Gozar-DHCP6' -Direction Outbound -Action Allow -Protocol UDP -LocalPort 546 -RemotePort 547 -Profile Any | Out-Null\n")
	b.WriteString("New-NetFirewallRule -DisplayName 'Gozar-LAN' -Direction Outbound -Action Allow -RemoteAddress @('10.0.0.0/8','172.16.0.0/12','192.168.0.0/16','169.254.0.0/16','fe80::/10','fc00::/7') -Profile Any | Out-Null\n")
	if cfg.AllowDirect {
		var v4p, v6p []string
		for _, prefix := range geo.IranianPrefixes() {
			if prefix.Addr().Is4() {
				v4p = append(v4p, prefix.String())
			} else {
				v6p = append(v6p, prefix.String())
			}
		}
		all := append(v4p, v6p...)
		for start, index := 0, 0; start < len(all); start += 400 {
			end := start + 400
			if end > len(all) {
				end = len(all)
			}
			b.WriteString(fmt.Sprintf("New-NetFirewallRule -DisplayName 'Gozar-IR-%d' -Direction Outbound -Action Allow -RemoteAddress %s -Profile Any | Out-Null\n", index, psArray(all[start:end])))
			index++
		}
	}
	b.WriteString("Set-NetFirewallProfile -Profile Domain,Private,Public -DefaultOutboundAction Block\n")
	return b.String()
}

func windowsEnableScript(cfg Config) string { return windowsRules(cfg) }

func windowsDisableScript() string {
	return `$ErrorActionPreference='Stop'
Get-NetFirewallRule -DisplayName 'Gozar-*' -ErrorAction SilentlyContinue | Remove-NetFirewallRule
$path = Join-Path $env:TEMP 'gozar-killswitch-previous.txt'
$previous = @{}
if (Test-Path $path) {
  (Get-Content $path -Raw) -split ',' | ForEach-Object {
    $parts = $_ -split '='
    if ($parts.Count -eq 2) { $previous[$parts[0]] = $parts[1] }
  }
  Remove-Item $path -Force
}
foreach ($profile in @('Domain','Private','Public')) {
  $action = 'Allow'
  if ($previous.ContainsKey($profile)) { $action = $previous[$profile] }
  Set-NetFirewallProfile -Profile $profile -DefaultOutboundAction $action
}
`
}

func enableWindows(cfg Config) error {
	if err := run("powershell.exe", "-NoProfile", "-NonInteractive", "-Command", windowsEnableScript(cfg)); err != nil {
		return fmt.Errorf("enable Windows kill switch: %w", err)
	}
	return nil
}

func disableWindows() error {
	if err := run("powershell.exe", "-NoProfile", "-NonInteractive", "-Command", windowsDisableScript()); err != nil {
		return fmt.Errorf("disable Windows kill switch: %w", err)
	}
	return nil
}

func enableOS(cfg Config) error { return enableWindows(cfg) }
func disableOS() error { return disableWindows() }
