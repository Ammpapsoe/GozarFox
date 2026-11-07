package update

import (
	"context"
	"errors"
	"testing"
)

func TestServiceClearsOldResultAfterFailedCheckAndRejectsWrongPlatform(t *testing.T) {
	verified := verifiedForData(t, []byte("package"))
	service := NewService()
	service.osName, service.arch = "linux", "amd64"
	service.check = func(context.Context, string) (*Verified, error) { return verified, nil }
	service.download = func(context.Context, *Verified, string) (string, error) { return "/verified/file", nil }
	summary, err := service.Check(context.Background(), "1.10.4")
	if err != nil || !summary.Newer || len(summary.Artifacts) != 1 {
		t.Fatalf("check failed: %v", err)
	}
	if _, err := service.Download(context.Background(), "wrong-id"); err == nil {
		t.Fatal("accepted an artifact not offered for this platform")
	}
	service.check = func(context.Context, string) (*Verified, error) { return nil, errors.New("offline") }
	if _, err := service.Check(context.Background(), "1.10.4"); err == nil {
		t.Fatal("failed check was treated as success")
	}
	if _, err := service.Download(context.Background(), "linux-amd64-deb"); err == nil {
		t.Fatal("failed check left an old update available")
	}
}
