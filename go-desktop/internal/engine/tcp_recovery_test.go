package engine

import (
	"testing"

	"gvisor.dev/gvisor/pkg/tcpip"
	"gvisor.dev/gvisor/pkg/tcpip/stack"
	"gvisor.dev/gvisor/pkg/tcpip/transport/tcp"
)

func TestTCPRecoveryPlatformPolicy(t *testing.T) {
	for _, tc := range []struct {
		platform string
		want     tcpip.TCPRecovery
	}{
		{"windows", 0},
		{"linux", tcpip.TCPRACKLossDetection},
		{"darwin", tcpip.TCPRACKLossDetection},
	} {
		t.Run(tc.platform, func(t *testing.T) {
			s := stack.New(stack.Options{TransportProtocols: []stack.TransportProtocolFactory{tcp.NewProtocolCUBIC}})
			defer s.Close()
			if err := tuneTCPForPlatform(s, tc.platform); err != nil {
				t.Fatal(err)
			}
			var got tcpip.TCPRecovery
			if err := s.TransportProtocolOption(tcp.ProtocolNumber, &got); err != nil {
				t.Fatal(err)
			}
			if got != tc.want {
				t.Fatalf("TCP recovery = %d, want %d", got, tc.want)
			}
		})
	}
}
