package update

import (
	"context"
	"errors"
	"runtime"
	"sync"
)

var ErrBusy = errors.New("another update operation is already running")

type Summary struct {
	Version   string     `json:"version"`
	Newer     bool       `json:"newer"`
	Notes     string     `json:"notes"`
	Artifacts []Artifact `json:"artifacts"`
}

// Service serializes checks and downloads so stale checks cannot replace a
// later result or expose an old download after a new check starts.
type Service struct {
	mu       sync.Mutex
	busy     bool
	cancel   context.CancelFunc
	verified *Verified
	osName   string
	arch     string
	check    func(context.Context, string) (*Verified, error)
	download func(context.Context, *Verified, string) (string, error)
}

func NewService() *Service {
	return &Service{
		osName: runtime.GOOS,
		arch:   runtime.GOARCH,
		check:  Check,
		download: func(ctx context.Context, v *Verified, id string) (string, error) {
			return v.Download(ctx, id)
		},
	}
}

func (s *Service) begin(parent context.Context, clear bool) (context.Context, context.CancelFunc, *Verified, error) {
	s.mu.Lock()
	defer s.mu.Unlock()
	if s.busy {
		return nil, nil, nil, ErrBusy
	}
	ctx, cancel := context.WithCancel(parent)
	if clear {
		s.verified = nil
	}
	s.busy = true
	s.cancel = cancel
	return ctx, cancel, s.verified, nil
}

func (s *Service) finish(cancel context.CancelFunc) {
	cancel()
	s.mu.Lock()
	s.cancel = nil
	s.busy = false
	s.mu.Unlock()
}

// Cancel requests cancellation of a running network operation. It does not
// start a new operation until the old one has actually returned.
func (s *Service) Cancel() {
	s.mu.Lock()
	defer s.mu.Unlock()
	if s.cancel != nil {
		s.cancel()
	}
}

func (s *Service) Check(ctx context.Context, installed string) (Summary, error) {
	ctx, cancel, _, err := s.begin(ctx, true)
	if err != nil {
		return Summary{}, err
	}
	defer s.finish(cancel)
	verified, err := s.check(ctx, installed)
	if err != nil {
		return Summary{}, err
	}
	if verified == nil {
		return Summary{}, errors.New("update verifier returned no result")
	}
	manifest := verified.Manifest()
	summary := Summary{Version: manifest.Version, Newer: verified.Newer(), Notes: manifest.Notes}
	if summary.Newer {
		summary.Artifacts = verified.ArtifactsFor(s.osName, s.arch)
	}
	s.mu.Lock()
	defer s.mu.Unlock()
	if err := ctx.Err(); err != nil {
		return Summary{}, err
	}
	s.verified = verified
	return summary, nil
}

func (s *Service) Download(ctx context.Context, artifactID string) (string, error) {
	ctx, cancel, verified, err := s.begin(ctx, false)
	if err != nil {
		return "", err
	}
	defer s.finish(cancel)
	if verified == nil || !verified.Newer() {
		return "", errors.New("no authenticated newer update")
	}
	allowed := false
	for _, artifact := range verified.ArtifactsFor(s.osName, s.arch) {
		if artifact.ID == artifactID {
			allowed = true
			break
		}
	}
	if !allowed {
		return "", errors.New("artifact does not match this platform")
	}
	path, err := s.download(ctx, verified, artifactID)
	if err != nil {
		return "", err
	}
	if err := ctx.Err(); err != nil {
		return "", err
	}
	return path, nil
}
