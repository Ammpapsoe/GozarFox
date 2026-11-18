package tunnel

import (
	"net/http"

	"golang.org/x/net/http2"
)

// h2ReceiveWindow limits buffered response data per stream. This is a
// throughput/latency trade-off, not a guarantee of better performance: on
// high-bandwidth, high-latency paths a smaller window can limit throughput.
const h2ReceiveWindow = 1 << 20

// newH2Transport associates the x/net HTTP/2 transport with a net/http
// transport so that x/net applies its HTTP2Config to manually opened client
// connections. The connection window is left at the library default.
func newH2Transport() (*http2.Transport, error) {
	base := &http.Transport{
		DisableCompression: true,
		HTTP2: &http.HTTP2Config{
			MaxReceiveBufferPerStream: h2ReceiveWindow,
		},
	}
	transport, err := http2.ConfigureTransports(base)
	if err != nil {
		return nil, err
	}
	transport.DisableCompression = true
	return transport, nil
}
