package localproxy

import (
	"errors"
	"io"
	"testing"
	"time"
)

func TestUnexpectedListenerFailureReported(t *testing.T) {
	server, err := Listen(0, func(string, int) (io.ReadWriteCloser, error) { return nil, errors.New("unused") })
	if err != nil {
		t.Fatal(err)
	}
	defer server.Close()
	if err := server.listener.Close(); err != nil {
		t.Fatal(err)
	}
	select {
	case err, ok := <-server.Failures():
		if !ok || err == nil {
			t.Fatal("listener failure was not reported")
		}
	case <-time.After(5 * time.Second):
		t.Fatal("listener failure timed out")
	}
}

func TestNormalCloseDoesNotReportFailure(t *testing.T) {
	server, err := Listen(0, func(string, int) (io.ReadWriteCloser, error) { return nil, errors.New("unused") })
	if err != nil {
		t.Fatal(err)
	}
	if err := server.Close(); err != nil {
		t.Fatal(err)
	}
	select {
	case err, ok := <-server.Failures():
		if ok || err != nil {
			t.Fatal("normal close reported a listener failure")
		}
	case <-time.After(5 * time.Second):
		t.Fatal("normal close did not finish")
	}
}
