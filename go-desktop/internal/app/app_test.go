package app

import (
	"encoding/json"
	"io"
	"net/http"
	"os"
	"path/filepath"
	"runtime"
	"strings"
	"testing"

	"github.com/Ammpapsoe/GozarFox/go-desktop/internal/config"
)

func TestStartupUsesTunWithoutChangingSystemProxy(t *testing.T) {
	if runtime.GOOS != "linux" {
		t.Skip("the proxy command is specific to Linux")
	}
	root := t.TempDir()
	t.Setenv("HOME", root)
	t.Setenv("XDG_CONFIG_HOME", root)
	t.Setenv("APPDATA", root)
	t.Setenv("PATH", root)
	marker := filepath.Join(root, "proxy-command-called")
	t.Setenv("GOZAR_PROXY_MARKER", marker)
	command := []byte("#!/bin/sh\nprintf called > \"$GOZAR_PROXY_MARKER\"\n")
	if err := os.WriteFile(filepath.Join(root, "gsettings"), command, 0o700); err != nil {
		t.Fatal(err)
	}
	if err := config.Save(config.Config{FullTunnel: false, Port: 10810}); err != nil {
		t.Fatal(err)
	}
	current := New("test")
	defer current.Close()
	if state := current.State(); state.Mode != "tun" {
		t.Fatalf("got mode %q, want tun", state.Mode)
	}
	if err := current.SetMode(false); err == nil {
		t.Fatal("proxy mode should be rejected")
	}
	if err := current.SetMode(true); err != nil {
		t.Fatal(err)
	}
	if _, err := os.Stat(marker); !os.IsNotExist(err) {
		t.Fatal("startup or mode selection accessed the system proxy")
	}
}

type fakeHTTP func(*http.Request) (*http.Response, error)

func (f fakeHTTP) RoundTrip(request *http.Request) (*http.Response, error) {
	return f(request)
}

func TestVerificationKeepsTheOriginalSession(t *testing.T) {
	root := t.TempDir()
	t.Setenv("HOME", root)
	t.Setenv("XDG_CONFIG_HOME", root)
	t.Setenv("APPDATA", root)
	previous := http.DefaultTransport
	defer func() { http.DefaultTransport = previous }()
	loginCount, verificationCount, tokenCount := 0, 0, 0
	response := func(code int, body string) *http.Response {
		return &http.Response{StatusCode: code, Header: make(http.Header), Body: io.NopCloser(strings.NewReader(body))}
	}
	http.DefaultTransport = fakeHTTP(func(request *http.Request) (*http.Response, error) {
		switch request.URL.Path {
		case "/v1/account/login":
			loginCount++
			return response(200, `{"uid":"example","sessionToken":"a0a1a2a3a4a5a6a7a8a9aaabacadaeafb0b1b2b3b4b5b6b7b8b9babbbcbdbebf","verified":false,"verificationMethod":"email-2fa"}`), nil
		case "/v1/session/verify_code":
			verificationCount++
			if verificationCount == 1 {
				return response(400, `{"message":"code rejected"}`), nil
			}
			return response(200, `{}`), nil
		case "/v1/oauth/token":
			tokenCount++
			return response(503, `{"message":"mock server stopped"}`), nil
		default:
			t.Fatalf("unexpected request to %s", request.URL.Path)
			return nil, nil
		}
	})
	current := New("test")
	defer current.Close()
	if err := current.SignIn("person@example.org", "fake-password", ""); !NeedsCode(err) {
		t.Fatalf("expected a code request, got %v", err)
	}
	if err := current.SignIn("person@example.org", "fake-password", "111111"); err == nil {
		t.Fatal("wrong code should fail")
	}
	if err := current.SignIn("person@example.org", "fake-password", "123456"); err == nil {
		t.Fatal("mock token request should fail")
	}
	if err := current.SignIn("person@example.org", "fake-password", "123456"); err == nil {
		t.Fatal("mock token retry should fail")
	}
	if loginCount != 1 || verificationCount != 2 || tokenCount != 2 {
		t.Fatalf("login=%d verify=%d token=%d", loginCount, verificationCount, tokenCount)
	}
}

func TestBlockedSignInRequestsNewCodesWithoutStartingNewSessions(t *testing.T) {
	root := t.TempDir()
	t.Setenv("HOME", root)
	t.Setenv("XDG_CONFIG_HOME", root)
	t.Setenv("APPDATA", root)
	previous := http.DefaultTransport
	defer func() { http.DefaultTransport = previous }()
	loginCount, sentCount, verifyCount := 0, 0, 0
	response := func(code int, body string) *http.Response {
		return &http.Response{StatusCode: code, Header: make(http.Header), Body: io.NopCloser(strings.NewReader(body))}
	}
	http.DefaultTransport = fakeHTTP(func(request *http.Request) (*http.Response, error) {
		switch request.URL.Path {
		case "/v1/account/login":
			loginCount++
			if loginCount == 1 {
				return response(400, `{"errno":125,"message":"request blocked"}`), nil
			}
			var body map[string]string
			if err := json.NewDecoder(request.Body).Decode(&body); err != nil {
				t.Fatal(err)
			}
			if body["unblockCode"] != "ABC123" {
				t.Fatal("unblock code was not included")
			}
			return response(200, `{"uid":"example","sessionToken":"a0a1a2a3a4a5a6a7a8a9aaabacadaeafb0b1b2b3b4b5b6b7b8b9babbbcbdbebf","verified":false,"verificationMethod":"email-2fa"}`), nil
		case "/v1/account/login/send_unblock_code":
			sentCount++
			return response(200, `{}`), nil
		case "/v1/session/verify_code":
			verifyCount++
			return response(200, `{}`), nil
		case "/v1/oauth/token":
			return response(503, `{"message":"mock server stopped"}`), nil
		default:
			t.Fatalf("unexpected request to %s", request.URL.Path)
			return nil, nil
		}
	})
	current := New("test")
	defer current.Close()
	if err := current.SignIn("person@example.org", "fake-password", ""); !NeedsCode(err) {
		t.Fatalf("expected unblock code, got %v", err)
	}
	if err := current.SignIn("person@example.org", "fake-password", "abc123"); !NeedsCode(err) {
		t.Fatalf("expected a separate session code, got %v", err)
	}
	if verifyCount != 0 {
		t.Fatal("the unblock code must not be reused as the session code")
	}
	if err := current.SignIn("person@example.org", "fake-password", "123456"); err == nil {
		t.Fatal("mock token request should fail")
	}
	if loginCount != 2 || sentCount != 1 || verifyCount != 1 {
		t.Fatalf("login=%d sent=%d verify=%d", loginCount, sentCount, verifyCount)
	}
}

func TestRegistrationRetainsSessionForEmailAndTokenRetries(t *testing.T) {
	root := t.TempDir()
	t.Setenv("HOME", root)
	t.Setenv("XDG_CONFIG_HOME", root)
	t.Setenv("APPDATA", root)
	previous := http.DefaultTransport
	defer func() { http.DefaultTransport = previous }()
	createCount, verificationCount, tokenCount := 0, 0, 0
	response := func(code int, body string) *http.Response {
		return &http.Response{StatusCode: code, Header: make(http.Header), Body: io.NopCloser(strings.NewReader(body))}
	}
	http.DefaultTransport = fakeHTTP(func(request *http.Request) (*http.Response, error) {
		switch request.URL.Path {
		case "/v1/account/create":
			createCount++
			return response(200, `{"uid":"example","sessionToken":"a0a1a2a3a4a5a6a7a8a9aaabacadaeafb0b1b2b3b4b5b6b7b8b9babbbcbdbebf","verified":false}`), nil
		case "/v1/recovery_email/verify_code":
			verificationCount++
			var body map[string]string
			if json.NewDecoder(request.Body).Decode(&body) != nil || body["uid"] != "example" {
				t.Fatal("registration used the wrong account")
			}
			if body["code"] != "123456" {
				return response(400, `{"message":"code rejected"}`), nil
			}
			return response(200, `{}`), nil
		case "/v1/oauth/token":
			tokenCount++
			return response(503, `{"message":"mock server stopped"}`), nil
		default:
			t.Fatalf("unexpected request to %s", request.URL.Path)
			return nil, nil
		}
	})
	current := New("test")
	defer current.Close()
	if err := current.Authenticate("person@example.org", "fake-password", "", "", true); !NeedsCode(err) {
		t.Fatalf("expected a registration code, got %v", err)
	}
	if err := current.Authenticate("person@example.org", "fake-password", "111111", "", true); err == nil {
		t.Fatal("wrong registration code should fail")
	}
	if err := current.Authenticate("person@example.org", "fake-password", "123456", "", true); err == nil {
		t.Fatal("mock token request should fail")
	}
	if err := current.Authenticate("person@example.org", "fake-password", "123456", "", true); err == nil {
		t.Fatal("mock token retry should fail")
	}
	if createCount != 1 || verificationCount != 2 || tokenCount != 2 {
		t.Fatalf("create=%d verify=%d token=%d", createCount, verificationCount, tokenCount)
	}
}
