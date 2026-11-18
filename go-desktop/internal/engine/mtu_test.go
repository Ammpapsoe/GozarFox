package engine

import "testing"

func TestTunnelMTUSelection(t *testing.T) {
	cases := []struct {
		platform string
		udp      bool
		want     int
	}{
		{"windows", false, 9000},
		{"windows", true, 1280},
		{"linux", false, 1600},
		{"darwin", false, 1600},
		{"linux", true, 1280},
		{"darwin", true, 1280},
	}
	for _, tc := range cases {
		if got := tunnelMTU(tc.platform, tc.udp); got != tc.want {
			t.Errorf("tunnelMTU(%s, %t) = %d, want %d", tc.platform, tc.udp, got, tc.want)
		}
	}
}
