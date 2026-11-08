// warp-live is an explicitly opted-in diagnostic, not part of the app. It
// reuses a pre-authorized WARP test device and makes DIRECT network connections;
// it neither registers a device nor prints identity or packet contents.
package main

import (
	"context"
	"encoding/binary"
	"encoding/json"
	"flag"
	"fmt"
	"io"
	"net"
	"os"
	"time"

	"github.com/bardia049b-stack/Gozar1/go-desktop/internal/warp"
)

func run() error {
	path := flag.String("identity", "", "protected pre-existing WARP identity JSON")
	direct := flag.Bool("allow-direct-test", false, "explicitly authorize direct network probing")
	flag.Parse()
	if !*direct || *path == "" {
		return fmt.Errorf("identity and direct-test authorization are required")
	}
	info, err := os.Stat(*path)
	if err != nil || info.Mode().Perm()&0o077 != 0 {
		return fmt.Errorf("identity file must be private")
	}
	raw, err := os.ReadFile(*path)
	if err != nil {
		return fmt.Errorf("identity unavailable")
	}
	defer func() {
		for i := range raw {
			raw[i] = 0
		}
	}()
	identity := &warp.Identity{}
	if json.Unmarshal(raw, identity) != nil || !identity.Ready() {
		return fmt.Errorf("identity invalid")
	}
	dial := func(ctx context.Context, host string, port int) (io.ReadWriteCloser, error) {
		d := net.Dialer{Timeout: 8 * time.Second}
		return d.DialContext(ctx, "tcp", net.JoinHostPort(host, fmt.Sprint(port)))
	}
	ctx, cancel := context.WithTimeout(context.Background(), 30*time.Second)
	defer cancel()
	auto, err := warp.NewAuto(ctx, identity, dial)
	if err != nil {
		return fmt.Errorf("authenticated scanner failed: %w", err)
	}
	defer auto.Close()
	fmt.Println("STATE=GO_WARP_AUTHENTICATED_UDP_SCAN_OK")
	flow, err := auto.OpenUDP("1.1.1.1", 53)
	if err != nil {
		return fmt.Errorf("flow open failed")
	}
	defer flow.Close()
	query := []byte{0x79, 0x31, 0x01, 0x00, 0x00, 0x01, 0, 0, 0, 0, 0, 0,
		7, 'e', 'x', 'a', 'm', 'p', 'l', 'e', 3, 'c', 'o', 'm', 0, 0, 1, 0, 1}
	if err := flow.WriteDatagram(query); err != nil {
		return fmt.Errorf("UDP write failed")
	}
	result := make(chan bool, 1)
	go func() {
		reply, err := flow.ReadDatagram()
		result <- err == nil && len(reply) >= 12 && binary.BigEndian.Uint16(reply[:2]) == 0x7931 && reply[2]&0x80 != 0 && reply[3]&0x0f == 0
	}()
	select {
	case ok := <-result:
		if !ok {
			return fmt.Errorf("UDP reply invalid")
		}
	case <-ctx.Done():
		return fmt.Errorf("UDP reply timed out")
	}
	fmt.Println("STATE=GO_WARP_REAL_UDP_ROUND_TRIP_OK")
	return nil
}

func main() {
	if err := run(); err != nil {
		fmt.Fprintln(os.Stderr, "STATE=GO_WARP_PROBE_FAILED", err)
		os.Exit(1)
	}
}
