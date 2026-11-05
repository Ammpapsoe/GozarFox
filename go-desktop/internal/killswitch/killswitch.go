package killswitch

import (
	"context"
	"errors"
	"fmt"
	"net/netip"
	"os"
	"os/exec"
	"strings"
	"time"

	"github.com/bardia049b-stack/Gozar1/go-desktop/internal/geo"
	"github.com/bardia049b-stack/Gozar1/go-desktop/internal/quiet"
)

type Config struct {
	Interface   string
	Proxy       []netip.Addr
	AllowDirect bool
}

func Enable(cfg Config) error {
	if cfg.Interface == "" {
		return errors.New("kill switch needs the tunnel interface")
	}
	return enableOS(cfg)
}

func Disable() error {
	return disableOS()
}

func run(name string, args ...string) error {
	ctx, cancel := context.WithTimeout(context.Background(), 30*time.Second)
	defer cancel()
	cmd := quiet.CommandContext(ctx, name, args...)
	out, err := cmd.CombinedOutput()
	if errors.Is(ctx.Err(), context.DeadlineExceeded) {
		return fmt.Errorf("%s timed out", name)
	}
	if err != nil {
		return fmt.Errorf("%s: %v: %s", name, err, strings.TrimSpace(string(out)))
	}
	return nil
}

func runIgnore(name string, args ...string) {
	_ = run(name, args...)
}

func runStdin(name, input string, args ...string) error {
	ctx, cancel := context.WithTimeout(context.Background(), 30*time.Second)
	defer cancel()
	cmd := quiet.CommandContext(ctx, name, args...)
	cmd.Stdin = strings.NewReader(input)
	out, err := cmd.CombinedOutput()
	if errors.Is(ctx.Err(), context.DeadlineExceeded) {
		return fmt.Errorf("%s timed out", name)
	}
	if err != nil {
		return fmt.Errorf("%s: %v: %s", name, err, strings.TrimSpace(string(out)))
	}
	return nil
}

func proxyLists(cfg Config) (string, string) {
	var v4, v6 []string
	for _, address := range cfg.Proxy {
		address = address.Unmap()
		if address.Is4() {
			v4 = append(v4, address.String())
		} else if address.Is6() {
			v6 = append(v6, address.String())
		}
	}
	return strings.Join(v4, ", "), strings.Join(v6, ", ")
}

func irPrefixes() (string, string) {
	var v4, v6 []string
	for _, prefix := range geo.IranianPrefixes() {
		if prefix.Addr().Is4() {
			v4 = append(v4, prefix.String())
		} else {
			v6 = append(v6, prefix.String())
		}
	}
	return strings.Join(v4, ", "), strings.Join(v6, ", ")
}

func tempFile(pattern, content string) (string, error) {
	file, err := os.CreateTemp("", pattern)
	if err != nil {
		return "", err
	}
	name := file.Name()
	if _, err := file.WriteString(content); err != nil {
		file.Close()
		os.Remove(name)
		return "", err
	}
	if err := file.Close(); err != nil {
		os.Remove(name)
		return "", err
	}
	return name, nil
}

func commandOutput(name string, args ...string) (string, error) {
	ctx, cancel := context.WithTimeout(context.Background(), 20*time.Second)
	defer cancel()
	cmd := exec.CommandContext(ctx, name, args...)
	out, err := cmd.CombinedOutput()
	if err != nil {
		return "", fmt.Errorf("%s: %v: %s", name, err, strings.TrimSpace(string(out)))
	}
	return string(out), nil
}
