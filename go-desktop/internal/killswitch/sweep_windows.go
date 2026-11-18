package killswitch

import (
	"os"
	"strings"
)

// staleOS reports whether an earlier run left the Windows firewall in the
// blocked-by-default state. The marker file it writes before changing the
// profile is the reliable signal: the rules themselves can be edited by an
// administrator, but a left-over marker with no rules means the process died
// between the two steps and the profile must still be restored.
func staleOS() (bool, error) {
	if _, err := os.Stat(markerPath()); err == nil {
		return true, nil
	}
	out, err := commandOutput("powershell", "-NoProfile", "-NonInteractive", "-Command",
		"(Get-NetFirewallRule -DisplayName 'Gozar-Tunnel' -ErrorAction SilentlyContinue | Measure-Object).Count")
	if err != nil {
		return false, nil
	}
	return strings.TrimSpace(out) != "0" && strings.TrimSpace(out) != "", nil
}

// markerPath is the file the enable script writes; its name is fixed there, so
// it is repeated here rather than passed around.
func markerPath() string {
	return `C:\Windows\Temp\gozar-killswitch-previous.txt`
}
