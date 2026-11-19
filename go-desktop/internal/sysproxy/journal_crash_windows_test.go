//go:build windows

package sysproxy

import (
	"bufio"
	"fmt"
	"os"
	"os/exec"
	"strings"
	"testing"
	"time"

	"golang.org/x/sys/windows/registry"
)

const proxyCrashPrefix = `Software\Gozar-Proxy-Crash-Test-`

// Helper is only activated by the parent test and only opens explicitly
// named disposable test keys; it never touches actual Internet Settings.
func TestRegistryJournalCrashChild(t *testing.T) {
	if os.Getenv("GOZAR_PROXY_CRASH_CHILD") != "1" {
		t.Skip("child process only")
	}
	backupPath := os.Getenv("GOZAR_TEST_BACKUP_KEY")
	settingsPath := os.Getenv("GOZAR_TEST_SETTINGS_KEY")
	if !strings.HasPrefix(backupPath, proxyCrashPrefix) || !strings.HasPrefix(settingsPath, proxyCrashPrefix) {
		t.Fatal("invalid isolated test key")
	}
	backup, err := registry.OpenKey(registry.CURRENT_USER, backupPath, registry.ALL_ACCESS)
	if err != nil {
		t.Fatal(err)
	}
	defer backup.Close()
	settings, err := registry.OpenKey(registry.CURRENT_USER, settingsPath, registry.ALL_ACCESS)
	if err != nil {
		t.Fatal(err)
	}
	defer settings.Close()
	if err := activateProxy(registryProxyStore{key: settings}, registryJournal{key: backup}, 23456); err != nil {
		t.Fatal(err)
	}
	fmt.Println("BACKUP_AND_APPLY_COMPLETE")
	// The parent terminates this process without running cleanup.
	time.Sleep(time.Minute)
	t.Fatal("parent did not terminate child")
}

func TestRegistryJournalRecoversAfterProcessKilled(t *testing.T) {
	base := fmt.Sprintf("%s%d-%d", proxyCrashPrefix, os.Getpid(), time.Now().UnixNano())
	backupPath, settingsPath := base+"-backup", base+"-settings"
	create := func(path string) registry.Key {
		key, existed, err := registry.CreateKey(registry.CURRENT_USER, path, registry.ALL_ACCESS)
		if err != nil || existed {
			t.Fatalf("create isolated key: existed=%t err=%v", existed, err)
		}
		t.Cleanup(func() {
			key.Close()
			if err := registry.DeleteKey(registry.CURRENT_USER, path); err != nil {
				t.Errorf("delete isolated key: %v", err)
			}
		})
		return key
	}
	backup, key := create(backupPath), create(settingsPath)
	store := registryProxyStore{key: key}
	original := map[string]ProxyValue{
		"ProxyEnable": number(1), "ProxyServer": text("original.invalid:8080"),
		"ProxyOverride": text("intranet"), "AutoConfigURL": missing(), "AutoDetect": number(1),
	}
	for name, value := range original {
		if err := store.Write(name, value); err != nil {
			t.Fatal(err)
		}
	}
	command := exec.Command(os.Args[0], "-test.run=^TestRegistryJournalCrashChild$", "-test.timeout=45s")
	command.Env = append(os.Environ(), "GOZAR_PROXY_CRASH_CHILD=1", "GOZAR_TEST_BACKUP_KEY="+backupPath, "GOZAR_TEST_SETTINGS_KEY="+settingsPath)
	out, err := command.StdoutPipe()
	if err != nil {
		t.Fatal(err)
	}
	command.Stderr = os.Stderr
	if err := command.Start(); err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() { _ = command.Process.Kill(); _ = command.Wait() })
	lines := make(chan string, 1)
	go func() {
		scanner := bufio.NewScanner(out)
		if scanner.Scan() {
			lines <- scanner.Text()
		} else {
			lines <- ""
		}
	}()
	select {
	case line := <-lines:
		if line != "BACKUP_AND_APPLY_COMPLETE" {
			t.Fatalf("child did not finish activation: %q", line)
		}
	case <-time.After(20 * time.Second):
		t.Fatal("child activation timed out")
	}
	if err := command.Process.Kill(); err != nil {
		t.Fatal(err)
	}
	_ = command.Wait()
	journal := registryJournal{key: backup}
	if snapshot, err := journal.Load(); err != nil || snapshot == nil {
		t.Fatalf("backup missing after process termination: %v", err)
	}
	if current, err := store.Read("ProxyServer"); err != nil || current != desired(23456)["ProxyServer"] {
		t.Fatalf("child did not install expected test state: %#v, %v", current, err)
	}
	if err := recoverProxy(store, journal); err != nil {
		t.Fatal(err)
	}
	for name, want := range original {
		got, err := store.Read(name)
		if err != nil || got != want {
			t.Fatalf("%s was not restored: %#v, %v", name, got, err)
		}
	}
	if snapshot, err := journal.Load(); err != nil || snapshot != nil {
		t.Fatal("backup was not cleared", err)
	}
}
