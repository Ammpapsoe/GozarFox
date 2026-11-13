package app

import (
	"context"
	"errors"
	"io"
	"net"
	"net/netip"
	"strconv"
	"strings"
	"sync"
	"sync/atomic"
	"time"

	"github.com/bardia049b-stack/Gozar1/go-desktop/internal/config"
	"github.com/bardia049b-stack/Gozar1/go-desktop/internal/engine"
	"github.com/bardia049b-stack/Gozar1/go-desktop/internal/fxa"
	"github.com/bardia049b-stack/Gozar1/go-desktop/internal/geo"
	"github.com/bardia049b-stack/Gozar1/go-desktop/internal/killswitch"
	relaytransport "github.com/bardia049b-stack/Gozar1/go-desktop/internal/relay"
	"github.com/bardia049b-stack/Gozar1/go-desktop/internal/signinproxy"
	"github.com/bardia049b-stack/Gozar1/go-desktop/internal/tundev"
	"github.com/bardia049b-stack/Gozar1/go-desktop/internal/tunnel"
	"github.com/bardia049b-stack/Gozar1/go-desktop/internal/warp"
	"github.com/bardia049b-stack/Gozar1/go-desktop/internal/wintun"
)

type AccountSummary struct {
	ID     string `json:"id"`
	Email  string `json:"email"`
	Active bool   `json:"active"`
}

type State struct {
	Accounts   []AccountSummary `json:"accounts"`
	SignedIn   bool             `json:"signed_in"`
	Email      string           `json:"email"`
	Language   string           `json:"language"`
	Theme      string           `json:"theme"`
	Recents    bool             `json:"recents"`
	WarpUDP    bool             `json:"warp_udp"`
	KillSwitch bool             `json:"kill_switch"`
	LastServer string           `json:"last_server"`
	Chain      []string         `json:"chain"`
	Stage      string           `json:"stage"`
	Live       bool             `json:"live"`
	Connecting bool             `json:"connecting"`
	Mode       string           `json:"mode"`
	Exit       string           `json:"exit"`
	Server     fxa.Location     `json:"server"`
	Servers    []fxa.Location   `json:"servers"`
	Down       int64            `json:"down"`
	Up         int64            `json:"up"`
	RateDown   int64            `json:"rate_down"`
	RateUp     int64            `json:"rate_up"`
	Since      int64            `json:"since"`
	Fault      string           `json:"fault"`
	NeedsAdmin bool             `json:"needs_admin"`
	Limit      int64            `json:"limit"`
	Remaining  int64            `json:"remaining"`
	Reset      string           `json:"reset"`
	Port       int              `json:"port"`
	Device     string           `json:"device"`
	Version    string           `json:"version"`
	Admin      bool             `json:"admin"`
}

type App struct {
	mu              sync.Mutex
	connectionMu    sync.Mutex
	authMu          sync.Mutex
	refreshMu       sync.Mutex
	sessionRevision uint64
	authRevision    uint64
	refreshToken    func(string) (*fxa.Tokens, error)
	requestPass     func(string) (*fxa.Pass, error)
	fetchServers    func() ([]fxa.Location, error)
	renewDelay      func(string, time.Time) time.Duration
	renewCancel     context.CancelFunc
	connectCancel   context.CancelFunc
	activeCancel    context.CancelFunc
	cfg             config.Config
	servers         []fxa.Location
	serversUntil    time.Time
	serversCached   bool
	pass            *fxa.Pass
	pending         *fxa.Session
	pendingFor      string
	pendingKind     string
	unblockFor      string
	engine          *engine.Engine
	transport       *tunnel.Manager
	warp            *warp.Auto
	stage           string
	fault           string
	live            bool
	connecting      bool
	needAdmin       bool
	exit            string
	activeServer    fxa.Location
	since           int64
	rateDown        atomic.Int64
	rateUp          atomic.Int64
	lastDown        int64
	lastUp          int64
	lastActivity    atomic.Int64
	passMu          sync.Mutex
	geo             *geo.Rules
	signin          *signinproxy.Proxy
	geoRefresh      atomic.Int64
	killActive      bool
	version         string
	admin           bool
}

func New(version string) *App {
	cfg := config.Load()
	cfg.FullTunnel = true
	if cfg.Recents && cfg.LastServer != "" {
		cfg.Server = cfg.LastServer
	} else {
		cfg.Server = ""
	}
	created := &App{cfg: cfg, version: version, admin: tundev.Privileged(), refreshToken: fxa.Refresh, requestPass: fxa.PassToken, fetchServers: fxa.Servers, geo: geo.Default()}
	created.servers, created.serversUntil = decodeServerCache(cfg.ServerCache, time.Now())
	created.serversCached = len(created.servers) != 0
	created.pass = cachedAccountPass(cfg.ActiveAccount(), time.Now())
	relaytransport.RestoreCache(cfg.RelayCache, time.Now())
	go created.sampler()
	return created
}

func (a *App) State() State {
	a.mu.Lock()
	defer a.mu.Unlock()
	state := State{
		SignedIn:   a.cfg.SignedIn(),
		Email:      a.cfg.Email,
		Language:   a.cfg.Language,
		Theme:      a.cfg.Theme,
		Recents:    a.cfg.Recents,
		WarpUDP:    a.cfg.WarpUDP,
		KillSwitch: a.cfg.KillSwitch,
		LastServer: a.cfg.LastServer,
		Chain:      append([]string(nil), a.cfg.Chain...),
		Stage:      a.stage,
		Live:       a.live,
		Connecting: a.connecting,
		Mode:       a.mode(),
		Exit:       a.exit,
		Servers:    append([]fxa.Location(nil), a.servers...),
		Fault:      a.fault,
		NeedsAdmin: a.needAdmin,
		Port:       a.cfg.Port,
		Version:    a.version,
		Admin:      a.admin,
		RateDown:   a.rateDown.Load(),
		RateUp:     a.rateUp.Load(),
	}
	active := a.cfg.ActiveAccount()
	state.Limit, state.Remaining, state.Reset = active.Limit, active.Remaining, active.Reset
	state.Accounts = make([]AccountSummary, 0, len(a.cfg.Accounts))
	for _, account := range a.cfg.WithCurrentAccount().Accounts {
		if account.ID() != "" && account.Email != "" && (account.Access != "" || account.Refresh != "") {
			state.Accounts = append(state.Accounts, AccountSummary{ID: account.ID(), Email: account.Email, Active: account.ID() == active.ID()})
		}
	}
	if a.pass != nil {
		state.Limit = a.pass.Limit
		state.Remaining = a.pass.Remaining
		state.Reset = a.pass.Reset
	}
	state.Since = a.since
	for _, place := range a.servers {
		if strings.EqualFold(place.Host, a.cfg.Server) || strings.EqualFold(place.Code, a.cfg.Server) {
			state.Server = place
			break
		}
	}
	if a.live && a.activeServer.Host != "" {
		state.Server = a.activeServer
	}
	if a.engine != nil {
		state.Down, state.Up = a.engine.Counters()
		state.Device = a.engine.Device()
	}
	return state
}

func (a *App) mode() string {
	if a.cfg.FullTunnel {
		return "tun"
	}
	return "proxy"
}

func (a *App) sampler() {
	for {
		time.Sleep(time.Second)
		a.mu.Lock()
		engineNow := a.engine
		var down, up int64
		if engineNow != nil {
			down, up = engineNow.Counters()
		}
		a.rateDown.Store(down - a.lastDown)
		a.rateUp.Store(up - a.lastUp)
		a.lastDown, a.lastUp = down, up
		a.mu.Unlock()
	}
}

func (a *App) Rates() (int64, int64) {
	return a.rateDown.Load(), a.rateUp.Load()
}

func (a *App) LoadServers() ([]fxa.Location, error) {
	a.mu.Lock()
	a.expireServersLocked(time.Now())
	if len(a.servers) != 0 {
		servers := append([]fxa.Location(nil), a.servers...)
		a.mu.Unlock()
		return servers, nil
	}
	live, transport, revision := a.live, a.transport, a.sessionRevision
	a.mu.Unlock()
	var servers []fxa.Location
	var err error
	if live {
		client, closeIdle, ok := a.controlClient()
		if !ok {
			return nil, errors.New("Mozilla control tunnel is unavailable")
		}
		defer closeIdle()
		servers, err = fxa.ServersThrough(client)
	} else {
		fetch := a.fetchServers
		if fetch == nil {
			fetch = fxa.Servers
		}
		servers, err = fetch()
	}
	if err != nil {
		return nil, err
	}
	if len(servers) == 0 {
		return nil, errors.New("Mozilla returned no servers")
	}
	a.mu.Lock()
	defer a.mu.Unlock()
	if live && (!a.live || a.transport != transport || a.sessionRevision != revision) {
		return nil, errors.New("Mozilla control tunnel stopped")
	}
	err = a.saveFreshServersLocked(servers, time.Now())
	return servers, err
}

func (a *App) SetServer(key string) error {
	a.mu.Lock()
	defer a.mu.Unlock()
	if a.live || a.connecting {
		return errors.New("disconnect before changing the server")
	}
	if autoSelected(key) {
		next := a.cfg
		next.Server = ""
		if err := config.Save(next); err != nil {
			return err
		}
		a.cfg = next
		return nil
	}
	for _, place := range a.servers {
		if strings.EqualFold(place.Host, key) || strings.EqualFold(place.Code, key) {
			next := a.cfg
			next.Server = place.Host
			if err := config.Save(next); err != nil {
				return err
			}
			a.cfg = next
			return nil
		}
	}
	return errors.New("server not found")
}

func (a *App) SetMode(tunMode bool) error {
	if !tunMode {
		return errors.New("only TUN mode is supported")
	}
	a.mu.Lock()
	defer a.mu.Unlock()
	next := a.cfg
	next.FullTunnel = true
	if err := config.Save(next); err != nil {
		return err
	}
	a.cfg = next
	return nil
}

func (a *App) SetLanguage(language string) error {
	if language != "" && language != "system" && language != "fa" && language != "en" {
		return errors.New("unsupported language")
	}
	if language == "system" {
		language = ""
	}
	a.mu.Lock()
	defer a.mu.Unlock()
	next := a.cfg
	next.Language = language
	if err := config.Save(next); err != nil {
		return err
	}
	a.cfg = next
	return nil
}

func (a *App) SetTheme(theme string) error {
	if theme != "system" && theme != "light" && theme != "dark" {
		return errors.New("unsupported theme")
	}
	a.mu.Lock()
	defer a.mu.Unlock()
	next := a.cfg
	next.Theme = theme
	if err := config.Save(next); err != nil {
		return err
	}
	a.cfg = next
	return nil
}

func (a *App) SetRecents(enabled bool) error {
	a.mu.Lock()
	defer a.mu.Unlock()
	next := a.cfg
	next.Recents = enabled
	if !enabled {
		next.LastServer = ""
	}
	if err := config.Save(next); err != nil {
		return err
	}
	a.cfg = next
	return nil
}

// SetKillSwitch stores the opt-in. Switching it off also stands an active
// firewall down immediately; switching it on takes effect at the next
// connect, because the rules need the tunnel interface that does not
// exist yet.
func (a *App) SetKillSwitch(enabled bool) error {
	a.connectionMu.Lock()
	defer a.connectionMu.Unlock()
	a.mu.Lock()
	if a.cfg.KillSwitch == enabled {
		a.mu.Unlock()
		return nil
	}
	next := a.cfg
	next.KillSwitch = enabled
	if err := config.Save(next); err != nil {
		a.mu.Unlock()
		return err
	}
	a.cfg = next
	a.mu.Unlock()
	if !enabled && a.killActive {
		_ = killswitch.Disable()
		a.killActive = false
	}
	return nil
}

func (a *App) SetChain(values []string) error {
	hops, err := tunnel.ParseChain(values)
	if err != nil {
		return err
	}
	chain := make([]string, 0, len(hops))
	for _, hop := range hops {
		chain = append(chain, hop.Scheme+"://"+net.JoinHostPort(hop.Host, strconv.Itoa(hop.Port)))
	}
	a.mu.Lock()
	defer a.mu.Unlock()
	if a.live || a.connecting {
		return errors.New("disconnect before changing the proxy chain")
	}
	next := a.cfg
	next.Chain = chain
	next.SettingsVersion = 1
	if err := config.Save(next); err != nil {
		return err
	}
	a.cfg = next
	return nil
}

func (a *App) SignIn(email, password, code string) error {
	return a.Authenticate(email, password, code, "", false)
}

func (a *App) Authenticate(email, password, code, captcha string, create bool) error {
	a.authMu.Lock()
	defer a.authMu.Unlock()
	a.mu.Lock()
	if a.live || a.connecting {
		a.mu.Unlock()
		return errors.New("disconnect before adding an account")
	}
	authRevision, sessionRevision := a.authRevision, a.sessionRevision
	a.mu.Unlock()
	email = strings.TrimSpace(email)
	code = strings.TrimSpace(code)
	if email == "" || password == "" {
		return errors.New("email and password are required")
	}
	if captcha != "" {
		if err := fxa.SubmitChallenge(captcha); err != nil {
			return err
		}
	}
	kind := "login"
	if create {
		kind = "signup"
	}
	a.setStage("در حال ورود…")
	a.mu.Lock()
	session := a.pending
	if !strings.EqualFold(a.pendingFor, email) || a.pendingKind != kind {
		session = nil
		a.pending = nil
		a.pendingFor = ""
		a.pendingKind = ""
	}
	usingUnblock := !create && code != "" && a.unblockFor != "" && strings.EqualFold(a.unblockFor, email)
	a.mu.Unlock()
	fromPending := session != nil
	var err error
	if session == nil {
		switch {
		case create:
			session, err = fxa.Register(email, password)
		case usingUnblock:
			session, err = fxa.SignInWithUnblockCode(email, password, code)
		default:
			session, err = fxa.SignIn(email, password)
		}
		if err != nil {
			var apiError *fxa.Error
			if !create && !usingUnblock && errors.As(err, &apiError) && apiError.Errno == 125 {
				address := email
				if apiError.Email != "" {
					address = apiError.Email
				}
				if sendErr := fxa.SendUnblockCode(address); sendErr != nil {
					a.setStage("")
					return sendErr
				}
				a.mu.Lock()
				if a.authRevision != authRevision || a.sessionRevision != sessionRevision {
					a.mu.Unlock()
					return errors.New("authentication cancelled")
				}
				a.unblockFor = email
				a.mu.Unlock()
				a.setStage("")
				return errCode
			}
			a.setStage("")
			return err
		}
		a.mu.Lock()
		if a.authRevision != authRevision || a.sessionRevision != sessionRevision {
			a.mu.Unlock()
			return errors.New("authentication cancelled")
		}
		a.pending = session
		a.pendingFor = email
		a.pendingKind = kind
		if usingUnblock {
			a.unblockFor = ""
			code = ""
		}
		a.mu.Unlock()
	}
	if session.NeedsOTP {
		if session.ByLink {
			if !fromPending {
				a.setStage("")
				return errLink
			}
		} else {
			if code == "" {
				a.setStage("")
				return errCode
			}
			if create {
				err = fxa.VerifySignup(session.UID, code)
			} else {
				err = fxa.VerifyCode(session.Token, code, session.VerificationMethod)
			}
			if err != nil {
				a.setStage("")
				return err
			}
			a.mu.Lock()
			session.NeedsOTP = false
			a.mu.Unlock()
		}
	}
	a.setStage("در حال دریافت مجوز…")
	tokens, err := fxa.SessionTokens(session.Token)
	if err != nil {
		a.setStage("")
		return err
	}
	pass, err := a.requestPass(tokens.Access)
	if err != nil {
		a.setStage("")
		return err
	}
	accountEmail := session.Email
	if accountEmail == "" {
		accountEmail = email
	}
	account := config.Account{
		Email: accountEmail, UID: session.UID, Access: tokens.Access, Refresh: tokens.Refresh,
		ExpiresAt: time.Now().Add(time.Duration(tokens.ExpiresIn-60) * time.Second).Unix(),
		Limit:     pass.Limit, Remaining: pass.Remaining, Reset: pass.Reset,
	}
	if err := a.commitAccount(account, pass, authRevision, sessionRevision); err != nil {
		a.setStage("")
		return err
	}
	a.setStage("")
	return nil
}

func (a *App) CancelAuthentication() {
	a.mu.Lock()
	a.authRevision++
	a.pending = nil
	a.pendingFor = ""
	a.pendingKind = ""
	a.unblockFor = ""
	a.mu.Unlock()
	fxa.CancelChallenge()
}

var errCode = errors.New("code needed")
var errLink = errors.New("verification link required")

func NeedsCode(err error) bool { return errors.Is(err, errCode) }

// The session a browser sign-in captured is not confirmed by Mozilla yet:
// the page stays open for the code and announces the session again when it
// is. Callers treat this as "wait", not as a failure.
var errUnverified = errors.New("session not verified yet")

// SessionUnverified reports whether err is the "wait for the page" case of
// a browser sign-in.
func SessionUnverified(err error) bool { return errors.Is(err, errUnverified) }

// SignInWithSession completes a sign-in that Mozilla's own page inside the
// browser view already performed: the session token the page announced is
// exchanged for OAuth tokens, the proxy pass is fetched, and the account is
// stored. Nothing is asked of the person here; the page took the address,
// the password and any confirmation code.
func (a *App) SignInWithSession(email, uid, sessionToken string) error {
	a.authMu.Lock()
	defer a.authMu.Unlock()
	a.mu.Lock()
	if a.live || a.connecting {
		a.mu.Unlock()
		return errors.New("disconnect before adding an account")
	}
	authRevision, sessionRevision := a.authRevision, a.sessionRevision
	a.mu.Unlock()
	email = strings.TrimSpace(email)
	uid = strings.ToLower(strings.TrimSpace(uid))
	sessionToken = strings.ToLower(strings.TrimSpace(sessionToken))
	if email == "" || uid == "" || sessionToken == "" {
		return errors.New("email, uid and session token are required")
	}
	a.setStage("در حال ورود…")
	verified, err := fxa.SessionVerified(sessionToken)
	if err != nil {
		a.setStage("")
		var apiError *fxa.Error
		if errors.As(err, &apiError) && apiError.Errno == 104 {
			return errUnverified
		}
		return err
	}
	if !verified {
		a.setStage("")
		return errUnverified
	}
	a.setStage("در حال دریافت مجوز…")
	tokens, err := fxa.SessionTokens(sessionToken)
	if err != nil {
		a.setStage("")
		var apiError *fxa.Error
		if errors.As(err, &apiError) && apiError.Errno == 104 {
			return errUnverified
		}
		return err
	}
	pass, err := a.requestPass(tokens.Access)
	if err != nil {
		a.setStage("")
		return err
	}
	account := config.Account{
		Email: email, UID: uid, Access: tokens.Access, Refresh: tokens.Refresh,
		ExpiresAt: time.Now().Add(time.Duration(tokens.ExpiresIn-60) * time.Second).Unix(),
		Limit:     pass.Limit, Remaining: pass.Remaining, Reset: pass.Reset,
	}
	if err := a.commitAccount(account, pass, authRevision, sessionRevision); err != nil {
		a.setStage("")
		return err
	}
	a.setStage("")
	return nil
}

// OpenSignInProxy starts (or reuses) the loopback proxy the sign-in browser
// view connects through, and reports its address. The web view environment
// of a process is made once, so repeated calls must keep the same port.
func (a *App) OpenSignInProxy() (string, error) {
	a.mu.Lock()
	if a.signin == nil {
		a.signin = signinproxy.New(nil)
	}
	proxy := a.signin
	a.mu.Unlock()
	port, err := proxy.Start()
	if err != nil {
		return "", err
	}
	return "127.0.0.1:" + strconv.Itoa(port), nil
}

// CloseSignInProxy stops the proxy and every tunnel in it. Safe when none
// was ever opened.
func (a *App) CloseSignInProxy() {
	a.mu.Lock()
	proxy := a.signin
	a.mu.Unlock()
	if proxy != nil {
		proxy.Stop()
	}
}

func (a *App) commitAccount(account config.Account, pass *fxa.Pass, authRevision, sessionRevision uint64) error {
	a.mu.Lock()
	defer a.mu.Unlock()
	if a.authRevision != authRevision || a.sessionRevision != sessionRevision || a.live || a.connecting {
		return errors.New("authentication cancelled or account changed")
	}
	if account.ID() == "" || account.Email == "" || (account.Access == "" && account.Refresh == "") {
		return errors.New("invalid account")
	}
	account.ProxyPass = persistablePass(pass, time.Now())
	if pass != nil {
		account.Limit, account.Remaining, account.Reset = pass.Limit, pass.Remaining, pass.Reset
	}
	next := a.cfg.PutAccount(account)
	if err := config.Save(next); err != nil {
		return err
	}
	a.cfg = next
	a.pass = pass
	a.sessionRevision++
	a.authRevision++
	a.pending = nil
	a.pendingFor = ""
	a.pendingKind = ""
	a.unblockFor = ""
	return nil
}

func (a *App) SwitchAccount(id string) error {
	a.connectionMu.Lock()
	defer a.connectionMu.Unlock()
	a.mu.Lock()
	next, found := a.cfg.SwitchAccount(id)
	a.mu.Unlock()
	if !found {
		return errors.New("account not found")
	}
	a.teardown()
	a.mu.Lock()
	defer a.mu.Unlock()
	next, found = a.cfg.SwitchAccount(id)
	if !found {
		return errors.New("account not found")
	}
	if err := config.Save(next); err != nil {
		return err
	}
	a.cfg = next
	a.pass = cachedAccountPass(next.ActiveAccount(), time.Now())
	a.sessionRevision++
	a.authRevision++
	a.pending = nil
	a.pendingFor = ""
	a.pendingKind = ""
	a.unblockFor = ""
	a.fault = ""
	a.needAdmin = false
	return nil
}

func (a *App) SignOut() error {
	a.connectionMu.Lock()
	defer a.connectionMu.Unlock()
	a.teardown()
	a.mu.Lock()
	defer a.mu.Unlock()
	next := a.cfg.RemoveActive()
	if err := config.Save(next); err != nil {
		return err
	}
	a.cfg = next
	a.pass = nil
	a.sessionRevision++
	a.authRevision++
	a.pending = nil
	a.pendingFor = ""
	a.pendingKind = ""
	a.unblockFor = ""
	a.fault = ""
	a.needAdmin = false
	return nil
}

type accountAccess struct {
	token    string
	id       string
	revision uint64
}

func (a *App) access() (accountAccess, error) { return a.accessWith(a.refreshForControl) }

func (a *App) accessWith(refresh func(string) (*fxa.Tokens, error)) (accountAccess, error) {
	a.refreshMu.Lock()
	defer a.refreshMu.Unlock()
	a.mu.Lock()
	current := a.cfg.ActiveAccount()
	revision := a.sessionRevision
	a.mu.Unlock()
	if current.ID() == "" || (current.Access == "" && current.Refresh == "") {
		return accountAccess{}, errors.New("not signed in")
	}
	if (current.Access == "" || (current.ExpiresAt > 0 && time.Now().Unix() >= current.ExpiresAt)) && current.Refresh != "" {
		tokens, err := refresh(current.Refresh)
		if err != nil {
			return accountAccess{}, err
		}
		a.mu.Lock()
		defer a.mu.Unlock()
		if a.sessionRevision != revision || a.cfg.ActiveAccount().ID() != current.ID() || a.cfg.Refresh != current.Refresh {
			return accountAccess{}, errors.New("account changed")
		}
		next := a.cfg
		next.Access = tokens.Access
		next.Refresh = tokens.Refresh
		next.ExpiresAt = time.Now().Add(time.Duration(tokens.ExpiresIn-60) * time.Second).Unix()
		next = next.WithCurrentAccount()
		if err := config.Save(next); err != nil {
			return accountAccess{}, err
		}
		a.cfg = next
		return accountAccess{token: tokens.Access, id: current.ID(), revision: revision}, nil
	}
	if current.Access == "" {
		return accountAccess{}, errors.New("sign in again")
	}
	return accountAccess{token: current.Access, id: current.ID(), revision: revision}, nil
}

func (a *App) saveUsage(pass *fxa.Pass, access accountAccess) error {
	a.mu.Lock()
	defer a.mu.Unlock()
	return a.saveUsageLocked(pass, access)
}

func (a *App) saveUsageLocked(pass *fxa.Pass, access accountAccess) error {
	if a.sessionRevision != access.revision || a.cfg.ActiveAccount().ID() != access.id {
		return errors.New("account changed")
	}
	current := a.cfg.ActiveAccount()
	if pass == nil || pass.Token == "" {
		return errors.New("Mozilla returned no proxy pass")
	}
	current.Limit, current.Remaining, current.Reset = pass.Limit, pass.Remaining, pass.Reset
	current.ProxyPass = persistablePass(pass, time.Now())
	next := a.cfg.PutAccount(current)
	if err := config.Save(next); err != nil {
		return err
	}
	a.cfg = next
	a.pass = pass
	return nil
}

func (a *App) RefreshUsage() error {
	// Serialize manual and automatic renewal so stale results cannot win.
	a.passMu.Lock()
	defer a.passMu.Unlock()
	access, err := a.access()
	if err != nil {
		return err
	}
	pass, err := a.passForControl(access.token)
	if err != nil {
		return err
	}
	return a.saveUsage(pass, access)
}

func (a *App) Connect() error {
	a.connectionMu.Lock()
	defer a.connectionMu.Unlock()
	return a.connectLocked()
}

// connectLocked serializes startup with disconnects and setting changes.
func (a *App) connectLocked() error {
	a.mu.Lock()
	if a.live || a.connecting {
		a.mu.Unlock()
		return nil
	}
	ctx, cancel := context.WithCancel(context.Background())
	a.connectCancel = cancel
	a.connecting = true
	a.fault = ""
	a.needAdmin = false
	a.stage = "در حال اتصال…"
	a.mu.Unlock()

	err := a.connect(ctx)
	a.mu.Lock()
	a.connectCancel = nil
	if err == nil && ctx.Err() != nil {
		err = ctx.Err()
	}
	if err == nil {
		a.activeCancel = cancel
	} else {
		cancel()
	}
	a.connecting = false
	a.stage = ""
	if err != nil {
		a.fault = err.Error()
		if strings.Contains(err.Error(), "administrator") || strings.Contains(err.Error(), "root") {
			a.needAdmin = true
		}
	}
	a.mu.Unlock()
	if err != nil {
		a.teardown()
	}
	return err
}

func (a *App) connect(ctx context.Context) error {
	// A successful sign-in already fetched a short-lived Mozilla pass. Using
	// it first avoids another direct Guardian request when the network changes.
	pass := a.bootstrapPass(time.Now())
	if pass == nil {
		access, err := a.access()
		if err != nil {
			return err
		}
		a.mu.Lock()
		a.stage = "دریافت پاس پروکسی…"
		a.mu.Unlock()
		pass, err = a.requestPass(access.token)
		if err != nil {
			return err
		}
		if err := a.saveUsage(pass, access); err != nil {
			return err
		}
	}

	a.mu.Lock()
	a.expireServersLocked(time.Now())
	needsServers := len(a.servers) == 0
	a.mu.Unlock()
	if needsServers {
		if _, err := a.LoadServers(); err != nil {
			return err
		}
	}
	a.mu.Lock()
	code := a.cfg.Server
	servers := append([]fxa.Location(nil), a.servers...)
	usingCachedServers := a.serversCached
	chain := append([]string(nil), a.cfg.Chain...)
	warpEnabled := a.cfg.WarpUDP
	splitConfigured := a.cfg.SplitTunnel
	killSwitch := a.cfg.KillSwitch
	a.mu.Unlock()
	hops, err := tunnel.ParseChain(chain)
	if err != nil {
		return err
	}
	selectAndVerify := func(list []fxa.Location) (pathCandidate, *tunnel.Manager, error) {
		var candidates []pathCandidate
		if autoSelected(code) {
			a.setStage("سنجش مسیرها…")
			candidates = autoCandidates(list, hops, 2, 1200*time.Millisecond, 8)
		} else {
			place, err := pickLocation(list, code)
			if err != nil {
				return pathCandidate{}, nil, err
			}
			candidates = []pathCandidate{preparePath(place, hops)}
		}
		a.setStage("برقراری تونل…")
		return choosePath(candidates, pass.Token, 8*time.Second, verifyPath)
	}
	chosen, transport, err := selectAndVerify(servers)
	if mayRetryFreshList(usingCachedServers, err, ctx.Err()) {
		// A previously authenticated list can become stale before its TTL.
		// If its relays no longer work, try a fresh public Mozilla list. This
		// is not a credential or proxy-pass renewal request.
		a.mu.Lock()
		if a.serversCached {
			a.servers = nil
			a.serversUntil = time.Time{}
			a.serversCached = false
		}
		a.mu.Unlock()
		if fresh, loadErr := a.LoadServers(); loadErr == nil && ctx.Err() == nil {
			chosen, transport, err = selectAndVerify(fresh)
		}
	}
	if err != nil {
		return err
	}
	if err := ctx.Err(); err != nil {
		transport.Close()
		return err
	}
	dial := func(host string, remotePort int) (io.ReadWriteCloser, error) {
		current, err := a.currentPass()
		if err != nil {
			return nil, err
		}
		a.noteActivity()
		return transport.Open(host, remotePort, current, 15*time.Second)
	}
	if err := wintun.Ensure(); err != nil {
		transport.Close()
		return err
	}
	var direct engine.Dialer
	var split *geo.Rules
	// A direct TCP bypass installs a host-wide route that could also carry
	// UDP around the TUN. Pause split routing while WARP is enabled rather
	// than silently sending a later UDP flow directly to that host.
	splitEnabled := allowSplitWithWarp(splitConfigured, warpEnabled)
	if splitEnabled {
		split = a.geo
		direct = func(host string, remotePort int) (io.ReadWriteCloser, error) {
			a.mu.Lock()
			current := a.engine
			a.mu.Unlock()
			if current == nil {
				return nil, errors.New("tunnel is not ready")
			}
			address, err := netip.ParseAddr(host)
			if err != nil {
				return nil, err
			}
			if err := current.Bypass(address); err != nil {
				return nil, err
			}
			a.noteActivity()
			return net.DialTimeout("tcp", net.JoinHostPort(host, strconv.Itoa(remotePort)), 15*time.Second)
		}
	}
	var udpDial engine.DatagramDialer
	var warpNow *warp.Auto
	if warpEnabled {
		a.setStage("Scanning Cloudflare WARP endpoints…")
		warpNow, err = a.startWarp(ctx, transport)
		if err != nil {
			transport.Close()
			return err
		}
		udpDial = func(host string, remotePort int) (engine.DatagramTunnel, error) {
			flow, err := warpNow.OpenUDP(host, remotePort)
			if err != nil {
				return nil, err
			}
			a.noteActivity()
			return flow, nil
		}
	}
	// Disabled, unavailable or refused UDP always fails closed. The old
	// Mozilla CONNECT-UDP path is not used as a substitute for WARP.
	if err := ctx.Err(); err != nil {
		if warpNow != nil {
			_ = warpNow.Close()
		}
		transport.Close()
		return err
	}
	created, err := engine.StartSplitUDP(chosen.target.Host, dial, direct, chosen.keep, split, udpDial)
	if err != nil {
		if warpNow != nil {
			_ = warpNow.Close()
		}
		transport.Close()
		return err
	}
	if err := ctx.Err(); err != nil {
		created.Stop()
		if warpNow != nil {
			_ = warpNow.Close()
		}
		transport.Close()
		return err
	}
	if killSwitch {
		if err := killswitch.Enable(killswitch.Config{Interface: created.Device(), Proxy: chosen.keep, AllowDirect: splitEnabled}); err != nil {
			created.Stop()
			if warpNow != nil {
				_ = warpNow.Close()
			}
			transport.Close()
			return err
		}
	}
	a.mu.Lock()
	a.engine = created
	a.killActive = killSwitch
	a.transport = transport
	a.warp = warpNow
	a.activeServer = chosen.place
	a.live = true
	a.since = time.Now().Unix()
	a.lastActivity.Store(time.Now().UnixNano())
	a.geoRefresh.Store(time.Now().UnixNano())
	go a.maybeRefreshGeo(true)
	if a.cfg.Recents {
		a.cfg.LastServer = chosen.target.Host
		_ = config.Save(a.cfg)
	}
	a.stage = ""
	a.mu.Unlock()
	go a.watchTunnel(created)
	a.startRenewal()
	go a.refreshServersThroughTunnel()
	if ip, err := transport.Probe(pass.Token, 12*time.Second); err == nil {
		a.mu.Lock()
		a.exit = ip
		a.mu.Unlock()
	}
	return nil
}

func (a *App) watchTunnel(current *engine.Engine) {
	err, ok := <-current.Failures()
	if !ok || err == nil {
		return
	}
	a.connectionMu.Lock()
	defer a.connectionMu.Unlock()
	a.mu.Lock()
	active := a.engine == current
	a.mu.Unlock()
	if !active {
		return
	}
	a.teardown()
	a.mu.Lock()
	a.fault = "The tunnel device stopped: " + err.Error()
	a.mu.Unlock()
}

func (a *App) teardown() {
	a.mu.Lock()
	engineNow := a.engine
	transport := a.transport
	warpNow := a.warp
	killActive := a.killActive
	a.killActive = false
	a.transport = nil
	a.warp = nil
	cancel := a.renewCancel
	activeCancel := a.activeCancel
	a.renewCancel = nil
	a.activeCancel = nil
	a.engine = nil
	a.live = false
	a.activeServer = fxa.Location{}
	a.exit = ""
	a.since = 0
	a.mu.Unlock()
	if cancel != nil {
		cancel()
	}
	if activeCancel != nil {
		activeCancel()
	}
	if warpNow != nil {
		_ = warpNow.Close()
	}
	if transport != nil {
		transport.Close()
	}
	if engineNow != nil {
		engineNow.Stop()
	}
	if killActive {
		_ = killswitch.Disable()
	}
}

func (a *App) cancelConnectionAttempt() {
	a.mu.Lock()
	pending, active := a.connectCancel, a.activeCancel
	a.mu.Unlock()
	if pending != nil {
		pending()
	}
	if active != nil {
		active()
	}
}

func (a *App) Disconnect() {
	a.cancelConnectionAttempt()
	a.connectionMu.Lock()
	defer a.connectionMu.Unlock()
	a.teardown()
	a.mu.Lock()
	a.fault = ""
	a.needAdmin = false
	a.mu.Unlock()
}

func (a *App) Close() {
	a.CloseSignInProxy()
	a.cancelConnectionAttempt()
	a.connectionMu.Lock()
	defer a.connectionMu.Unlock()
	a.teardown()
}

func (a *App) setStage(text string) {
	a.mu.Lock()
	a.stage = text
	a.mu.Unlock()
}

func (a *App) Config() config.Config {
	a.mu.Lock()
	defer a.mu.Unlock()
	return a.cfg.WithCurrentAccount()
}
