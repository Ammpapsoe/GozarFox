package rpc

import (
	"context"
	"encoding/json"
	"io"
	"testing"
	"time"

	"github.com/Ammpapsoe/GozarFox/go-desktop/internal/app"
)

func TestDisconnectCancelsPendingConnectBeforeSuccess(t *testing.T) {
	for _, waitForStartup := range []bool{false, true} {
		current := &app.App{}
		rpcIn, send := io.Pipe()
		replies, rpcOut := io.Pipe()
		started := make(chan struct{})
		finished := make(chan struct{})
		server := &Server{app: current, out: rpcOut, ctx: context.Background()}
		go func() {
			defer close(finished)
			defer rpcOut.Close()
			server.serve(rpcIn, func(ctx context.Context) error {
				close(started)
				<-ctx.Done() // Never succeeds; only a real cancellation can unblock this.
				return ctx.Err()
			})
		}()
		encoder := json.NewEncoder(send)
		if err := encoder.Encode(Request{ID: 1, Method: "connect"}); err != nil {
			t.Fatal(err)
		}
		if waitForStartup {
			select {
			case <-started:
			case <-time.After(2 * time.Second):
				t.Fatal("connect did not start")
			}
		}
		if err := encoder.Encode(Request{ID: 2, Method: "disconnect"}); err != nil {
			t.Fatal(err)
		}
		_ = send.Close()
		decoded := make(chan []Response, 1)
		go func() {
			defer replies.Close()
			var got []Response
			d := json.NewDecoder(replies)
			for {
				var response Response
				if d.Decode(&response) != nil {
					break
				}
				got = append(got, response)
			}
			decoded <- got
		}()
		select {
		case got := <-decoded:
			if len(got) != 2 || got[0].ID != 1 || got[0].Error != "context canceled" || got[1].ID != 2 || got[1].Error != "" || got[1].State == nil || got[1].State.Live || got[1].State.Connecting {
				t.Fatalf("unexpected replies: %+v", got)
			}
		case <-time.After(3 * time.Second):
			t.Fatal("disconnect blocked behind connect")
		}
		<-finished
	}
}
