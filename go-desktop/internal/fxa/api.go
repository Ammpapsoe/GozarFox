package fxa

import (
	"bytes"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"net/http"
	"strings"
)

const (
	userAgent   = "Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:158.0) Gecko/20100101 Firefox/158.0"
	clientID    = "5882386c6d801776"
	scope       = "profile https://identity.mozilla.com/apps/vpn"
	authBase    = "https://oauth.accounts.firefox.com/v1"
	accountBase = "https://api.accounts.firefox.com/v1"
	refreshURL  = "https://oauth.accounts.firefox.com/v1/token"
	guardianURL = "https://vpn.mozilla.org"
	serversURL  = "https://firefox.settings.services.mozilla.com/v1/buckets/main/collections/vpn-serverlist/records"
)

var client = newHTTPClient()

type Reply struct {
	Code    int
	Body    []byte
	Headers http.Header
}

type Error struct {
	Message string
	Status  int
	Errno   int
	Email   string
}

func (e *Error) Error() string { return e.Message }

func call(method, url, body string, headers map[string]string) (*Reply, error) {
	for attempt := 0; attempt < 3; attempt++ {
		reply, err := callOnce(method, url, body, headers)
		if err != nil || reply.Code != http.StatusNotAcceptable ||
			(!strings.HasPrefix(url, authBase+"/") && !strings.HasPrefix(url, accountBase+"/")) {
			return reply, err
		}
		if err := solveChallenge(); err != nil {
			return nil, err
		}
	}
	return nil, errors.New("Mozilla security check was not accepted; try again")
}

func callOnce(method, url, body string, headers map[string]string) (*Reply, error) {
	var reader io.Reader
	if body != "" {
		reader = strings.NewReader(body)
	}
	request, err := http.NewRequest(method, url, reader)
	if err != nil {
		return nil, err
	}
	request.Header.Set("User-Agent", userAgent)
	request.Header.Set("Accept", "application/json")
	if strings.HasPrefix(url, authBase+"/") || strings.HasPrefix(url, accountBase+"/") {
		request.Header.Set("Accept-Encoding", "identity")
	}
	if body != "" {
		request.Header.Set("Content-Type", "application/json")
	}
	for name, value := range headers {
		request.Header.Set(name, value)
	}
	response, err := client.Do(request)
	if err != nil {
		return nil, err
	}
	defer response.Body.Close()
	payload, err := io.ReadAll(io.LimitReader(response.Body, 1<<22))
	if err != nil {
		return nil, err
	}
	return &Reply{Code: response.StatusCode, Body: payload, Headers: response.Header}, nil
}

func failure(reply *Reply) error {
	var data struct {
		Message string `json:"message"`
		Code    string `json:"code"`
		Errno   int    `json:"errno"`
		Email   string `json:"email"`
	}
	json.Unmarshal(reply.Body, &data)
	message := data.Message
	if message == "" {
		message = data.Code
	}
	if message == "" {
		message = fmt.Sprintf("HTTP %d", reply.Code)
	}
	if reply.Code == 406 {
		message = "Mozilla security check was not accepted; try again"
	}
	return &Error{Message: message, Status: reply.Code, Errno: data.Errno, Email: data.Email}
}

func decode(reply *Reply, target any) error {
	if reply.Code < 200 || reply.Code > 299 {
		return failure(reply)
	}
	if err := json.Unmarshal(reply.Body, target); err != nil {
		return errors.New("unexpected response from Mozilla")
	}
	return nil
}

type Session struct {
	UID                string
	Token              string
	Email              string
	NeedsOTP           bool
	ByLink             bool
	VerificationMethod string
}

type Tokens struct {
	Access    string `json:"access_token"`
	Refresh   string `json:"refresh_token"`
	ExpiresIn int    `json:"expires_in"`
}

type Pass struct {
	Token     string
	Limit     int64
	Remaining int64
	Reset     string
}

type signInReply struct {
	UID                string `json:"uid"`
	SessionToken       string `json:"sessionToken"`
	Verified           *bool  `json:"verified"`
	EmailVerified      *bool  `json:"emailVerified"`
	SessionVerified    *bool  `json:"sessionVerified"`
	VerificationMethod string `json:"verificationMethod"`
}

func SignIn(email, password string) (*Session, error) {
	return signIn(email, password, "")
}

func SignInWithUnblockCode(email, password, code string) (*Session, error) {
	code = strings.ToUpper(strings.Join(strings.Fields(code), ""))
	return signIn(email, password, code)
}

func signIn(email, password, unblock string) (*Session, error) {
	email = strings.TrimSpace(email)
	session, err := signInAs(email, password, "", unblock)
	var apiError *Error
	if errors.As(err, &apiError) && apiError.Errno == 120 && apiError.Email != "" && apiError.Email != email {
		return signInAs(apiError.Email, password, email, unblock)
	}
	return session, err
}

func signInAs(email, password, original, unblock string) (*Session, error) {
	body := map[string]any{
		"email":  email,
		"authPW": fmt.Sprintf("%x", AuthPW(email, password)),
	}
	if original != "" {
		body["originalLoginEmail"] = original
	}
	if unblock != "" {
		body["unblockCode"] = unblock
	}
	payload, _ := json.Marshal(body)
	reply, err := call("POST", authBase+"/account/login", string(payload), nil)
	if err != nil {
		return nil, err
	}
	if reply.Code == 400 && replyCode(reply.Body) == 107 {
		body["verificationMethod"] = "email-2fa"
		payload, _ = json.Marshal(body)
		if reply, err = call("POST", authBase+"/account/login", string(payload), nil); err != nil {
			return nil, err
		}
	}
	session, err := readSession(reply)
	if err == nil {
		session.Email = email
		if session.VerificationMethod == "email" {
			// Let Mozilla decide whether this normal email-method sign-in
			// session is eligible for OAuth; this is not an OTP bypass.
			session.NeedsOTP = false
			session.ByLink = false
		}
	}
	return session, err
}

func SendUnblockCode(email string) error {
	body, _ := json.Marshal(map[string]string{"email": strings.TrimSpace(email)})
	reply, err := call("POST", authBase+"/account/login/send_unblock_code", string(body), nil)
	if err != nil {
		return err
	}
	if reply.Code < 200 || reply.Code > 299 {
		return failure(reply)
	}
	return nil
}

func replyCode(body []byte) int {
	var data struct {
		Errno int `json:"errno"`
	}
	json.Unmarshal(body, &data)
	return data.Errno
}

func readSession(reply *Reply) (*Session, error) {
	var data signInReply
	if err := decode(reply, &data); err != nil {
		return nil, err
	}
	if data.UID == "" || data.SessionToken == "" {
		return nil, errors.New("Mozilla did not return a valid session")
	}
	verified := data.Verified != nil && *data.Verified
	if data.Verified == nil && data.EmailVerified != nil {
		verified = *data.EmailVerified && (data.SessionVerified == nil || *data.SessionVerified)
	}
	return &Session{
		UID:                data.UID,
		Token:              data.SessionToken,
		NeedsOTP:           !verified,
		ByLink:             data.VerificationMethod == "email",
		VerificationMethod: data.VerificationMethod,
	}, nil
}

func Register(email, password string) (*Session, error) {
	email = strings.TrimSpace(email)
	body, _ := json.Marshal(map[string]any{
		"email":              email,
		"authPW":             fmt.Sprintf("%x", AuthPW(email, password)),
		"service":            "sync",
		"verificationMethod": "email-otp",
	})
	reply, err := call("POST", accountBase+"/account/create", string(body), nil)
	if err != nil {
		return nil, err
	}
	session, err := readSession(reply)
	if err != nil {
		return nil, err
	}
	session.Email = email
	return session, nil
}

func VerifySignup(uid, code string) error {
	body, _ := json.Marshal(map[string]string{"uid": uid, "code": strings.TrimSpace(code)})
	reply, err := call("POST", accountBase+"/recovery_email/verify_code", string(body), nil)
	if err != nil {
		return err
	}
	if reply.Code < 200 || reply.Code > 299 {
		return failure(reply)
	}
	return nil
}

func VerifyCode(sessionToken, code, method string) error {
	body, _ := json.Marshal(map[string]string{"code": strings.TrimSpace(code)})
	url := authBase + "/session/verify_code"
	if method == "totp-2fa" {
		url = authBase + "/session/verify/totp"
	}
	header := HawkHeader("POST", url, sessionToken, string(body))
	if header == "" {
		return errors.New("invalid session token")
	}
	reply, err := call("POST", url, string(body), map[string]string{"Authorization": header})
	if err != nil {
		return err
	}
	if reply.Code < 200 || reply.Code > 299 {
		return failure(reply)
	}
	if method == "totp-2fa" {
		var result struct {
			Success bool `json:"success"`
		}
		if err := json.Unmarshal(reply.Body, &result); err != nil || !result.Success {
			return errors.New("The code was not accepted")
		}
	}
	return nil
}

func SessionTokens(sessionToken string) (*Tokens, error) {
	body, _ := json.Marshal(map[string]string{
		"client_id":   clientID,
		"grant_type":  "fxa-credentials",
		"scope":       scope,
		"access_type": "offline",
	})
	url := authBase + "/oauth/token"
	header := HawkHeader("POST", url, sessionToken, string(body))
	if header == "" {
		return nil, errors.New("invalid session token")
	}
	reply, err := call("POST", url, string(body), map[string]string{"Authorization": header})
	if err != nil {
		return nil, err
	}
	var out Tokens
	if err := decode(reply, &out); err != nil {
		return nil, err
	}
	return &out, nil
}

func Refresh(refreshToken string) (*Tokens, error) {
	body, _ := json.Marshal(map[string]string{
		"client_id":     clientID,
		"grant_type":    "refresh_token",
		"refresh_token": refreshToken,
		"scope":         scope,
	})
	reply, err := call("POST", refreshURL, string(body), nil)
	if err != nil {
		return nil, err
	}
	var out Tokens
	if err := decode(reply, &out); err != nil {
		return nil, err
	}
	if out.Refresh == "" {
		out.Refresh = refreshToken
	}
	return &out, nil
}

func quota(reply *Reply) *Pass {
	if strings.EqualFold(strings.TrimSpace(reply.Headers.Get("X-Quota-Unlimited")), "true") {
		return &Pass{Limit: -1, Remaining: -1}
	}
	limit, err := parseInt(reply.Headers.Get("X-Quota-Limit"))
	if err != nil {
		return nil
	}
	remaining, err := parseInt(reply.Headers.Get("X-Quota-Remaining"))
	if err != nil {
		return nil
	}
	return &Pass{Limit: limit, Remaining: remaining, Reset: reply.Headers.Get("X-Quota-Reset")}
}

func parseInt(text string) (int64, error) {
	var value int64
	_, err := fmt.Sscan(strings.TrimSpace(text), &value)
	return value, err
}

func PassToken(access string) (*Pass, error) {
	reply, err := call("GET", guardianURL+"/api/v1/fpn/token", "", map[string]string{
		"Authorization": "Bearer " + access,
	})
	if err != nil {
		return nil, err
	}
	if reply.Code == 403 {
		call("POST", guardianURL+"/api/v1/fpn/activate", "{}", map[string]string{
			"Authorization": "Bearer " + access,
		})
		reply, err = call("GET", guardianURL+"/api/v1/fpn/token", "", map[string]string{
			"Authorization": "Bearer " + access,
		})
		if err != nil {
			return nil, err
		}
	}
	if reply.Code != 200 {
		return nil, failure(reply)
	}
	var data struct {
		Token string `json:"token"`
	}
	json.Unmarshal(reply.Body, &data)
	if data.Token == "" {
		return nil, errors.New("Mozilla did not return a proxy pass")
	}
	pass := quota(reply)
	if pass == nil {
		pass = &Pass{}
	}
	pass.Token = data.Token
	return pass, nil
}

func Usage(access string) (*Pass, error) {
	reply, err := call("HEAD", guardianURL+"/api/v1/fpn/token", "", map[string]string{
		"Authorization": "Bearer " + access,
	})
	if err != nil || reply.Code != 200 {
		return nil, errors.New("usage is not available")
	}
	return quota(reply), nil
}

type Location struct {
	Code        string `json:"code"`
	Country     string `json:"country"`
	City        string `json:"city"`
	Host        string `json:"host"`
	Port        int    `json:"port"`
	Recommended bool   `json:"recommended"`
}

type serverCity struct {
	Name    string `json:"name"`
	Servers []struct {
		Hostname    string `json:"hostname"`
		Port        int    `json:"port"`
		Quarantined bool   `json:"quarantined"`
		Protocols   []struct {
			Scheme string `json:"scheme"`
			Host   string `json:"host"`
			Port   int    `json:"port"`
		} `json:"protocols"`
	} `json:"servers"`
}

func Servers() ([]Location, error) {
	reply, err := call("GET", serversURL, "", nil)
	if err != nil {
		return nil, err
	}
	if reply.Code != 200 {
		return nil, failure(reply)
	}
	var data struct {
		Data []struct {
			Code   string       `json:"code"`
			Name   string       `json:"name"`
			Cities []serverCity `json:"cities"`
		} `json:"data"`
	}
	if err := json.Unmarshal(reply.Body, &data); err != nil {
		return nil, err
	}
	var out []Location
	for _, country := range data.Data {
		if country.Code == "" {
			continue
		}
		for _, city := range country.Cities {
			host, port := pickServer(city)
			if host == "" {
				continue
			}
			out = append(out, Location{
				Code:        country.Code,
				Country:     country.Name,
				City:        city.Name,
				Host:        host,
				Port:        port,
				Recommended: country.Code == "REC",
			})
		}
	}
	return out, nil
}

func pickServer(city serverCity) (string, int) {
	for _, server := range city.Servers {
		if server.Quarantined {
			continue
		}
		for _, proto := range server.Protocols {
			if proto.Scheme != "" && !strings.EqualFold(proto.Scheme, "https") {
				continue
			}
			host := proto.Host
			if host == "" {
				host = server.Hostname
			}
			port := proto.Port
			if port == 0 {
				port = server.Port
			}
			if port == 0 {
				port = 443
			}
			if host != "" {
				return host, port
			}
		}
		if server.Hostname != "" {
			port := server.Port
			if port == 0 {
				port = 443
			}
			return server.Hostname, port
		}
	}
	return "", 0
}

func SpliceJSON(raw []byte) string {
	return string(bytes.TrimSpace(raw))
}
