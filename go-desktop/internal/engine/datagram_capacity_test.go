package engine

import (
	"errors"
	"testing"
)

func TestDatagramCapacityCountsPendingAndEstablishedFlows(t *testing.T) {
	e := &Engine{udpDial: func(string, int) (DatagramTunnel, error) { return nil, errors.New("unused") }}
	// Keep half as in-flight opens, and register the others as live flows.
	flows := make([]*datagramFlow, 0, maxDatagramFlows/2)
	for i := 0; i < maxDatagramFlows; i++ {
		if !e.reserveDatagram() {
			t.Fatalf("reservation %d of %d refused early", i, maxDatagramFlows)
		}
		if i%2 == 0 {
			flow := &datagramFlow{}
			if !e.registerDatagram(flow) {
				t.Fatal("registration unexpectedly refused")
			}
			flows = append(flows, flow)
		}
	}
	if e.reserveDatagram() {
		t.Fatal("flow beyond capacity was admitted")
	}
	e.cancelDatagramReservation()
	if !e.reserveDatagram() {
		t.Fatal("cancelled pending open did not release capacity")
	}
	e.shutdownDatagrams()
	if e.reserveDatagram() {
		t.Fatal("shutdown admitted another flow")
	}
	for range maxDatagramFlows / 2 {
		e.cancelDatagramReservation()
	}
	for _, flow := range flows {
		e.forgetDatagram(flow)
	}
	if e.udpReservations != 0 || len(e.udpFlows) != 0 {
		t.Fatalf("resources not accounted for: %d reservations, %d flows", e.udpReservations, len(e.udpFlows))
	}
}
