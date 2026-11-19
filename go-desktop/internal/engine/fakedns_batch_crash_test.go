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

// Simulate process termination in a real multi-record batch. This does not
// simulate sudden power loss or storage hardware that lies about flushes.
func TestFakeDNSBatchCrashChild(t *testing.T) {
	dir := os.Getenv("GOZAR_BATCH_CRASH_DIR")
	stage := os.Getenv("GOZAR_BATCH_CRASH_STAGE")
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
		if stage == "history-flushed" && file == j.history {
			if err := file.Sync(); err != nil {
				return err
			}
			fmt.Println("STAGED " + stable.String())
			_, _ = io.Copy(io.Discard, os.Stdin)
		}
		if stage == "anchor-unflushed" && file == j.anchor {
			fmt.Println("STAGED " + stable.String())
			_, _ = io.Copy(io.Discard, os.Stdin)
		}
		return file.Sync()
	}
	names := make([]string, 16)
	for i := range names {
		names[i] = fmt.Sprintf("batch-%02d.example", i)
	}
	if err := j.AppendBatch(names); err != nil {
		t.Fatal(err)
	}
	t.Fatal("parent did not terminate child before batch completion")
}

func TestFakeDNSBatchProcessCrashRecovery(t *testing.T) {
	for _, stage := range []string{"history-flushed", "anchor-unflushed"} {
		t.Run(stage, func(t *testing.T) {
			dir := t.TempDir()
			exe, err := os.Executable()
			if err != nil {
				t.Fatal(err)
			}
			cmd := exec.Command(exe, "-test.run=^TestFakeDNSBatchCrashChild$")
			cmd.Env = append(os.Environ(), "GOZAR_BATCH_CRASH_DIR="+dir, "GOZAR_BATCH_CRASH_STAGE="+stage)
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
					t.Fatalf("child did not stage batch: %q", line)
				}
				stable = strings.TrimPrefix(line, "STAGED ")
			case <-time.After(20 * time.Second):
				t.Fatal("child did not reach stage")
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
				t.Fatalf("stable identity changed: %s %v", got, err)
			}
			for i := 0; i < 16; i++ {
				name := fmt.Sprintf("batch-%02d.example", i)
				want := fakeAddressAt(reopened.start, uint32(i+1))
				got, err := reopened.address(name)
				if err != nil || got != want {
					t.Fatalf("batch identity %d: got %s want %s: %v", i, got, want, err)
				}
			}
			got, err = reopened.address("later.example")
			want := fakeAddressAt(reopened.start, 17)
			if err != nil || got != want {
				t.Fatalf("reused recovered identity: got %s want %s: %v", got, want, err)
			}
		})
	}
}
