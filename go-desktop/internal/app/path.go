package app

import (
	"context"
	"errors"
	"log"
	"net/netip"
	"sort"
	"strconv"
	"strings"
	"sync"
	"time"

	"github.com/Ammpapsoe/GozarFox/go-desktop/internal/fxa"
	"github.com/Ammpapsoe/GozarFox/go-desktop/internal/tundev"
	"github.com/Ammpapsoe/GozarFox/go-desktop/internal/tunnel"
)

type pathCandidate struct {
	place     fxa.Location
	target    tunnel.Target
	keep      []netip.Addr
	probeHost string
	probePort int
	average   time.Duration
	jitter    time.Duration
	err       error
}

func autoSelected(code string) bool {
	return code == "" || strings.EqualFold(code, "AUTO") || strings.EqualFold(code, "REC")
}

func preparePath(place fxa.Location, hops []tunnel.Hop) pathCandidate {
	return preparePathContext(context.Background(), place, hops)
}

func preparePathContext(ctx context.Context, place fxa.Location, hops []tunnel.Hop) pathCandidate {
	candidate := pathCandidate{
		place:     place,
		target:    tunnel.Target{Host: place.Host, Port: place.Port, Chain: hops},
		probeHost: place.Host,
		probePort: place.Port,
	}
	if len(hops) > 0 {
		candidate.probeHost = hops[0].Host
		candidate.probePort = hops[0].Port
	}
	for _, address := range tundev.NeighboursContext(ctx, candidate.probeHost) {
		if !address.Is4() {
			continue
		}
		candidate.target.DialHost = address.String()
		candidate.probeHost = address.String()
		if !address.IsLoopback() {
			candidate.keep = append(candidate.keep, address)
		}
		break
	}
	if candidate.target.DialHost == "" {
		candidate.err = errors.New("could not resolve the first proxy to IPv4 before starting the tunnel")
	}
	return candidate
}

func measurePath(ctx context.Context, candidate *pathCandidate, samples int, timeout time.Duration) {
	if candidate.err != nil {
		return
	}
	candidate.average, candidate.jitter, candidate.err = tunnel.LatencyContext(ctx, candidate.probeHost, candidate.probePort, samples, timeout)
}

func pathScore(candidate pathCandidate) time.Duration {
	return candidate.average + 2*candidate.jitter
}

func rankPaths(candidates []pathCandidate) []pathCandidate {
	ranked := append([]pathCandidate(nil), candidates...)
	sort.SliceStable(ranked, func(i, j int) bool {
		left, right := ranked[i], ranked[j]
		if (left.err == nil) != (right.err == nil) {
			return left.err == nil
		}
		if left.err != nil {
			return false
		}
		return pathScore(left) < pathScore(right)
	})
	return ranked
}

func autoCandidates(servers []fxa.Location, hops []tunnel.Hop, samples int, timeout time.Duration, concurrency int) []pathCandidate {
	return autoCandidatesContext(context.Background(), servers, hops, samples, timeout, concurrency)
}

func autoCandidatesContext(ctx context.Context, servers []fxa.Location, hops []tunnel.Hop, samples int, timeout time.Duration, concurrency int) []pathCandidate {
	candidates := make([]pathCandidate, 0, len(servers))
	for _, place := range servers {
		if ctx.Err() != nil {
			return nil
		}
		candidates = append(candidates, preparePathContext(ctx, place, hops))
	}
	if concurrency < 1 {
		concurrency = 1
	}
	if samples < 1 {
		samples = 1
	}
	cache := make(map[string]pathCandidate)
	var mu sync.Mutex
	var wg sync.WaitGroup
	slots := make(chan struct{}, concurrency)
	for index := range candidates {
		if candidates[index].err != nil {
			continue
		}
		wg.Add(1)
		go func(index int) {
			defer wg.Done()
			select {
			case slots <- struct{}{}:
			case <-ctx.Done():
				return
			}
			defer func() { <-slots }()
			cacheKey := candidates[index].probeHost + ":" + strconv.Itoa(candidates[index].probePort)
			mu.Lock()
			cached, ok := cache[cacheKey]
			mu.Unlock()
			if ok {
				candidates[index].average = cached.average
				candidates[index].jitter = cached.jitter
				candidates[index].err = cached.err
				return
			}
			measurePath(ctx, &candidates[index], samples, timeout)
			mu.Lock()
			cache[cacheKey] = candidates[index]
			mu.Unlock()
		}(index)
	}
	wg.Wait()
	return rankPaths(candidates)
}

func pickLocation(servers []fxa.Location, code string) (fxa.Location, error) {
	if len(servers) == 0 {
		return fxa.Location{}, errors.New("no servers")
	}
	if !autoSelected(code) {
		for _, place := range servers {
			if strings.EqualFold(place.Code, code) || strings.EqualFold(place.Host, code) {
				return place, nil
			}
		}
		return fxa.Location{}, errors.New("server not found")
	}
	for _, place := range servers {
		if place.Recommended {
			return place, nil
		}
	}
	return servers[0], nil
}

func verifyPath(candidate pathCandidate, pass string, timeout time.Duration) (*tunnel.Manager, error) {
	return verifyPathContext(context.Background(), candidate, pass, timeout)
}

func verifyPathContext(ctx context.Context, candidate pathCandidate, pass string, timeout time.Duration) (*tunnel.Manager, error) {
	if err := ctx.Err(); err != nil {
		return nil, err
	}
	transport := tunnel.NewManager(candidate.target)
	// The verifier owns only this temporary manager. No stale result can
	// activate the tunnel, and cancellation closes its connections.
	result := make(chan error, 1)
	go func() { result <- transport.Verify(pass, timeout) }()
	var err error
	select {
	case err = <-result:
		if ctx.Err() != nil {
			err = ctx.Err()
		}
	case <-ctx.Done():
		err = ctx.Err()
	}
	if err != nil {
		transport.Close()
		return nil, err
	}
	return transport, nil
}

func choosePath(candidates []pathCandidate, pass string, timeout time.Duration, verify func(pathCandidate, string, time.Duration) (*tunnel.Manager, error)) (pathCandidate, *tunnel.Manager, error) {
	return choosePathContext(context.Background(), candidates, pass, timeout, verify)
}

func choosePathContext(ctx context.Context, candidates []pathCandidate, pass string, timeout time.Duration, verify func(pathCandidate, string, time.Duration) (*tunnel.Manager, error)) (pathCandidate, *tunnel.Manager, error) {
	var lastErr error
	for _, candidate := range candidates {
		if err := ctx.Err(); err != nil {
			return pathCandidate{}, nil, err
		}
		if candidate.err != nil {
			lastErr = candidate.err
			continue
		}
		transport, err := verify(candidate, pass, timeout)
		if err == nil {
			return candidate, transport, nil
		}
		if transport != nil {
			transport.Close()
		}
		log.Printf("gozar: server %s failed: %v", candidate.place.Host, err)
		lastErr = err
		if ctx.Err() != nil {
			return pathCandidate{}, nil, ctx.Err()
		}
		if tunnel.IsRefusal(err) {
			return pathCandidate{}, nil, err
		}
	}
	if lastErr == nil {
		lastErr = errors.New("no relay answered")
	}
	return pathCandidate{}, nil, lastErr
}
