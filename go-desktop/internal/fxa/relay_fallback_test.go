package fxa

import (
	"errors"
	"net/http"
	"reflect"
	"testing"

	relaytransport "github.com/bardia049b-stack/Gozar1/go-desktop/internal/relay"
)

func relayEndpoints() []string {
	return []string{
		"https://gozar-guardian-a.gozarfox7.workers.dev",
		"https://gozar-guardian-b.gozarfox7.workers.dev",
	}
}

func TestDirect451FallsBackToTheNextRelay(t *testing.T) {
	old := client
	defer func() { client = old }()
	client = &http.Client{Transport: fakeTransport(func(*http.Request) (*http.Response, error) {
		return testResponse(451, `{}`), nil
	})}
	restoreEndpoints := relaytransport.ReplaceForTest(relayEndpoints())
	defer restoreEndpoints()
	var endpoints []string
	restoreCall := relaytransport.ReplaceCallForTest(func(endpoint, path, method, access string, body []byte) (*relaytransport.Reply, error) {
		endpoints = append(endpoints, endpoint)
		if path != "/v1/guardian/pass" || method != http.MethodGet || access != "synthetic-access" {
			t.Fatal("unexpected relay request")
		}
		if endpoint == relayEndpoints()[0] {
			return &relaytransport.Reply{Code: 451, Body: []byte(`{}`), Headers: make(http.Header)}, nil
		}
		headers := make(http.Header)
		headers.Set("X-Quota-Limit", "50")
		headers.Set("X-Quota-Remaining", "49")
		return &relaytransport.Reply{Code: 200, Body: []byte(`{"token":"relay-pass"}`), Headers: headers}, nil
	})
	defer restoreCall()
	pass, err := PassToken("synthetic-access")
	if err != nil || pass.Token != "relay-pass" || pass.Limit != 50 {
		t.Fatalf("relay fallback failed: %v", err)
	}
	if !reflect.DeepEqual(endpoints, relayEndpoints()) {
		t.Fatalf("unexpected relay order: %v", endpoints)
	}
}

func TestPassTokenThrough451FailsClosedWithoutRelayFallback(t *testing.T) {
	old := client
	defer func() { client = old }()
	client = &http.Client{Transport: fakeTransport(func(*http.Request) (*http.Response, error) {
		return nil, errors.New("unexpected direct request")
	})}
	control := &http.Client{Transport: fakeTransport(func(*http.Request) (*http.Response, error) {
		return testResponse(451, `{}`), nil
	})}
	restoreEndpoints := relaytransport.ReplaceForTest(relayEndpoints()[:1])
	defer restoreEndpoints()
	relayCalls := 0
	restoreCall := relaytransport.ReplaceCallForTest(func(endpoint, path, method, access string, body []byte) (*relaytransport.Reply, error) {
		relayCalls++
		return &relaytransport.Reply{Code: 200, Body: []byte(`{"token":"unexpected-relay-pass"}`), Headers: make(http.Header)}, nil
	})
	defer restoreCall()
	pass, err := PassTokenThrough("synthetic-access", control)
	if pass != nil {
		t.Fatal("control-scoped 451 unexpectedly returned a pass")
	}
	var apiErr *Error
	if !errors.As(err, &apiErr) || apiErr.Status != 451 {
		t.Fatalf("control-scoped 451 was not preserved: %v", err)
	}
	if relayCalls != 0 {
		t.Fatalf("control-scoped request used relay fallback %d times", relayCalls)
	}
}

func TestDirectFirstLoginActivationThroughRelayNeedsNoExistingPass(t *testing.T) {
	old := client
	defer func() { client = old }()
	client = &http.Client{Transport: fakeTransport(func(*http.Request) (*http.Response, error) {
		return testResponse(451, `{}`), nil
	})}
	restoreEndpoints := relaytransport.ReplaceForTest(relayEndpoints()[:1])
	defer restoreEndpoints()
	var paths []string
	restoreCall := relaytransport.ReplaceCallForTest(func(endpoint, path, method, access string, body []byte) (*relaytransport.Reply, error) {
		paths = append(paths, path)
		if access != "first-login" {
			t.Fatal("missing first-login authorization")
		}
		switch len(paths) {
		case 1:
			return &relaytransport.Reply{Code: 403, Body: []byte(`{}`), Headers: make(http.Header)}, nil
		case 2:
			if path != "/v1/guardian/activate" || string(body) != "{}" {
				t.Fatal("activation request was not exact")
			}
			return &relaytransport.Reply{Code: 200, Body: []byte(`{}`), Headers: make(http.Header)}, nil
		default:
			return &relaytransport.Reply{Code: 200, Body: []byte(`{"token":"first-pass"}`), Headers: make(http.Header)}, nil
		}
	})
	defer restoreCall()
	pass, err := PassToken("first-login")
	if err != nil || pass.Token != "first-pass" {
		t.Fatalf("first-login relay flow failed: %v", err)
	}
	expected := []string{"/v1/guardian/pass", "/v1/guardian/activate", "/v1/guardian/pass"}
	if !reflect.DeepEqual(paths, expected) {
		t.Fatalf("unexpected activation sequence: %v", paths)
	}
}

func TestServerListFallsBackAfter451(t *testing.T) {
	old := client
	defer func() { client = old }()
	client = &http.Client{Transport: fakeTransport(func(*http.Request) (*http.Response, error) {
		return testResponse(451, `{}`), nil
	})}
	restoreEndpoints := relaytransport.ReplaceForTest(relayEndpoints()[:1])
	defer restoreEndpoints()
	restoreCall := relaytransport.ReplaceCallForTest(func(endpoint, path, method, access string, body []byte) (*relaytransport.Reply, error) {
		if path != "/v1/mozilla/servers" || access != "" {
			t.Fatal("server-list request carried credentials or a wrong path")
		}
		return &relaytransport.Reply{Code: 200, Body: []byte(`{"data":[{"code":"NL","name":"Netherlands","cities":[{"name":"Amsterdam","servers":[{"hostname":"nl.mozilla.example","port":2499}]}]}]}`), Headers: make(http.Header)}, nil
	})
	defer restoreCall()
	locations, err := Servers()
	if err != nil || len(locations) != 1 || locations[0].Host != "nl.mozilla.example" {
		t.Fatalf("server-list relay fallback failed: %v", err)
	}
}
