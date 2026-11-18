package killswitch

import (
	"os"
	"path/filepath"
	"strings"
	"testing"
)

// TestMarkerPathIsUnderTheUsersTemp is the regression test for the sweep that
// never fired: markerPath used to return C:\Windows\Temp\..., while the enable
// script wrote the marker to %TEMP%. An ordinary user cannot even write to
// C:\Windows\Temp, so the marker was never found, staleOS fell through to a
// PowerShell probe on every start, and that probe was the console window that
// flashed over the app.
func TestMarkerPathIsUnderTheUsersTemp(t *testing.T) {
	temp := os.TempDir()
	got := markerPath()
	if filepath.Dir(got) != temp {
		t.Fatalf("marker path %q is not in the user's temp directory %q", got, temp)
	}
	if filepath.Base(got) != markerName {
		t.Fatalf("marker file name changed: %q, want %q", filepath.Base(got), markerName)
	}
	if strings.Contains(strings.ToLower(got), `windows\temp`) {
		t.Fatalf("marker path %q points at the system temp directory again", got)
	}
}

// TestScriptsUseTheSameMarkerPathAsTheSweep keeps the two halves of the kill
// switch honest. The sweep looks for the marker at markerPath; if either script
// wrote or removed it somewhere else, the sweep would silently stop working
// again (or worse, restore a stale profile from another file).
func TestScriptsUseTheSameMarkerPathAsTheSweep(t *testing.T) {
	want := psQuote(markerPath())

	enable := windowsEnableScript(Config{Interface: "Gozar", Proxy: nil})
	if !strings.Contains(enable, want) {
		t.Fatalf("the enable script does not write the marker at %s:\n%s", want, enable)
	}

	disable := windowsDisableScript()
	if !strings.Contains(disable, "$path = "+want) {
		t.Fatalf("the disable script does not read the marker from %s:\n%s", want, disable)
	}
	if !strings.Contains(disable, "Set-NetFirewallProfile") {
		t.Fatal("the disable script no longer restores the profile default action")
	}
}

// TestNoScriptKeepsAMarkerPlaceholder fails if a template placeholder ever
// reaches PowerShell verbatim, which would make the scripts look right in Go
// while doing nothing on the machine.
func TestNoScriptKeepsAMarkerPlaceholder(t *testing.T) {
	for name, script := range map[string]string{
		"enable":  windowsEnableScript(Config{Interface: "Gozar"}),
		"disable": windowsDisableScript(),
	} {
		if strings.Contains(script, "@MARKERPATH@") {
			t.Fatalf("the %s script still contains the marker placeholder", name)
		}
	}
}

// TestStaleOSIsQuietWhenNoMarkerExists is the behavioural half of the fix: with
// no marker on disk, the sweep must report "nothing to do" and — crucially —
// must do it without running anything. The PowerShell probe is gone, so this
// test simply asserts the answer; there is no process left to observe.
func TestStaleOSIsQuietWhenNoMarkerExists(t *testing.T) {
	// Point the temp directory at a fresh directory so a marker left by a
	// previous run on this machine cannot make the test pass by accident.
	previous := os.TempDir()
	dir := t.TempDir()
	if err := os.Setenv("TMP", dir); err != nil {
		t.Fatal(err)
	}
	if err := os.Setenv("TEMP", dir); err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() {
		_ = os.Setenv("TMP", previous)
		_ = os.Setenv("TEMP", previous)
	})

	found, err := staleOS()
	if err != nil {
		t.Fatalf("staleOS: %v", err)
	}
	if found {
		t.Fatal("staleOS reported a stale kill switch with no marker on disk")
	}

	// And with a marker present it must report one, whatever its contents.
	if err := os.WriteFile(markerPath(), []byte("Domain=Allow,Private=Allow,Public=Block"), 0o600); err != nil {
		t.Fatal(err)
	}
	found, err = staleOS()
	if err != nil {
		t.Fatalf("staleOS: %v", err)
	}
	if !found {
		t.Fatal("staleOS did not see the marker it just wrote")
	}
}
