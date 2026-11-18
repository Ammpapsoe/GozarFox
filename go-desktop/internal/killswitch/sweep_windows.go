package killswitch

import (
	"os"
	"path/filepath"
)

// staleOS reports whether an earlier run left the Windows firewall in the
// blocked-by-default state, so that Sweep can put it back.
//
// The marker file is the only signal. It is written by the enable script
// *before* it touches the profile, so its presence means the profile was about
// to be changed and the process may have died before restoring it; its absence
// means the kill switch was never enabled on this machine and there is nothing
// to undo.
//
// This used to also ask PowerShell to count the Gozar-Tunnel rules. That call
// ran on every single start, even for a user who had never enabled the kill
// switch, and it was the raw exec that painted a console window over the app.
// It was also wrong in both directions: an administrator can delete the rules
// while the marker stays (the profile is still Block and must be restored), and
// the rules can outlive a lost marker. The marker alone is the honest signal.
//
// A previous version of this file looked for the marker under
// C:\Windows\Temp while the enable script wrote it to %TEMP% (the user's
// temporary folder). The paths never matched, so the marker was never seen and
// the sweep fell through to the PowerShell probe every time. markerPath now
// derives the location the same way the script does, so the two cannot drift
// apart again.
func staleOS() (bool, error) {
	_, err := os.Stat(markerPath())
	if err == nil {
		return true, nil
	}
	if os.IsNotExist(err) {
		return false, nil
	}
	return false, err
}

// markerPath is the file the enable script writes and the disable script
// removes. The script builds it with Join-Path $env:TEMP, so this must resolve
// the same directory rather than a hard-coded one.
func markerPath() string {
	return filepath.Join(os.TempDir(), markerName)
}

// markerName is the fixed file name the scripts use.
const markerName = "gozar-killswitch-previous.txt"
