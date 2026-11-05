package tunnel

import (
	"errors"
	"time"
)

func Latency(host string, port int, samples int, timeout time.Duration) (time.Duration, time.Duration, error) {
	if samples < 1 {
		samples = 1
	}
	if timeout <= 0 {
		timeout = 1500 * time.Millisecond
	}
	var total time.Duration
	var previous time.Duration
	var jitter time.Duration
	successes := 0
	var lastErr error
	for index := 0; index < samples; index++ {
		started := time.Now()
		conn, err := dialTCP(host, port, timeout)
		if err != nil {
			lastErr = err
			continue
		}
		elapsed := time.Since(started)
		conn.Close()
		if successes > 0 {
			diff := elapsed - previous
			if diff < 0 {
				diff = -diff
			}
			jitter += diff
		}
		previous = elapsed
		total += elapsed
		successes++
	}
	if successes == 0 {
		if lastErr == nil {
			lastErr = errors.New("the relay did not answer")
		}
		return 0, 0, lastErr
	}
	average := total / time.Duration(successes)
	if successes > 1 {
		jitter /= time.Duration(successes - 1)
	}
	return average, jitter, nil
}

func (m *Manager) Verify(pass string, timeout time.Duration) error {
	if timeout <= 0 {
		timeout = 8 * time.Second
	}
	var lastErr error
	for _, host := range []string{"www.mozilla.org", "www.cloudflare.com"} {
		conn, err := m.Open(host, 443, pass, timeout)
		if err == nil {
			conn.Close()
			return nil
		}
		if isRefusal(err) {
			return err
		}
		lastErr = err
	}
	if lastErr == nil {
		lastErr = errors.New("the relay did not answer")
	}
	return lastErr
}

func IsRefusal(err error) bool {
	return isRefusal(err)
}
