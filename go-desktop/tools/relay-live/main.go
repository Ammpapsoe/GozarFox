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

type input struct {
	Email    string `json:"email"`
	Password string `json:"password"`
	Code     string `json:"code"`
	Create   bool   `json:"create"`
}

func report(stage string, err error) {
	var api *fxa.Error
	var challenge *fxa.ChallengeRequired
	var network net.Error
	switch {
	case errors.As(err, &api):
		fmt.Printf("STATE=RELAY_%s_FAILED HTTP=%d ERRNO=%d\n", stage, api.Status, api.Errno)
	case errors.As(err, &challenge):
		fmt.Printf("STATE=RELAY_%s_CHALLENGE_REQUIRED\n", stage)
	case errors.As(err, &network) && network.Timeout():
		fmt.Printf("STATE=RELAY_%s_TIMEOUT\n", stage)
	case err != nil && strings.Contains(strings.ToLower(err.Error()), "pin mismatch"):
		fmt.Println("STATE=RELAY_PIN_MISMATCH")
	default:
		fmt.Printf("STATE=RELAY_%s_FAILED\n", stage)
	}
}

func main() {
	if len(os.Args) != 5 || os.Args[1] != "--allow-account-test" || os.Args[2] != "--allow-activation" || os.Args[3] != "--allow-create-account" {
		fmt.Println("STATE=RELAY_PROBE_REQUIRES_AUTHORIZATION")
		os.Exit(2)
	}
	path := os.Args[4]
	info, err := os.Lstat(path)
	if err != nil || !info.Mode().IsRegular() || info.Mode().Perm()&0o077 != 0 || info.Size() > 8192 {
		fmt.Println("STATE=RELAY_INPUT_NOT_PRIVATE")
		os.Exit(2)
	}
	data, err := os.ReadFile(path)
	removeErr := os.Remove(path)
	if err != nil || removeErr != nil {
		fmt.Println("STATE=RELAY_INPUT_FAILED")
		os.Exit(2)
	}
	var credentials input
	if json.Unmarshal(data, &credentials) != nil || credentials.Email == "" || credentials.Password == "" {
		fmt.Println("STATE=RELAY_INPUT_FAILED")
		os.Exit(2)
	}
	for index := range data {
		data[index] = 0
	}
	var session *fxa.Session
	if credentials.Create {
		session, err = fxa.Register(credentials.Email, credentials.Password)
	} else {
		session, err = fxa.SignIn(credentials.Email, credentials.Password)
	}
	credentials.Email, credentials.Password = "", ""
	if err != nil {
		report("LOGIN", err)
		os.Exit(1)
	}
	if session.NeedsOTP {
		if credentials.Code == "" {
			fmt.Println("STATE=RELAY_ACCOUNT_CODE_REQUIRED")
			os.Exit(1)
		}
		if credentials.Create {
			err = fxa.VerifySignup(session.UID, credentials.Code)
		} else {
			err = fxa.VerifyCode(session.Token, credentials.Code, session.VerificationMethod)
		}
		credentials.Code = ""
		if err != nil {
			report("VERIFY", err)
			os.Exit(1)
		}
	}
	tokens, err := fxa.SessionTokens(session.Token)
	if err != nil {
		report("OAUTH", err)
		os.Exit(1)
	}
	if tokens.Access == "" {
		fmt.Println("STATE=RELAY_OAUTH_EMPTY")
		os.Exit(1)
	}
	fmt.Println("STATE=RELAY_LOGIN_AND_OAUTH_OK")
	locations, err := fxa.ServersViaRelay()
	if err != nil || len(locations) == 0 {
		report("SERVER_LIST", err)
		os.Exit(1)
	}
	fmt.Println("STATE=RELAY_SERVER_LIST_OK")
	pass, err := fxa.PassTokenViaRelay(tokens.Access)
	if err != nil {
		report("PASS", err)
		os.Exit(1)
	}
	if pass.Token == "" {
		fmt.Println("STATE=RELAY_PASS_EMPTY")
		os.Exit(1)
	}
	fmt.Println("STATE=RELAY_PASS_HTTP_200")
}
