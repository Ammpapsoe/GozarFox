package app

import (
	"bytes"
	"errors"
	"io"
	"log"
	"time"

	"github.com/bardia049b-stack/Gozar1/go-desktop/internal/tunnel"
)

const geoListURL = "raw.githubusercontent.com"
const geoListPath = "/bardia049b-stack/Gozar/main/packaging/ir-domains.txt"

func (a *App) maybeRefreshGeo(force bool) {
	now := time.Now().UnixNano()
	if !force && now-a.geoRefresh.Load() < int64(24*time.Hour) {
		return
	}
	a.mu.Lock()
	transport := a.transport
	pass := a.pass
	a.mu.Unlock()
	if transport == nil || pass == nil || pass.Token == "" {
		return
	}
	a.geoRefresh.Store(now)
	data, err := fetchGeoList(transport, pass.Token)
	if err != nil {
		log.Printf("gozar: split-tunnel list update failed: %v", err)
		return
	}
	if err := a.geo.Replace(data); err != nil {
		log.Printf("gozar: split-tunnel list rejected: %v", err)
	}
}

func fetchGeoList(transport *tunnel.Manager, token string) ([]byte, error) {
	stream, err := transport.Open(geoListURL, 443, token, 20*time.Second)
	if err != nil {
		return nil, err
	}
	defer stream.Close()
	request := "GET " + geoListPath + " HTTP/1.1\r\nHost: " + geoListURL + "\r\nConnection: close\r\n\r\n"
	if _, err := io.WriteString(stream, request); err != nil {
		return nil, err
	}
	raw, err := io.ReadAll(io.LimitReader(stream, 1_100_000))
	if err != nil {
		return nil, err
	}
	split := bytes.Index(raw, []byte("\r\n\r\n"))
	if split < 0 {
		return nil, errors.New("invalid split-tunnel list response")
	}
	head := string(raw[:split])
	if !bytes.HasPrefix(raw, []byte("HTTP/")) || !bytes.Contains(raw[:split], []byte(" 200 ")) {
		return nil, errors.New("split-tunnel list returned " + head)
	}
	return raw[split+4:], nil
}
