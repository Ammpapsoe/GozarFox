package app

import (
	"encoding/json"
	"errors"
	"os"
	"path/filepath"
	"strings"
	"testing"
	"time"

	"github.com/Ammpapsoe/GozarFox/go-desktop/internal/config"
	"github.com/Ammpapsoe/GozarFox/go-desktop/internal/fxa"
)

func accountApp(t *testing.T) *App {
	t.Helper()
	root := t.TempDir()
	t.Setenv("HOME", root)
	t.Setenv("XDG_CONFIG_HOME", root)
	t.Setenv("APPDATA", root)
	a := New("test")
	t.Cleanup(func() { _ = a.Close() })
	return a
}

func addAccount(t *testing.T, a *App, email, uid, suffix string, remaining int64) {
	t.Helper()
	a.mu.Lock()
	authRevision, sessionRevision := a.authRevision, a.sessionRevision
	a.mu.Unlock()
	account := config.Account{
		Email: email, UID: uid, Access: "access-" + suffix, Refresh: "refresh-" + suffix,
		ExpiresAt: time.Now().Add(time.Hour).Unix(), Limit: 1000, Remaining: remaining,
	}
	if err := a.commitAccount(account, &fxa.Pass{Token: "pass-" + suffix, Limit: 1000, Remaining: remaining}, authRevision, sessionRevision); err != nil {
		t.Fatal(err)
	}
}

func TestAccountsPersistSwitchAndSignOut(t *testing.T) {
	a := accountApp(t)
	addAccount(t, a, "first@example.test", "first", "first", 800)
	addAccount(t, a, "second@example.test", "second", "second", 300)
	if state := a.State(); state.Email != "second@example.test" || len(state.Accounts) != 2 || state.Remaining != 300 {
		t.Fatalf("unexpected state: %#v", state)
	}
	if err := a.SwitchAccount("uid:first"); err != nil {
		t.Fatal(err)
	}
	if state := a.State(); state.Email != "first@example.test" || state.Remaining != 800 || a.pass != nil {
		t.Fatalf("switch leaked a pass or quota: %#v", state)
	}
	loaded := config.Load()
	if loaded.Email != "first@example.test" || len(loaded.Accounts) != 2 || loaded.Refresh != "refresh-first" {
		t.Fatalf("session was not persisted: %#v", loaded)
	}
	if err := a.SignOut(); err != nil {
		t.Fatal(err)
	}
	if state := a.State(); state.SignedIn || len(state.Accounts) != 1 || state.Accounts[0].Email != "second@example.test" {
		t.Fatalf("logout removed the wrong account: %#v", state)
	}
	if err := a.SwitchAccount("uid:second"); err != nil {
		t.Fatal(err)
	}
	if a.Config().Refresh != "refresh-second" || a.State().Remaining != 300 {
		t.Fatal("saved account was not restored")
	}
	encoded, err := json.Marshal(a.State())
	if err != nil {
		t.Fatal(err)
	}
	for _, secret := range []string{"access-second", "refresh-second", "pass-second"} {
		if strings.Contains(string(encoded), secret) {
			t.Fatal("RPC state exposed a credential")
		}
	}
}

func TestCancelAndDuplicateAuthenticationPreserveSessions(t *testing.T) {
	a := accountApp(t)
	addAccount(t, a, "first@example.test", "first", "first", 800)
	a.mu.Lock()
	authRevision, sessionRevision := a.authRevision, a.sessionRevision
	a.mu.Unlock()
	a.CancelAuthentication()
	late := config.Account{Email: "late@example.test", UID: "late", Access: "late", Refresh: "late"}
	if err := a.commitAccount(late, &fxa.Pass{}, authRevision, sessionRevision); err == nil {
		t.Fatal("cancelled authentication committed")
	}
	if a.State().Email != "first@example.test" || len(a.State().Accounts) != 1 {
		t.Fatal("cancel changed the active session")
	}
	addAccount(t, a, "FIRST@example.test", "first", "new", 700)
	if len(a.State().Accounts) != 1 || a.Config().Refresh != "refresh-new" {
		t.Fatal("duplicate account was not replaced")
	}
}

func TestDelayedRefreshCannotOverwriteSwitchedAccount(t *testing.T) {
	a := accountApp(t)
	addAccount(t, a, "first@example.test", "first", "first", 800)
	addAccount(t, a, "second@example.test", "second", "second", 300)
	if err := a.SwitchAccount("uid:first"); err != nil {
		t.Fatal(err)
	}
	a.mu.Lock()
	a.cfg.ExpiresAt = 1
	a.mu.Unlock()
	started, proceed := make(chan struct{}), make(chan struct{})
	a.refreshToken = func(refresh string) (*fxa.Tokens, error) {
		close(started)
		<-proceed
		return &fxa.Tokens{Access: "late-access", Refresh: "late-refresh", ExpiresIn: 3600}, nil
	}
	result := make(chan error, 1)
	go func() { _, err := a.access(); result <- err }()
	<-started
	if err := a.SwitchAccount("uid:second"); err != nil {
		t.Fatal(err)
	}
	close(proceed)
	if err := <-result; err == nil {
		t.Fatal("stale refresh succeeded")
	}
	if a.Config().Refresh != "refresh-second" {
		t.Fatal("stale refresh replaced another account's token")
	}
	if err := a.SwitchAccount("uid:first"); err != nil {
		t.Fatal(err)
	}
	if a.Config().Refresh != "refresh-first" {
		t.Fatal("stale refresh replaced the archived token")
	}
}

func TestDelayedUsageCannotOverwriteAnotherOrReauthenticatedAccount(t *testing.T) {
	a := accountApp(t)
	addAccount(t, a, "first@example.test", "first", "first", 800)
	started, proceed := make(chan struct{}), make(chan struct{})
	a.requestPass = func(string) (*fxa.Pass, error) {
		close(started)
		<-proceed
		return &fxa.Pass{Token: "late", Limit: 1000, Remaining: 2}, nil
	}
	result := make(chan error, 1)
	go func() { result <- a.RefreshUsage() }()
	<-started
	addAccount(t, a, "first@example.test", "first", "new", 700)
	close(proceed)
	if err := <-result; err == nil {
		t.Fatal("stale usage succeeded")
	}
	if a.State().Remaining != 700 || a.Config().Refresh != "refresh-new" {
		t.Fatal("stale usage changed the new session")
	}
}

func TestRefreshFailureLeavesExistingSessionUntouched(t *testing.T) {
	a := accountApp(t)
	addAccount(t, a, "first@example.test", "first", "first", 800)
	a.mu.Lock()
	a.cfg.ExpiresAt = 1
	a.mu.Unlock()
	a.refreshToken = func(string) (*fxa.Tokens, error) { return nil, errors.New("expired") }
	if err := a.RefreshUsage(); err == nil {
		t.Fatal("expected refresh failure")
	}
	if a.State().Email != "first@example.test" || a.Config().Refresh != "refresh-first" {
		t.Fatal("failed refresh discarded the account")
	}
}

func TestLegacySingleAccountMigratesAndRecentServerHasEffect(t *testing.T) {
	root := t.TempDir()
	t.Setenv("HOME", root)
	t.Setenv("XDG_CONFIG_HOME", root)
	t.Setenv("APPDATA", root)
	old := config.Config{
		Email: "legacy@example.test", UID: "legacy", Access: "old-access", Refresh: "old-refresh",
		Server: "selected.example", Recents: true, LastServer: "last-connected.example",
		SettingsVersion: 1,
	}
	data, err := json.Marshal(old)
	if err != nil {
		t.Fatal(err)
	}
	// A genuine legacy config predates connection_mode; marshaling the
	// current Config type would otherwise add an invalid empty value.
	var legacy map[string]json.RawMessage
	if err := json.Unmarshal(data, &legacy); err != nil {
		t.Fatal(err)
	}
	delete(legacy, "connection_mode")
	data, err = json.Marshal(legacy)
	if err != nil {
		t.Fatal(err)
	}
	if err := os.WriteFile(filepath.Join(config.Dir(), "config.json"), data, 0o600); err != nil {
		t.Fatal(err)
	}
	a := New("test")
	defer a.Close()
	state := a.State()
	if !state.SignedIn || len(state.Accounts) != 1 || !state.Accounts[0].Active || a.Config().Server != "last-connected.example" {
		t.Fatalf("legacy account or recent server not restored: %#v", state)
	}
	if err := a.SetRecents(false); err != nil {
		t.Fatal(err)
	}
	restarted := New("test")
	defer restarted.Close()
	if restarted.Config().Server != "" {
		t.Fatal("turning off remembered servers should use the recommended default after restart")
	}
}

func TestSettingsDoNotApplyWhenSavingFails(t *testing.T) {
	a := accountApp(t)
	a.servers = []fxa.Location{{Host: "server.example", Code: "test"}}
	before := a.Config()
	blocked := filepath.Join(t.TempDir(), "not-a-directory")
	if err := os.WriteFile(blocked, []byte("x"), 0o600); err != nil {
		t.Fatal(err)
	}
	t.Setenv("XDG_CONFIG_HOME", blocked)
	t.Setenv("APPDATA", blocked)
	t.Setenv("HOME", blocked)
	attempts := []func() error{
		func() error { return a.SetLanguage("fa") },
		func() error { return a.SetTheme("dark") },
		func() error { return a.SetRecents(true) },
		func() error { return a.SetServer("test") },
		func() error { return a.SetChain([]string{"socks5://127.0.0.1:1080"}) },
	}
	for _, attempt := range attempts {
		if err := attempt(); err == nil {
			t.Fatal("expected save failure")
		}
	}
	after := a.Config()
	if after.Language != before.Language || after.Theme != before.Theme || after.Recents != before.Recents || after.Server != before.Server || len(after.Chain) != len(before.Chain) {
		t.Fatalf("a rejected settings change was applied: %#v", after)
	}
}
