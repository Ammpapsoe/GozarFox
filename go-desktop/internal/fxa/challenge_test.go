package fxa

import (
	"crypto/sha256"
	"encoding/hex"
	"encoding/json"
	"errors"
	"io"
	"net/http"
	"strings"
	"testing"
)

const testImage = "data:image/png;base64,iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO8B+ekAAAAASUVORK5CYII="

func TestParseChallengeAndProof(t *testing.T) {
	script := `junk.init([1,2]);init([{"ty":"pow","data":{"base":"base","hash":"value","nested":[1,2]}},{"ty":"pat","data":{}}],"token-one","/_fs-ch-example")`
	tasks, token, err := parseChallenge(script)
	if err != nil || token != "token-one" || len(tasks) != 2 || tasks[0].Type != "pow" || tasks[1].Type != "pat" {
		t.Fatalf("unexpected challenge: %v %q %v", tasks, token, err)
	}
	sum := sha256.Sum256([]byte("baseXk"))
	suffix, ok := proofOfWork("base", hex.EncodeToString(sum[:]))
	if !ok || suffix != "Xk" {
		t.Fatalf("unexpected proof %q", suffix)
	}
	if _, ok := proofOfWork("base", "not-hex"); ok {
		t.Fatal("accepted invalid hash")
	}
}

func TestChallengeRetriesLoginWithCookies(t *testing.T) {
	old := client
	defer func() { client = old; CancelChallenge() }()
	client = newHTTPClient()
	requests := 0
	sum := sha256.Sum256([]byte("baseXk"))
	hash := hex.EncodeToString(sum[:])
	client.Transport = fakeTransport(func(r *http.Request) (*http.Response, error) {
		switch {
		case r.URL.Host == "oauth.accounts.firefox.com" && r.URL.Path == "/v1/account/login":
			requests++
			if requests == 1 {
				return testResponse(406, ""), nil
			}
			if c, err := r.Cookie("_fs_ch_cp_test"); err != nil || c.Value != "passed" {
				t.Fatal("solved cookie was not sent to the API")
			}
			return testResponse(200, `{"uid":"test-uid","sessionToken":"token","verified":true}`), nil
		case r.URL.Host == "api.accounts.firefox.com" && r.URL.Path == "/":
			return testResponse(200, "no challenge"), nil
		case r.URL.Host == "accounts.firefox.com" && r.URL.Path == "/":
			if _, err := r.Cookie("_fs_ch_cp_test"); err == nil {
				return testResponse(200, "welcome"), nil
			}
			res := testResponse(200, "Client Challenge /_fs-ch-test")
			res.Header.Add("Set-Cookie", "_fs_ch_st_test=start; Path=/")
			return res, nil
		case r.URL.Path == "/_fs-ch-test/script.js":
			return testResponse(200, `init([{"ty":"pat","data":{}},{"ty":"pow","data":{"base":"base","hash":"`+hash+`","hmac":"h","expires":"123"}}],"token","/_fs-ch-test")`), nil
		case r.URL.Path == "/_fs-ch-test/pat":
			return testResponse(401, ""), nil
		case r.URL.Path == "/_fs-ch-test/fst-post-back":
			var request struct {
				Token string           `json:"token"`
				Data  []map[string]any `json:"data"`
			}
			if json.NewDecoder(r.Body).Decode(&request) != nil || request.Token != "token" || len(request.Data) != 2 || request.Data[1]["answer"] != "Xk" {
				t.Fatal("incorrect security-check response")
			}
			if r.Header.Get("Authorization") != "" {
				t.Fatal("account credentials were sent to the security-check host")
			}
			res := testResponse(200, `{"status":"success"}`)
			res.Header.Add("Set-Cookie", "_fs_ch_cp_test=passed; Path=/")
			return res, nil
		default:
			t.Fatalf("unexpected request %s %s", r.Method, r.URL)
			return nil, nil
		}
	})
	session, err := SignIn("person@example.invalid", "fake-password")
	if err != nil || session == nil || session.UID != "test-uid" || requests != 2 {
		t.Fatalf("login retry failed: %v", err)
	}
}

func TestCaptchaDisplayedAndRetriedInsideApp(t *testing.T) {
	old := client
	defer func() { client = old; CancelChallenge() }()
	client = newHTTPClient()
	attempts := 0
	client.Transport = fakeTransport(func(r *http.Request) (*http.Response, error) {
		switch {
		case r.URL.Host == "oauth.accounts.firefox.com" && r.URL.Path == "/v1/account/login":
			if _, err := r.Cookie("_fs_ch_cp_test"); err != nil {
				return testResponse(406, ""), nil
			}
			return testResponse(200, `{"uid":"test-uid","sessionToken":"token","verified":true}`), nil
		case r.URL.Host == "api.accounts.firefox.com" && r.URL.Path == "/":
			return testResponse(200, "no challenge"), nil
		case r.URL.Host == "accounts.firefox.com" && r.URL.Path == "/":
			if _, err := r.Cookie("_fs_ch_cp_test"); err == nil {
				return testResponse(200, "welcome"), nil
			}
			return testResponse(200, "Client Challenge /_fs-ch-test"), nil
		case r.URL.Path == "/_fs-ch-test/script.js":
			return testResponse(200, `init([{"ty":"captcha","data":{"image_b64":"`+testImage+`"}}],"token","/_fs-ch-test")`), nil
		case r.URL.Path == "/_fs-ch-test/fst-post-back":
			var request struct {
				Data []map[string]any `json:"data"`
			}
			if json.NewDecoder(r.Body).Decode(&request) != nil || len(request.Data) != 1 {
				t.Fatal("invalid captcha answer")
			}
			attempts++
			if attempts == 1 {
				return testResponse(200, `{"ch":[{"ty":"captcha","data":{"image_b64":"`+testImage+`"}}],"tok":"another"}`), nil
			}
			if request.Data[0]["answer"] != "correct" {
				t.Fatal("second answer was not received")
			}
			res := testResponse(200, `{"status":"success"}`)
			res.Header.Add("Set-Cookie", "_fs_ch_cp_test=passed; Path=/")
			return res, nil
		default:
			t.Fatalf("unexpected request %s %s", r.Method, r.URL)
			return nil, nil
		}
	})
	_, err := SignIn("person@example.invalid", "fake-password")
	var challenge *ChallengeRequired
	if !errors.As(err, &challenge) || challenge.Image != testImage {
		t.Fatalf("expected an image challenge, got %v", err)
	}
	err = SubmitChallenge("wrong")
	if !errors.As(err, &challenge) || challenge.Image != testImage {
		t.Fatalf("expected another image challenge, got %v", err)
	}
	if err := SubmitChallenge("correct"); err != nil {
		t.Fatal(err)
	}
	if _, err := SignIn("person@example.invalid", "fake-password"); err != nil {
		t.Fatalf("login was not retried after the security check: %v", err)
	}
	if attempts != 2 {
		t.Fatal("unexpected number of captcha answers")
	}
}

func TestRegistrationAndEmailVerification(t *testing.T) {
	old := client
	defer func() { client = old; CancelChallenge() }()
	client = newHTTPClient()
	client.Transport = fakeTransport(func(r *http.Request) (*http.Response, error) {
		body, _ := io.ReadAll(r.Body)
		var data map[string]string
		json.Unmarshal(body, &data)
		switch r.URL.Path {
		case "/v1/account/create":
			if data["email"] != "person@example.invalid" || data["authPW"] != hex.EncodeToString(AuthPW(data["email"], "fake-password")) || data["verificationMethod"] != "email-otp" {
				t.Fatal("incorrect registration request")
			}
			return testResponse(200, `{"uid":"test-uid","sessionToken":"test-token","verified":false}`), nil
		case "/v1/recovery_email/verify_code":
			if data["uid"] != "test-uid" || data["code"] != "123456" {
				t.Fatal("incorrect registration verification")
			}
			return testResponse(200, `{}`), nil
		default:
			t.Fatalf("unexpected request %s", r.URL.Path)
			return nil, nil
		}
	})
	session, err := Register(" person@example.invalid ", "fake-password")
	if err != nil || session == nil || !session.NeedsOTP || session.Email != "person@example.invalid" {
		t.Fatalf("registration failed: %v", err)
	}
	if err := VerifySignup(session.UID, " 123456 "); err != nil {
		t.Fatalf("verification failed: %v", err)
	}
}

func TestChallengeRejectsInvalidImage(t *testing.T) {
	for _, image := range []string{"https://other.example/image", "data:image/svg+xml;base64,PHN2Zy8+", "data:image/png;base64,???"} {
		data, _ := json.Marshal(map[string]string{"image_b64": image})
		var challenge *ChallengeRequired
		err := challengeImage(challengeTask{Type: "captcha", Data: data})
		if err == nil || errors.As(err, &challenge) || !strings.Contains(err.Error(), "image") {
			t.Fatalf("accepted invalid image %q", image)
		}
	}
}
