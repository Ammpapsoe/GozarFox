package engine

import (
	"testing"

	"github.com/Ammpapsoe/GozarFox/go-desktop/internal/tunnel"
)

// These limits are the ones the field reports blamed for stalls. They are
// asserted so a future refactor cannot quietly restore the old values.

func TestRelayBufferIsLargeEnoughForAFastPath(t *testing.T) {
	if relayBuffer < 128*1024 {
		t.Fatalf("relayBuffer is %d bytes; a copy smaller than 128 KiB caps throughput on a fast relay", relayBuffer)
	}
	// The pool must hand back a full-size buffer, and must accept it back.
	held := takeRelayBuffer()
	if len(*held) != relayBuffer || cap(*held) != relayBuffer {
		t.Fatalf("pooled buffer is %d/%d, want %d", len(*held), cap(*held), relayBuffer)
	}
	// A truncated buffer must not poison the pool: putRelayBuffer restores the
	// length instead of storing whatever slice the caller ended with.
	*held = (*held)[:10]
	putRelayBuffer(held)
	if again := takeRelayBuffer(); len(*again) != relayBuffer {
		t.Fatalf("the pool returned a %d byte buffer after a truncating put", len(*again))
	}
}

func TestDatagramFlowCeilingCoversABusySession(t *testing.T) {
	if maxDatagramFlows < 512 {
		t.Fatalf("maxDatagramFlows is %d; a phone with a call, a game and two browsers needs more", maxDatagramFlows)
	}
}

func TestTunnelSessionBudgetIsNotAWorkingLimit(t *testing.T) {
	// One browser tab opens several connections and the DNS and control traffic
	// share the pool, so 8x8 (64 flows) was reached in normal use and new
	// connections then waited for an old one to close.
	total := tunnel.H2FlowBudget()
	if total < 1024 {
		t.Fatalf("the tunnel admits at most %d concurrent HTTP/2 flows", total)
	}
}
