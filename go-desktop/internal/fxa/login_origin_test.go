package fxa

import (
	"encoding/json"
	"net/http"
	"testing"
)

func TestPlainEmailSessionAttemptsOAuthWithoutPretendingTwoFactorIsVerified(t *testing.T) {
	old := client
	defer func() { client = old }()
	calls := 0
	client = &http.Client{Transport: fakeTransport(func(r *http.Request) (*http.Response, error) {
		calls++
		if r.URL.Host != "oauth.accounts.firefox.com" || r.URL.Path != "/v1/account/login" {
			t.Fatal("wrong sign-in origin or path")
		}
		var body map[string]any
		if json.NewDecoder(r.Body).Decode(&body) != nil {
			t.Fatal("invalid login JSON")
		}
		if _, ok := body["verificationMethod"]; ok {
			t.Fatal("plain login unnecessarily requested two-factor")
		}
		return testResponse(200, `{"uid":"test","sessionToken":"test-token","verified":false,"verificationMethod":"email"}`), nil
	})}
	session, err := SignIn("person@example.invalid", "fake-password")
	if err != nil || session == nil || session.NeedsOTP || session.ByLink || calls != 1 {
		t.Fatalf("plain email session did not proceed to OAuth: %v", err)
	}
}

func TestEmailTwoFactorRemainsPendingWhenRequestedByServer(t *testing.T) {
	old := client
	defer func() { client = old }()
	calls := 0
	client = &http.Client{Transport: fakeTransport(func(r *http.Request) (*http.Response, error) {
		calls++
		var body map[string]any
		if json.NewDecoder(r.Body).Decode(&body) != nil {
			t.Fatal("invalid login JSON")
		}
		if calls == 1 {
			if _, ok := body["verificationMethod"]; ok {
				t.Fatal("unexpected verification method")
			}
			return testResponse(400, `{"errno":107}`), nil
		}
		if body["verificationMethod"] != "email-2fa" {
			t.Fatal("email-2fa was not requested")
		}
		return testResponse(200, `{"uid":"test","sessionToken":"test-token","verified":false,"verificationMethod":"email-2fa"}`), nil
	})}
	session, err := SignIn("person@example.invalid", "fake-password")
	if err != nil || session == nil || !session.NeedsOTP || calls != 2 {
		t.Fatalf("two-factor challenge was not preserved: %v", err)
	}
}
