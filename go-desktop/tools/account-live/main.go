// account-live is an explicitly authorized, CI-built diagnostic. It never
// writes or prints credentials and never activates an account entitlement.
package main

import (
	"encoding/json"
	"errors"
	"fmt"
	"net"
	"os"
	"strings"

	"github.com/bardia049b-stack/Gozar1/go-desktop/internal/fxa"
)

func failure(stage string, err error) {
	var api *fxa.Error
	var challenge *fxa.ChallengeRequired
	var network net.Error
	switch {
	case errors.As(err, &api):
		fmt.Printf("STATE=GO_ACCOUNT_%s_FAILED HTTP=%d ERRNO=%d\n", stage, api.Status, api.Errno)
	case errors.As(err, &challenge):
		fmt.Printf("STATE=GO_ACCOUNT_%s_CHALLENGE_REQUIRED\n", stage)
	case errors.As(err, &network) && network.Timeout():
		fmt.Printf("STATE=GO_ACCOUNT_%s_TIMEOUT\n", stage)
	case err != nil && strings.Contains(strings.ToLower(err.Error()), "security check"):
		fmt.Printf("STATE=GO_ACCOUNT_%s_SECURITY_CHECK_FAILED\n", stage)
	default:
		fmt.Printf("STATE=GO_ACCOUNT_%s_FAILED\n", stage)
	}
}

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
		failure("LOGIN", err)
		os.Exit(1)
	}
	if session.NeedsOTP {
		fmt.Println("STATE=GO_ACCOUNT_CODE_REQUIRED")
		return
	}
	tokens, err := fxa.SessionTokens(session.Token)
	if err != nil {
		failure("OAUTH", err)
		os.Exit(1)
	}
	if tokens.Access == "" || tokens.Refresh == "" {
		fmt.Println("STATE=GO_ACCOUNT_OAUTH_EMPTY_TOKEN")
		os.Exit(1)
	}
	fmt.Println("STATE=GO_ACCOUNT_LOGIN_AND_OAUTH_OK")
	refreshed, err := fxa.Refresh(tokens.Refresh)
	if err != nil {
		failure("REFRESH", err)
		os.Exit(1)
	}
	if refreshed.Access == "" {
		fmt.Println("STATE=GO_ACCOUNT_REFRESH_EMPTY_TOKEN")
		os.Exit(1)
	}
	fmt.Println("STATE=GO_ACCOUNT_REAL_REFRESH_OK")
	if servers, err := fxa.Servers(); err != nil || len(servers) == 0 {
		fmt.Println("STATE=GO_ACCOUNT_SERVER_LIST_FAILED")
		os.Exit(1)
	}
	fmt.Println("STATE=GO_ACCOUNT_SERVER_LIST_OK")
	// This uses the production transport and parsing but cannot activate an
	// entitlement if the user's existing account is not already eligible.
	pass, err := fxa.ExistingPassToken(refreshed.Access)
	if err != nil {
		failure("PASS", err)
		os.Exit(1)
	}
	if pass.Token == "" {
		fmt.Println("STATE=GO_ACCOUNT_PASS_EMPTY")
		os.Exit(1)
	}
	fmt.Println("STATE=GO_ACCOUNT_PASS_HTTP_200")
}
