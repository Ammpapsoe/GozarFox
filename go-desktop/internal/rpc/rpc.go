package rpc

import (
	"bufio"
	"context"
	"encoding/json"
	"errors"
	"io"
	"os"
	"sync"

	"github.com/bardia049b-stack/Gozar1/go-desktop/internal/app"
	"github.com/bardia049b-stack/Gozar1/go-desktop/internal/fxa"
	"github.com/bardia049b-stack/Gozar1/go-desktop/internal/systemaccounts"
	"github.com/bardia049b-stack/Gozar1/go-desktop/internal/update"
)

type Request struct {
	ID     int             `json:"id"`
	Method string          `json:"method"`
	Params json.RawMessage `json:"params,omitempty"`
}

type Response struct {
	ID    int        `json:"id"`
	Error string     `json:"error,omitempty"`
	State *app.State `json:"state,omitempty"`
	Reply any        `json:"reply,omitempty"`
}

type updateService interface {
	Check(context.Context, string) (update.Summary, error)
	Download(context.Context, string) (string, error)
	Cancel()
}

type pendingUpdate struct{ cancel context.CancelFunc }

type Server struct {
	app       *app.App
	write     sync.Mutex
	out       io.Writer
	updates   updateService
	ctx       context.Context
	pendingMu sync.Mutex
	pending   map[*pendingUpdate]struct{}
}

func Run(current *app.App) {
	ignorePipe()
	ctx, cancel := context.WithCancel(context.Background())
	defer cancel()
	server := &Server{app: current, out: os.Stdout, updates: update.NewService(), ctx: ctx}
	reader := bufio.NewReaderSize(os.Stdin, 1<<20)
	for {
		line, err := reader.ReadBytes('\n')
		if len(line) > 0 {
			var request Request
			if json.Unmarshal(line, &request) == nil {
				switch request.Method {
				case "check_update", "download_update":
					// Network I/O must not block disconnect, quit or cancellation.
					server.startUpdate(request, current.State().Version)
				case "cancel_update":
					if err := server.emit(server.handleUpdate(request, "")); err != nil {
						return
					}
				default:
					if err := server.emit(server.handle(request)); err != nil || request.Method == "quit" {
						return
					}
				}
			}
		}
		if err != nil {
			return
		}
	}
}

func (s *Server) registerUpdate() (context.Context, func()) {
	parent := s.ctx
	if parent == nil {
		parent = context.Background()
	}
	ctx, cancel := context.WithCancel(parent)
	pending := &pendingUpdate{cancel: cancel}
	s.pendingMu.Lock()
	if s.pending == nil {
		s.pending = make(map[*pendingUpdate]struct{})
	}
	s.pending[pending] = struct{}{}
	s.pendingMu.Unlock()
	return ctx, func() {
		cancel()
		s.pendingMu.Lock()
		delete(s.pending, pending)
		s.pendingMu.Unlock()
	}
}

func (s *Server) cancelUpdates() {
	s.pendingMu.Lock()
	for pending := range s.pending {
		pending.cancel()
	}
	s.pendingMu.Unlock()
}

func (s *Server) startUpdate(request Request, installed string) {
	// Register before starting the goroutine, so an immediately following
	// cancel cannot slip through before the service begins its network work.
	ctx, done := s.registerUpdate()
	go func() {
		defer done()
		_ = s.emit(s.handleUpdateContext(ctx, request, installed))
	}()
}

func (s *Server) handleUpdate(request Request, installed string) Response {
	ctx := s.ctx
	if ctx == nil {
		ctx = context.Background()
	}
	return s.handleUpdateContext(ctx, request, installed)
}

func (s *Server) handleUpdateContext(ctx context.Context, request Request, installed string) Response {
	response := Response{ID: request.ID}
	if s.updates == nil {
		response.Error = "update service unavailable"
		return response
	}
	if request.Method != "cancel_update" && ctx.Err() != nil {
		response.Error = "update operation cancelled"
		return response
	}
	var err error
	switch request.Method {
	case "check_update":
		response.Reply, err = s.updates.Check(ctx, installed)
	case "download_update":
		var body struct {
			ID string `json:"id"`
		}
		if json.Unmarshal(request.Params, &body) != nil || body.ID == "" {
			response.Error = "invalid update artifact"
			return response
		}
		var path string
		path, err = s.updates.Download(ctx, body.ID)
		if err == nil {
			response.Reply = map[string]string{"downloaded_path": path}
		}
	case "cancel_update":
		s.cancelUpdates()
		s.updates.Cancel()
	default:
		response.Error = "unknown update method"
		return response
	}
	if err != nil {
		if errors.Is(err, context.Canceled) {
			response.Error = "update operation cancelled"
		} else {
			response.Error = "signed update operation failed"
		}
	}
	return response
}

func (s *Server) handle(request Request) Response {
	response := Response{ID: request.ID}
	switch request.Method {
	case "state":
	case "servers":
		servers, err := s.app.LoadServers()
		if err != nil {
			response.Error = err.Error()
		} else {
			response.Reply = servers
		}
	case "login", "register":
		var body struct {
			Email    string `json:"email"`
			Password string `json:"password"`
			Code     string `json:"code"`
			Captcha  string `json:"captcha"`
		}
		json.Unmarshal(request.Params, &body)
		if err := s.app.Authenticate(body.Email, body.Password, body.Code, body.Captcha, request.Method == "register"); err != nil {
			response.Error = err.Error()
			var challenge *fxa.ChallengeRequired
			switch {
			case app.NeedsCode(err):
				response.Reply = map[string]any{"needs_code": true}
			case errors.As(err, &challenge):
				response.Reply = map[string]any{"captcha_image": challenge.Image}
			}
		}
	case "browser_signin":
		var body struct {
			Email        string `json:"email"`
			UID          string `json:"uid"`
			SessionToken string `json:"session_token"`
		}
		json.Unmarshal(request.Params, &body)
		if err := s.app.SignInWithSession(body.Email, body.UID, body.SessionToken); err != nil {
			response.Error = err.Error()
			if app.SessionUnverified(err) {
				response.Reply = map[string]any{"unverified": true}
			}
		}
	case "open_signin_proxy":
		address, err := s.app.OpenSignInProxy()
		if err != nil {
			response.Error = err.Error()
		} else {
			response.Reply = map[string]any{"address": address}
		}
	case "close_signin_proxy":
		s.app.CloseSignInProxy()
	case "system_accounts":
		accounts, err := systemaccounts.Emails()
		if err != nil {
			response.Error = err.Error()
		} else {
			response.Reply = map[string]any{"emails": accounts}
		}
	case "cancel_auth":
		s.app.CancelAuthentication()
	case "logout":
		if err := s.app.SignOut(); err != nil {
			response.Error = err.Error()
		}
	case "switch_account":
		var body struct {
			ID string `json:"id"`
		}
		if err := json.Unmarshal(request.Params, &body); err != nil || body.ID == "" {
			response.Error = "invalid account"
		} else if err := s.app.SwitchAccount(body.ID); err != nil {
			response.Error = err.Error()
		}
	case "usage":
		if err := s.app.RefreshUsage(); err != nil {
			response.Error = err.Error()
		}
	case "connect":
		if err := s.app.Connect(); err != nil {
			response.Error = err.Error()
		}
	case "disconnect":
		s.app.Disconnect()
	case "server":
		var body struct {
			Host string `json:"host"`
			Code string `json:"code"`
		}
		json.Unmarshal(request.Params, &body)
		key := body.Host
		if key == "" {
			key = body.Code
		}
		if err := s.app.SetServer(key); err != nil {
			response.Error = err.Error()
		}
	case "language":
		var body struct {
			Language string `json:"language"`
		}
		json.Unmarshal(request.Params, &body)
		if err := s.app.SetLanguage(body.Language); err != nil {
			response.Error = err.Error()
		}
	case "theme":
		var body struct {
			Theme string `json:"theme"`
		}
		if err := json.Unmarshal(request.Params, &body); err != nil {
			response.Error = "invalid theme"
		} else if err := s.app.SetTheme(body.Theme); err != nil {
			response.Error = err.Error()
		}
	case "recents":
		var body struct {
			Enabled *bool `json:"enabled"`
		}
		if err := json.Unmarshal(request.Params, &body); err != nil || body.Enabled == nil {
			response.Error = "invalid recent-server setting"
		} else if err := s.app.SetRecents(*body.Enabled); err != nil {
			response.Error = err.Error()
		}
	case "warp_udp":
		var body struct {
			Enabled *bool `json:"enabled"`
			Consent bool  `json:"consent"`
		}
		if err := json.Unmarshal(request.Params, &body); err != nil || body.Enabled == nil {
			response.Error = "invalid WARP UDP setting"
		} else if err := s.app.SetWarpUDP(*body.Enabled, body.Consent); err != nil {
			response.Error = err.Error()
		}
	case "chain":
		var body struct {
			Hops []string `json:"hops"`
		}
		if err := json.Unmarshal(request.Params, &body); err != nil || body.Hops == nil {
			response.Error = "invalid proxy chain"
		} else if err := s.app.SetChain(body.Hops); err != nil {
			response.Error = err.Error()
		}
	case "mode":
		var body struct {
			Tunnel bool `json:"tunnel"`
		}
		json.Unmarshal(request.Params, &body)
		if err := s.app.SetMode(body.Tunnel); err != nil {
			response.Error = err.Error()
		}
	case "quit":
		s.app.Close()
		response.State = statePointer(s.app.State())
	default:
		response.Error = "unknown method"
	}
	if response.State == nil {
		response.State = statePointer(s.app.State())
	}
	return response
}

func (s *Server) emit(response Response) error {
	encoder := json.NewEncoder(s.out)
	s.write.Lock()
	defer s.write.Unlock()
	return encoder.Encode(response)
}

func statePointer(value app.State) *app.State { return &value }
