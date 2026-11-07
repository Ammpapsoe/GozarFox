package update

import (
	"context"
	"errors"
	"testing"
	"time"
)

func TestServiceOverlapAndCancellationAcrossFiftyRaces(t *testing.T) {
	verified := verifiedForData(t, []byte("package"))
	for round := 0; round < 50; round++ {
		service := NewService()
		service.osName, service.arch = "linux", "amd64"
		enteredCheck := make(chan struct{})
		service.check = func(ctx context.Context, _ string) (*Verified, error) {
			close(enteredCheck)
			<-ctx.Done()
			return nil, ctx.Err()
		}
		checked := make(chan error, 1)
		go func() {
			_, err := service.Check(context.Background(), "1.10.4")
			checked <- err
		}()
		select {
		case <-enteredCheck:
		case <-time.After(5 * time.Second):
			t.Fatal("check did not start")
		}
		if _, err := service.Check(context.Background(), "1.10.4"); !errors.Is(err, ErrBusy) {
			t.Fatalf("overlapping check: %v", err)
		}
		if _, err := service.Download(context.Background(), "linux-amd64-deb"); !errors.Is(err, ErrBusy) {
			t.Fatalf("overlapping download: %v", err)
		}
		service.Cancel()
		select {
		case err := <-checked:
			if !errors.Is(err, context.Canceled) {
				t.Fatalf("cancel check: %v", err)
			}
		case <-time.After(5 * time.Second):
			t.Fatal("cancelled check did not return")
		}
		service.check = func(context.Context, string) (*Verified, error) { return verified, nil }
		if _, err := service.Check(context.Background(), "1.10.4"); err != nil {
			t.Fatalf("service remained busy after cancellation: %v", err)
		}
		enteredDownload := make(chan struct{})
		service.download = func(ctx context.Context, _ *Verified, _ string) (string, error) {
			close(enteredDownload)
			<-ctx.Done()
			return "", ctx.Err()
		}
		downloaded := make(chan error, 1)
		go func() {
			_, err := service.Download(context.Background(), "linux-amd64-deb")
			downloaded <- err
		}()
		select {
		case <-enteredDownload:
		case <-time.After(5 * time.Second):
			t.Fatal("download did not start")
		}
		if _, err := service.Check(context.Background(), "1.10.4"); !errors.Is(err, ErrBusy) {
			t.Fatalf("check overlapped download: %v", err)
		}
		service.Cancel()
		select {
		case err := <-downloaded:
			if !errors.Is(err, context.Canceled) {
				t.Fatalf("cancel download: %v", err)
			}
		case <-time.After(5 * time.Second):
			t.Fatal("cancelled download did not return")
		}
	}
}
