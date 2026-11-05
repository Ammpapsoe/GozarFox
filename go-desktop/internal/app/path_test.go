package app

import (
	"errors"
	"net"
	"testing"
	"time"

	"github.com/bardia049b-stack/Gozar1/go-desktop/internal/fxa"
	"github.com/bardia049b-stack/Gozar1/go-desktop/internal/tunnel"
)

func TestRankPathsPrefersLowerScoreAndHealthyCandidates(t *testing.T) {
	failed := pathCandidate{place: fxa.Location{Host: "failed"}, err: errors.New("unreachable")}
	slow := pathCandidate{place: fxa.Location{Host: "slow"}, average: 80 * time.Millisecond, jitter: 10 * time.Millisecond}
	fast := pathCandidate{place: fxa.Location{Host: "fast"}, average: 30 * time.Millisecond, jitter: 5 * time.Millisecond}
	jittery := pathCandidate{place: fxa.Location{Host: "jittery"}, average: 20 * time.Millisecond, jitter: 40 * time.Millisecond}
	ranked := rankPaths([]pathCandidate{failed, slow, jittery, fast})
	want := []string{"fast", "slow", "jittery", "failed"}
	for index, host := range want {
		if ranked[index].place.Host != host {
			t.Fatalf("rank %d = %s, want %s", index, ranked[index].place.Host, host)
		}
	}
}

func TestChoosePathFallsBackToTheNextHealthyCandidate(t *testing.T) {
	first := pathCandidate{place: fxa.Location{Host: "first"}, target: tunnel.Target{Host: "first", Port: 443}}
	second := pathCandidate{place: fxa.Location{Host: "second"}, target: tunnel.Target{Host: "second", Port: 443}}
	healthy := tunnel.NewManager(second.target)
	defer healthy.Close()
	attempts := 0
	chosen, transport, err := choosePath([]pathCandidate{first, second}, "pass", time.Second, func(candidate pathCandidate, _ string, _ time.Duration) (*tunnel.Manager, error) {
		attempts++
		if candidate.place.Host == "first" {
			return nil, errors.New("relay timed out")
		}
		return healthy, nil
	})
	if err != nil {
		t.Fatal(err)
	}
	defer transport.Close()
	if attempts != 2 || chosen.place.Host != "second" || transport != healthy {
		t.Fatalf("attempts=%d chosen=%s", attempts, chosen.place.Host)
	}
}

func TestChoosePathReportsTheLastFailureWhenEveryCandidateFails(t *testing.T) {
	candidates := []pathCandidate{
		{place: fxa.Location{Host: "first"}},
		{place: fxa.Location{Host: "second"}},
	}
	_, _, err := choosePath(candidates, "pass", time.Second, func(candidate pathCandidate, _ string, _ time.Duration) (*tunnel.Manager, error) {
		return nil, errors.New(candidate.place.Host + " failed")
	})
	if err == nil || err.Error() != "second failed" {
		t.Fatalf("unexpected error: %v", err)
	}
}

func TestAutoCandidatesRanksReachableRelaysBeforeFailures(t *testing.T) {
	listener, err := net.Listen("tcp4", "127.0.0.1:0")
	if err != nil {
		t.Fatal(err)
	}
	defer listener.Close()
	go func() {
		for {
			conn, err := listener.Accept()
			if err != nil {
				return
			}
			conn.Close()
		}
	}()
	closed, err := net.Listen("tcp4", "127.0.0.1:0")
	if err != nil {
		t.Fatal(err)
	}
	closedPort := closed.Addr().(*net.TCPAddr).Port
	closed.Close()
	healthyPort := listener.Addr().(*net.TCPAddr).Port
	servers := []fxa.Location{
		{Code: "BAD", Host: "127.0.0.1", Port: closedPort},
		{Code: "OK", Host: "127.0.0.1", Port: healthyPort},
	}
	ranked := autoCandidates(servers, nil, 1, 300*time.Millisecond, 2)
	if len(ranked) != 2 {
		t.Fatalf("got %d candidates", len(ranked))
	}
	if ranked[0].place.Code != "OK" || ranked[0].err != nil {
		t.Fatalf("healthy relay was not ranked first: %+v", ranked[0])
	}
	if ranked[1].err == nil {
		t.Fatal("the closed relay should keep its failure")
	}
}

func TestPickLocationRespectsManualSelectionAndAutoFallback(t *testing.T) {
	servers := []fxa.Location{
		{Code: "US", Host: "us.example", Port: 443},
		{Code: "NL", Host: "nl.example", Port: 8443, Recommended: true},
	}
	manual, err := pickLocation(servers, "us.example")
	if err != nil || manual.Code != "US" {
		t.Fatalf("manual pick = %+v, %v", manual, err)
	}
	if _, err := pickLocation(servers, "missing"); err == nil {
		t.Fatal("an unknown manual selection must not silently fall back")
	}
	auto, err := pickLocation(servers, "")
	if err != nil || auto.Code != "NL" {
		t.Fatalf("auto pick = %+v, %v", auto, err)
	}
}

func TestSetServerAutoClearsTheManualSelection(t *testing.T) {
	root := t.TempDir()
	t.Setenv("HOME", root)
	t.Setenv("XDG_CONFIG_HOME", root)
	t.Setenv("APPDATA", root)
	current := New("test")
	defer current.Close()
	current.mu.Lock()
	current.servers = []fxa.Location{{Code: "NL", Host: "nl.example", Port: 443}}
	current.cfg.Server = "nl.example"
	current.mu.Unlock()
	if err := current.SetServer("AUTO"); err != nil {
		t.Fatal(err)
	}
	if current.Config().Server != "" {
		t.Fatalf("AUTO should clear the manual selection, got %q", current.Config().Server)
	}
	if err := current.SetServer("nl.example"); err != nil {
		t.Fatal(err)
	}
	if current.Config().Server != "nl.example" {
		t.Fatal("manual selection was not restored")
	}
	if err := current.SetServer("REC"); err != nil {
		t.Fatal(err)
	}
	if current.Config().Server != "" {
		t.Fatal("REC should return to automatic selection")
	}
}
