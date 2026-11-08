package fxa

import (
	"bytes"
	"crypto/sha256"
	"encoding/base64"
	"encoding/hex"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"net/http"
	"net/http/cookiejar"
	"net/url"
	"regexp"
	"strings"
	"sync"
	"time"

	"golang.org/x/net/publicsuffix"
)

const challengeAgent = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0 Safari/537.36"
const challengeAlphabet = "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789"

var challengePath = regexp.MustCompile(`/_fs-ch-[A-Za-z0-9]+`)
var challengeInit = regexp.MustCompile(`\binit\s*\(`)
var challengeLock sync.Mutex
var pendingChallenge *challengeState

type ChallengeRequired struct {
	Image string
}

func (e *ChallengeRequired) Error() string {
	return "Mozilla requires a security check"
}

type challengeTask struct {
	Type string          `json:"ty"`
	Data json.RawMessage `json:"data"`
}

type challengeState struct {
	client  *http.Client
	base    string
	path    string
	token   string
	tasks   []challengeTask
	answers []any
	index   int
	rounds  int
	created time.Time
}

func newHTTPClient() *http.Client {
	jar, _ := cookiejar.New(&cookiejar.Options{PublicSuffixList: publicsuffix.List})
	return &http.Client{Timeout: 25 * time.Second, Jar: jar}
}

func solveChallenge() error {
	challengeLock.Lock()
	defer challengeLock.Unlock()
	if pendingChallenge != nil {
		if time.Since(pendingChallenge.created) < 2*time.Minute {
			return challengeImage(pendingChallenge.tasks[pendingChallenge.index])
		}
		pendingChallenge = nil
	}
	var last error
	for _, base := range []string{"https://api.accounts.firefox.com", "https://accounts.firefox.com"} {
		state, err := prepareChallenge(base)
		if err != nil {
			last = err
			continue
		}
		if err = state.advance(""); err != nil {
			var required *ChallengeRequired
			if errors.As(err, &required) {
				pendingChallenge = state
				return err
			}
			last = err
			continue
		}
		return nil
	}
	if last == nil {
		last = errors.New("challenge page unavailable")
	}
	return fmt.Errorf("Mozilla security check could not be completed: %w", last)
}

func SubmitChallenge(answer string) error {
	challengeLock.Lock()
	defer challengeLock.Unlock()
	state := pendingChallenge
	if state == nil || time.Since(state.created) >= 2*time.Minute {
		pendingChallenge = nil
		return errors.New("security check expired; try again")
	}
	answer = strings.TrimSpace(answer)
	if answer == "" || len(answer) > 256 {
		return errors.New("enter the security check answer")
	}
	err := state.advance(answer)
	var required *ChallengeRequired
	if !errors.As(err, &required) {
		pendingChallenge = nil
	}
	return err
}

func CancelChallenge() {
	challengeLock.Lock()
	pendingChallenge = nil
	challengeLock.Unlock()
}

func prepareChallenge(base string) (*challengeState, error) {
	jar, err := cookiejar.New(&cookiejar.Options{PublicSuffixList: publicsuffix.List})
	if err != nil {
		return nil, err
	}
	transport := client.Transport
	fresh := &http.Client{
		Transport: transport,
		Jar:       jar,
		Timeout:   15 * time.Second,
		CheckRedirect: func(req *http.Request, via []*http.Request) error {
			if len(via) >= 5 || req.URL.Scheme != via[0].URL.Scheme || req.URL.Host != via[0].URL.Host {
				return errors.New("unexpected security-check redirect")
			}
			return nil
		},
	}
	state := &challengeState{client: fresh, base: base, created: time.Now()}
	page, err := state.fetch("GET", base+"/", "", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
	if err != nil {
		return nil, err
	}
	if !strings.Contains(string(page), "Client Challenge") {
		return nil, errors.New("no challenge on account host")
	}
	state.path = challengePath.FindString(string(page))
	if state.path == "" {
		return nil, errors.New("unknown security-check page")
	}
	script, err := state.fetch("GET", base+state.path+"/script.js?reload=true", "", "*/*")
	if err != nil {
		return nil, err
	}
	state.tasks, state.token, err = parseChallenge(string(script))
	if err != nil {
		return nil, err
	}
	return state, nil
}

func parseChallenge(script string) ([]challengeTask, string, error) {
	matches := challengeInit.FindAllStringIndex(script, -1)
	for n := len(matches) - 1; n >= 0; n-- {
		i := matches[n][1]
		for i < len(script) && strings.ContainsRune(" \t\r\n", rune(script[i])) {
			i++
		}
		if i >= len(script) || script[i] != '[' {
			continue
		}
		start, depth, quoted, escaped := i, 0, false, false
		for ; i < len(script); i++ {
			c := script[i]
			if quoted {
				if escaped {
					escaped = false
				} else if c == '\\' {
					escaped = true
				} else if c == '"' {
					quoted = false
				}
				continue
			}
			if c == '"' {
				quoted = true
			} else if c == '[' {
				depth++
			} else if c == ']' {
				depth--
				if depth == 0 {
					i++
					break
				}
			}
		}
		if depth != 0 || i > len(script) {
			continue
		}
		var tasks []challengeTask
		if json.Unmarshal([]byte(script[start:i]), &tasks) != nil || len(tasks) == 0 || len(tasks) > 20 {
			continue
		}
		rest := strings.TrimSpace(script[i:])
		if !strings.HasPrefix(rest, ",") {
			continue
		}
		rest = strings.TrimSpace(rest[1:])
		var token string
		if json.NewDecoder(strings.NewReader(rest)).Decode(&token) == nil && token != "" && len(token) <= 8192 {
			return tasks, token, nil
		}
	}
	return nil, "", errors.New("unknown security-check script")
}

func (s *challengeState) advance(answer string) error {
	for s.rounds < 8 {
		for s.index < len(s.tasks) {
			task := s.tasks[s.index]
			var value any
			switch task.Type {
			case "pow":
				var data struct {
					Base    string `json:"base"`
					Hash    string `json:"hash"`
					HMAC    string `json:"hmac"`
					Expires string `json:"expires"`
				}
				if json.Unmarshal(task.Data, &data) != nil || len(data.Base) > 4096 {
					return errors.New("invalid security-check proof")
				}
				suffix, ok := proofOfWork(data.Base, data.Hash)
				if !ok {
					return errors.New("security-check proof was not accepted")
				}
				value = map[string]any{"ty": "pow", "base": data.Base, "answer": suffix, "hmac": data.HMAC, "expires": data.Expires}
			case "pat":
				auth, err := s.privateToken()
				if err != nil {
					return err
				}
				value = map[string]any{"ty": "pat", "auth": auth}
			case "clientmetrics":
				value = map[string]any{
					"ty": "clientmetrics", "webdriver": false,
					"bot_detection_result": map[string]any{"bot_detected": false, "bot_kind": nil},
					"browser_metrics":      map[string]any{"client_data": "{}", "error_trace": nil},
					"detector_results":     map[string]any{}, "v": 2,
				}
			case "captcha":
				if answer == "" {
					return challengeImage(task)
				}
				value = map[string]any{"ty": "captcha", "answer": answer}
				answer = ""
			default:
				return fmt.Errorf("unsupported security-check type: %s", task.Type)
			}
			s.answers = append(s.answers, value)
			s.index++
		}
		body, err := json.Marshal(map[string]any{"token": s.token, "data": s.answers})
		if err != nil {
			return err
		}
		payload, err := s.fetch("POST", s.base+s.path+"/fst-post-back", string(body), "application/json")
		if err != nil {
			return err
		}
		var result struct {
			Status string          `json:"status"`
			Tasks  []challengeTask `json:"ch"`
			Token  string          `json:"tok"`
		}
		if json.Unmarshal(payload, &result) != nil {
			return errors.New("invalid security-check response")
		}
		if result.Status == "success" {
			page, err := s.fetch("GET", s.base+"/", "", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
			if err != nil || strings.Contains(string(page), "Client Challenge") {
				return errors.New("security-check cookie was not accepted")
			}
			return s.installCookies()
		}
		if len(result.Tasks) == 0 || len(result.Tasks) > 20 || result.Token == "" || len(result.Token) > 8192 {
			return errors.New("security-check response was incomplete")
		}
		s.tasks, s.token = result.Tasks, result.Token
		s.answers, s.index = nil, 0
		s.rounds++
	}
	return errors.New("security check did not finish")
}

func challengeImage(task challengeTask) error {
	var data struct {
		Image string `json:"image_b64"`
	}
	if json.Unmarshal(task.Data, &data) != nil {
		return errors.New("invalid security-check image")
	}
	parts := strings.SplitN(data.Image, ",", 2)
	if len(parts) != 2 || len(parts[1]) > 700000 ||
		(parts[0] != "data:image/png;base64" && parts[0] != "data:image/jpeg;base64" && parts[0] != "data:image/webp;base64") {
		return errors.New("unsupported security-check image")
	}
	if _, err := base64.StdEncoding.DecodeString(parts[1]); err != nil {
		return errors.New("invalid security-check image")
	}
	return &ChallengeRequired{Image: data.Image}
}

func proofOfWork(base, target string) (string, bool) {
	expected, err := hex.DecodeString(target)
	if err != nil || len(expected) != sha256.Size || len(base) > 4096 {
		return "", false
	}
	candidate := append([]byte(base), 0, 0)
	for i := range challengeAlphabet {
		candidate[len(candidate)-2] = challengeAlphabet[i]
		for j := range challengeAlphabet {
			candidate[len(candidate)-1] = challengeAlphabet[j]
			sum := sha256.Sum256(candidate)
			if bytes.Equal(sum[:], expected) {
				return string(candidate[len(candidate)-2:]), true
			}
		}
	}
	return "", false
}

func (s *challengeState) privateToken() (string, error) {
	raw := s.base + s.path + "/pat?token=" + url.QueryEscape(s.token)
	req, err := http.NewRequest("POST", raw, nil)
	if err != nil {
		return "", err
	}
	req.Header.Set("User-Agent", challengeAgent)
	req.Header.Set("Accept", "text/plain")
	req.Header.Set("Content-Type", "application/json")
	req.Header.Set("Origin", s.base)
	response, err := s.client.Do(req)
	if err != nil {
		return "", err
	}
	defer response.Body.Close()
	if response.StatusCode == 400 || response.StatusCode == 401 {
		return "", nil
	}
	if response.StatusCode != 200 {
		return "", fmt.Errorf("security-check token returned HTTP %d", response.StatusCode)
	}
	var data struct {
		Auth string `json:"auth"`
	}
	if err := json.NewDecoder(io.LimitReader(response.Body, 1<<20)).Decode(&data); err != nil {
		return "", err
	}
	return data.Auth, nil
}

func (s *challengeState) fetch(method, address, body, accept string) ([]byte, error) {
	var reader io.Reader
	if body != "" {
		reader = strings.NewReader(body)
	}
	req, err := http.NewRequest(method, address, reader)
	if err != nil {
		return nil, err
	}
	req.Header.Set("User-Agent", challengeAgent)
	req.Header.Set("Accept", accept)
	if method == "POST" {
		req.Header.Set("Content-Type", "application/json")
		req.Header.Set("Origin", s.base)
	}
	response, err := s.client.Do(req)
	if err != nil {
		return nil, err
	}
	defer response.Body.Close()
	if response.StatusCode != 200 {
		return nil, fmt.Errorf("security-check request returned HTTP %d", response.StatusCode)
	}
	return io.ReadAll(io.LimitReader(response.Body, 2<<20))
}

func (s *challengeState) installCookies() error {
	if client.Jar == nil {
		return errors.New("security-check cookie storage is unavailable")
	}
	source, _ := url.Parse(s.base + "/")
	api, _ := url.Parse("https://api.accounts.firefox.com/")
	oauth, _ := url.Parse("https://oauth.accounts.firefox.com/")
	site, _ := url.Parse("https://accounts.firefox.com/")
	var cookies []*http.Cookie
	for _, cookie := range s.client.Jar.Cookies(source) {
		if strings.HasPrefix(cookie.Name, "_fs_ch_") {
			cookies = append(cookies, &http.Cookie{Name: cookie.Name, Value: cookie.Value, Path: "/", Secure: true})
		}
	}
	if len(cookies) == 0 {
		return errors.New("security check did not issue a cookie")
	}
	client.Jar.SetCookies(api, cookies)
	client.Jar.SetCookies(oauth, cookies)
	client.Jar.SetCookies(site, cookies)
	return nil
}
