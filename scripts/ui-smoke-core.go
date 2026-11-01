package main

import (
	"bufio"
	"encoding/json"
	"os"
)

type location struct {
	Code    string `json:"code"`
	Country string `json:"country"`
	City    string `json:"city"`
	Host    string `json:"host"`
	Port    int    `json:"port"`
}

func main() {
	server := location{Code: "NL", Country: "Netherlands", City: "Amsterdam", Host: "relay.example.test", Port: 8443}
	live := true
	scanner := bufio.NewScanner(os.Stdin)
	writer := bufio.NewWriter(os.Stdout)
	for scanner.Scan() {
		var request struct {
			ID     int    `json:"id"`
			Method string `json:"method"`
		}
		if json.Unmarshal(scanner.Bytes(), &request) != nil {
			continue
		}
		if path := os.Getenv("GOZAR_UI_TEST_LOG"); path != "" {
			if file, err := os.OpenFile(path, os.O_CREATE|os.O_APPEND|os.O_WRONLY, 0600); err == nil {
				_, _ = file.WriteString(request.Method + "\n")
				_ = file.Close()
			}
		}
		if request.Method == "disconnect" {
			live = false
		}
		state := map[string]any{
			"signed_in": true, "email": "sample@example.test", "language": "en", "theme": "dark",
			"live": live, "connecting": false, "mode": "tun", "server": server,
			"servers": []location{server}, "limit": 10737418240, "remaining": 5368709120,
			"rate_down": 2048, "rate_up": 1024, "down": 4096, "up": 1024,
			"exit": "192.0.2.4", "device": "SIMULATED TUN", "version": "test",
		}
		_ = json.NewEncoder(writer).Encode(map[string]any{"id": request.ID, "state": state})
		_ = writer.Flush()
		if request.Method == "quit" {
			return
		}
	}
}
