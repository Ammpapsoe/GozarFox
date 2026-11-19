package engine

import (
	"bufio"
	"fmt"
	"io"
	"os"
	"os/exec"
	"strings"
	"testing"
	"time"
)

func TestFakeDNSFileJournalCrashChild(t *testing.T) {
	dir := os.Getenv("GOZAR_FAKE_DNS_CRASH_DIR")
	if dir == "" {
		t.Skip("child helper only")
	}
	j := openTestDNSFiles(t, dir)
	f, err := newJournalFakeDNS(j, nil)
	if err != nil {
		t.Fatal(err)
	}
	stable, err := f.address("stable.example")
	if err != nil {
		t.Fatal(err)
	}
	j.syncFile = func(file *os.File) error {
		if file == j.anchor {
			// The data file is flushed; the checkpoint has been written but
			// this process has not yet flushed it or returned an address.
			fmt.Println("STAGED " + stable.String())
			_, _ = io.Copy(io.Discard, os.Stdin)
		}
		return file.Sync()
	}
	_, _ = f.address("uncertain.example")
}

func TestFakeDNSFileJournalProcessCrash(t *testing.T) {
	dir := t.TempDir()
	exe, err := os.Executable()
	if err != nil {
		t.Fatal(err)
	}
	cmd := exec.Command(exe, "-test.run=^TestFakeDNSFileJournalCrashChild$")
	cmd.Env = append(os.Environ(), "GOZAR_FAKE_DNS_CRASH_DIR="+dir)
	stdin, err := cmd.StdinPipe()
	if err != nil {
		t.Fatal(err)
	}
	stdout, err := cmd.StdoutPipe()
	if err != nil {
		t.Fatal(err)
	}
	cmd.Stderr = os.Stderr
	if err := cmd.Start(); err != nil {
		t.Fatal(err)
	}
	finished := false
	t.Cleanup(func() {
		_ = stdin.Close()
		if !finished {
			_ = cmd.Process.Kill()
			_ = cmd.Wait()
		}
	})
	ready := make(chan string, 1)
	go func() {
		scanner := bufio.NewScanner(stdout)
		if scanner.Scan() {
			ready <- scanner.Text()
		} else {
			ready <- ""
		}
	}()
	var stable string
	select {
	case line := <-ready:
		if !strings.HasPrefix(line, "STAGED ") {
			t.Fatalf("child failed to stage record: %q", line)
		}
		stable = strings.TrimPrefix(line, "STAGED ")
	case <-time.After(20 * time.Second):
		t.Fatal("child did not reach crash checkpoint")
	}
	if err := cmd.Process.Kill(); err != nil {
		t.Fatal(err)
	}
	_ = cmd.Wait()
	finished = true
	_ = stdin.Close()
	reopened, err := newJournalFakeDNS(openTestDNSFiles(t, dir), nil)
	if err != nil {
		t.Fatal(err)
	}
	got, err := reopened.address("stable.example")
	if err != nil || got.String() != stable {
		t.Fatalf("durable identity changed after crash: %s, %v", got, err)
	}
	uncertain, err := reopened.address("uncertain.example")
	if err != nil || uncertain == got {
		t.Fatal("uncertain allocation reused stable identity", err)
	}
	next, err := reopened.address("next.example")
	if err != nil || next == got || next == uncertain {
		t.Fatal("recovery reused an address", err)
	}
}
