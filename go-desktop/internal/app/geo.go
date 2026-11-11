package app

import (
	"bytes"
	"errors"
	"io"
	"log"
	"time"

	"github.com/bardia049b-stack/Gozar1/go-desktop/internal/geo"
	"github.com/bardia049b-stack/Gozar1/go-desktop/internal/tunnel"
)

const geoListURL = "raw.githubusercontent.com"
const geoListPath = "/Ammpapsoe/GozarFox/main/packaging/ir-domains.txt"

const maxGeoListBytes = 1_100_000
const maxGeoSignatureBytes = 512

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
	// The list and its detached signature are fetched through the same tunnel.
	// If the list changes between the two fetches, verification fails closed
	// and the previously verified rules stay in place.
	data, err := fetchGeoResource(transport, pass.Token, geoListPath, maxGeoListBytes)
	if err != nil {
		log.Printf("gozar: split-tunnel list update failed: %v", err)
		return
	}
	signature, err := fetchGeoResource(transport, pass.Token, geoListPath+".sig", maxGeoSignatureBytes)
	if err != nil {
		log.Printf("gozar: split-tunnel list signature unavailable: %v", err)
		return
	}
	if err := geo.VerifyList(data, signature); err != nil {
		log.Printf("gozar: split-tunnel list rejected: %v", err)
		return
	}
	if err := a.geo.Replace(data); err != nil {
		log.Printf("gozar: split-tunnel list rejected: %v", err)
	}
}

func fetchGeoResource(transport *tunnel.Manager, token, path string, limit int) ([]byte, error) {
	stream, err := transport.Open(geoListURL, 443, token, 20*time.Second)
	if err != nil {
		return nil, err
	}
	defer stream.Close()
	request := "GET " + path + " HTTP/1.1\r\nHost: " + geoListURL + "\r\nConnection: close\r\n\r\n"
	if _, err := io.WriteString(stream, request); err != nil {
		return nil, err
	}
	raw, err := io.ReadAll(io.LimitReader(stream, int64(limit)+4096))
	if err != nil {
		return nil, err
	}
	split := bytes.Index(raw, []byte("\r\n\r\n"))
	if split < 0 {
		return nil, errors.New("invalid split-tunnel list response")
	}
	head := raw[:split]
	if !bytes.HasPrefix(raw, []byte("HTTP/")) || !bytes.Contains(head, []byte(" 200 ")) {
		return nil, errors.New("split-tunnel list returned a non-200 response")
	}
	body := raw[split+4:]
	if len(body) > limit {
		return nil, errors.New("split-tunnel list is too large")
	}
	return body, nil
}
