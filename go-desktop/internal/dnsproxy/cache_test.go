package dnsproxy

import (
	"bytes"
	"io"
	"net/http"
	"sync"
	"sync/atomic"
	"testing"
	"time"

	"golang.org/x/net/dns/dnsmessage"
)

func TestResponseCacheRealDoHReusesAnswerWithClientIDAndAgedTTL(t *testing.T) {
	var requests atomic.Int32
	r, _, _ := setup(t, http.HandlerFunc(func(w http.ResponseWriter, req *http.Request) {
		body, _ := io.ReadAll(req.Body)
		requests.Add(1)
		w.Header().Set("Content-Type", "application/dns-message")
		_, _ = w.Write(answerFor(t, body))
	}))
	now := time.Unix(1000, 0)
	r.cache.now = func() time.Time { return now }
	first := query(t, 1, "example.org.", dnsmessage.TypeA)
	one, err := r.Resolve(first)
	if err != nil {
		t.Fatal(err)
	}
	now = now.Add(1100 * time.Millisecond)
	second := query(t, 2, "example.org.", dnsmessage.TypeA)
	two, err := r.Resolve(second)
	if err != nil {
		t.Fatal(err)
	}
	var a, b dnsmessage.Message
	if a.Unpack(one) != nil || b.Unpack(two) != nil || b.ID != 2 || a.ID != 1 || a.Answers[0].Header.TTL != 27 || b.Answers[0].Header.TTL != 25 || requests.Load() != 1 {
		t.Fatalf("cache id or TTL mismatch: first=%+v second=%+v requests=%d", a, b, requests.Load())
	}
	// A caller mutating its response must not mutate the cache.
	two[0] = 99
	three, err := r.Resolve(second)
	if err != nil || three[0] != 0 || three[1] != 2 {
		t.Fatal("cached response was shared", err)
	}
	now = now.Add(26 * time.Second)
	if _, err = r.Resolve(second); err != nil || requests.Load() != 2 {
		t.Fatalf("expired answer used: requests=%d err=%v", requests.Load(), err)
	}
}

// A controlled loopback test counts actual DoH requests and reports latency;
// the injected delay is not a measurement of a real relay or user network.
func TestResponseCacheLoopbackLatencyDiagnostic(t *testing.T) {
	var requests atomic.Int32
	r, _, _ := setup(t, http.HandlerFunc(func(w http.ResponseWriter, req *http.Request) {
		body, _ := io.ReadAll(req.Body)
		requests.Add(1)
		time.Sleep(40 * time.Millisecond)
		w.Header().Set("Content-Type", "application/dns-message")
		_, _ = w.Write(answerFor(t, body))
	}))
	start := time.Now()
	if _, err := r.Resolve(query(t, 1, "example.org.", dnsmessage.TypeA)); err != nil {
		t.Fatal(err)
	}
	cold := time.Since(start)
	start = time.Now()
	for i := 0; i < 50; i++ {
		if _, err := r.Resolve(query(t, uint16(i+2), "example.org.", dnsmessage.TypeA)); err != nil {
			t.Fatal(err)
		}
	}
	warm := time.Since(start)
	if requests.Load() != 1 {
		t.Fatalf("expected one DoH request, got %d", requests.Load())
	}
	t.Logf("simulated 40ms DoH delay: first=%s; 50 cached queries=%s; upstream requests=%d (local test only)", cold, warm, requests.Load())
}

func TestResponseCacheExcludesComplexQueriesAndResponses(t *testing.T) {
	base := query(t, 3, "example.org.", dnsmessage.TypeA)
	valid := answerFor(t, base)
	for _, tc := range []string{"negative", "nodata", "zero-ttl", "truncated", "additional", "different-owner", "cname", "edns-query", "dnssec-query", "changed-flags", "changed-case"} {
		t.Run(tc, func(t *testing.T) {
			cache := newResponseCache()
			var q, a dnsmessage.Message
			if q.Unpack(base) != nil || a.Unpack(valid) != nil {
				t.Fatal("bad fixture")
			}
			switch tc {
			case "negative":
				a.RCode = dnsmessage.RCodeNameError
				a.Answers = nil
			case "nodata":
				a.Answers = nil
			case "zero-ttl":
				a.Answers[0].Header.TTL = 0
			case "truncated":
				a.Truncated = true
			case "additional":
				a.Additionals = []dnsmessage.Resource{{Header: dnsmessage.ResourceHeader{Name: dnsmessage.MustNewName("."), Type: dnsmessage.TypeOPT, Class: 1232}, Body: &dnsmessage.OPTResource{}}}
			case "different-owner":
				a.Answers[0].Header.Name = dnsmessage.MustNewName("other.example.")
			case "cname":
				a.Answers[0].Header.Type = dnsmessage.TypeCNAME
				a.Answers[0].Body = &dnsmessage.CNAMEResource{CNAME: dnsmessage.MustNewName("other.example.")}
			case "edns-query":
				q.Additionals = []dnsmessage.Resource{{Header: dnsmessage.ResourceHeader{Name: dnsmessage.MustNewName("."), Type: dnsmessage.TypeOPT, Class: 1232}, Body: &dnsmessage.OPTResource{}}}
			case "dnssec-query":
				q.Additionals = []dnsmessage.Resource{{Header: dnsmessage.ResourceHeader{Name: dnsmessage.MustNewName("."), Type: dnsmessage.TypeOPT, Class: 1232, TTL: 0x8000}, Body: &dnsmessage.OPTResource{}}}
			case "changed-flags":
				q.CheckingDisabled = true
			case "changed-case":
				q.Questions[0].Name = dnsmessage.MustNewName("Example.org.")
			}
			if tc == "changed-flags" || tc == "changed-case" {
				cache.put(base, valid)
				qwire, err := q.Pack()
				if err != nil {
					t.Fatal(err)
				}
				if _, ok := cache.get(qwire); ok {
					t.Fatal("different query reused cache")
				}
				return
			}
			qwire, err := q.Pack()
			if err != nil {
				t.Fatal(err)
			}
			awire, err := a.Pack()
			if err != nil {
				t.Fatal(err)
			}
			cache.put(qwire, awire)
			if _, ok := cache.get(qwire); ok {
				t.Fatal("unsupported DNS message was cached")
			}
		})
	}
}

func TestResponseCacheBoundedAndClosed(t *testing.T) {
	cache := newResponseCache()
	for i := 0; i < cacheMaxEntries+20; i++ {
		name := dnsmessage.MustNewName(string(rune('a'+i%26)) + "." + string(rune('a'+i/26)) + ".example.")
		q := dnsmessage.Message{Header: dnsmessage.Header{ID: 1, RecursionDesired: true}, Questions: []dnsmessage.Question{{Name: name, Type: dnsmessage.TypeA, Class: dnsmessage.ClassINET}}}
		wire, err := q.Pack()
		if err != nil {
			t.Fatal(err)
		}
		cache.put(wire, answerFor(t, wire))
	}
	if len(cache.entries) > cacheMaxEntries {
		t.Fatal("unbounded cache")
	}
	q := query(t, 1, "last.example.", dnsmessage.TypeA)
	cache.clear()
	cache.put(q, answerFor(t, q))
	if len(cache.entries) != 0 {
		t.Fatal("cache reopened after close")
	}
	if _, ok := cache.get(q); ok {
		t.Fatal("cache served after close")
	}
}

func TestResponseCacheConcurrentAccess(t *testing.T) {
	cache := newResponseCache()
	q := query(t, 1, "example.org.", dnsmessage.TypeA)
	a := answerFor(t, q)
	var wg sync.WaitGroup
	for i := 0; i < 16; i++ {
		wg.Add(1)
		go func() {
			defer wg.Done()
			for n := 0; n < 100; n++ {
				cache.put(q, a)
				result, ok := cache.get(q)
				if ok && !bytes.Equal(result[:2], q[:2]) {
					t.Error("incorrect ID")
				}
			}
		}()
	}
	wg.Wait()
}
