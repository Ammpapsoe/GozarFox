// account-live is an explicitly authorized, CI-built diagnostic. It never
// writes or prints credentials and never activates an account entitlement.
package main

import (
	"encoding/json"
	"fmt"
	"io"
	"net/http"
	"os"
	"time"

	"github.com/bardia049b-stack/Gozar1/go-desktop/internal/fxa"
)

func main() {
	if len(os.Args) != 3 || os.Args[1] != "--allow-account-test" {
		fmt.Println("STATE=GO_ACCOUNT_PROBE_REQUIRES_AUTHORIZATION")
		os.Exit(2)
	}
	path := os.Args[2]
	info, err := os.Lstat(path)
	if err != nil || !info.Mode().IsRegular() || info.Mode().Perm()&0o077 != 0 || info.Size() > 8192 {
		fmt.Println("STATE=GO_ACCOUNT_INPUT_NOT_PRIVATE")
		os.Exit(2)
	}
	data, err := os.ReadFile(path)
	removeErr := os.Remove(path)
	if err != nil || removeErr != nil {
		fmt.Println("STATE=GO_ACCOUNT_INPUT_FAILED")
		os.Exit(2)
	}
	var input struct {
		Email    string `json:"email"`
		Password string `json:"password"`
	}
	if json.Unmarshal(data, &input) != nil || input.Email == "" || input.Password == "" {
		fmt.Println("STATE=GO_ACCOUNT_INPUT_FAILED")
		os.Exit(2)
	}
	for i := range data {
		data[i] = 0
	}
	session, err := fxa.SignIn(input.Email, input.Password)
	input.Email, input.Password = "", ""
	if err != nil {
		fmt.Println("STATE=GO_ACCOUNT_LOGIN_FAILED")
		os.Exit(1)
	}
	if session.NeedsOTP {
		fmt.Println("STATE=GO_ACCOUNT_CODE_REQUIRED")
		return
	}
	tokens, err := fxa.SessionTokens(session.Token)
	if err != nil || tokens.Access == "" || tokens.Refresh == "" {
		fmt.Println("STATE=GO_ACCOUNT_OAUTH_FAILED")
		os.Exit(1)
	}
	fmt.Println("STATE=GO_ACCOUNT_LOGIN_AND_OAUTH_OK")
	refreshed, err := fxa.Refresh(tokens.Refresh)
	if err != nil || refreshed.Access == "" {
		fmt.Println("STATE=GO_ACCOUNT_REFRESH_FAILED")
		os.Exit(1)
	}
	fmt.Println("STATE=GO_ACCOUNT_REAL_REFRESH_OK")
	if servers, err := fxa.Servers(); err != nil || len(servers) == 0 {
		fmt.Println("STATE=GO_ACCOUNT_SERVER_LIST_FAILED")
		os.Exit(1)
	}
	fmt.Println("STATE=GO_ACCOUNT_SERVER_LIST_OK")
	// This is a read-only version of the production pass request: deliberately
	// do not call PassToken, whose 403 path can activate an entitlement.
	client := &http.Client{Timeout: 25 * time.Second, CheckRedirect: func(*http.Request, []*http.Request) error {
		return http.ErrUseLastResponse
	}}
	request, _ := http.NewRequest(http.MethodGet, "https://vpn.mozilla.org/api/v1/fpn/token", nil)
	request.Header.Set("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:158.0) Gecko/20100101 Firefox/158.0")
	request.Header.Set("Accept", "application/json")
	request.Header.Set("Authorization", "Bearer "+refreshed.Access)
	response, err := client.Do(request)
	if err != nil {
		fmt.Println("STATE=GO_ACCOUNT_PASS_REQUEST_FAILED")
		os.Exit(1)
	}
	defer response.Body.Close()
	_, _ = io.Copy(io.Discard, io.LimitReader(response.Body, 1<<20))
	fmt.Printf("STATE=GO_ACCOUNT_PASS_HTTP_%d\n", response.StatusCode)
	if response.StatusCode != http.StatusOK {
		os.Exit(1)
	}
}
