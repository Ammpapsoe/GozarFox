package engine

import (
	"errors"
	"testing"
)

func TestTypedNilDatagramDialNeverPanicsOrPublishesFlow(t *testing.T) {
	for _, failure := range []error{nil, errors.New("refused")} {
		var typedNil *echoDatagramTunnel
		flow := &datagramFlow{}
		e := &Engine{
			udpFlows: map[*datagramFlow]struct{}{flow: {}},
			udpDial:  func(string, int) (DatagramTunnel, error) { return typedNil, failure },
		}
		e.relayDatagrams(flow, "203.0.113.9", 53)
		if !flow.closed || len(e.udpFlows) != 0 {
			t.Fatal("failed datagram open was not closed and forgotten")
		}
	}
}
