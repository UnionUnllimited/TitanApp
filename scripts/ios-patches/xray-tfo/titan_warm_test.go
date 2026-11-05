package libXray

// Titan patch check (apply_libxray.py, libxray_warm.go), run by build-libxray-ios.sh: pingBatch
// with "warmTimeout" probes an outbound that answered once more in the same call, over a new
// connection (nothing kept alive), and answers the better delay and both; a second probe that
// fails leaves the first answer; one that didn't answer gets no second; without "warmTimeout"
// the answer is upstream's.

import (
	"encoding/json"
	"net"
	"net/http"
	"net/http/httptest"
	"strings"
	"sync/atomic"
	"testing"
	"time"
)

type titanWarmResult struct {
	Success bool    `json:"success"`
	Delay   int64   `json:"delay"`
	Error   string  `json:"error"`
	Delays  []int64 `json:"delays"`
}

// titanWarmServer answers HEAD requests, the nth after wait(n) (stopped by the cleanup), and
// counts requests and TCP connections.
func titanWarmServer(t *testing.T, wait func(n int64) time.Duration) (url string, requests, conns *atomic.Int64) {
	requests, conns = new(atomic.Int64), new(atomic.Int64)
	done := make(chan struct{})
	server := httptest.NewUnstartedServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if d := wait(requests.Add(1)); d > 0 {
			select {
			case <-time.After(d):
			case <-done:
			}
		}
		w.WriteHeader(http.StatusNoContent)
	}))
	server.Config.ConnState = func(_ net.Conn, s http.ConnState) {
		if s == http.StateNew {
			conns.Add(1)
		}
	}
	server.Start()
	t.Cleanup(func() { close(done); server.Close() })
	return server.URL, requests, conns
}

// titanWarmPing sends pingBatch as the app does (raw JSON through Invoke) with n configs.
func titanWarmPing(t *testing.T, url string, n, timeout, warmTimeout int) ([]titanWarmResult, string) {
	t.Helper()
	configs := make([]map[string]any, n)
	for i := range configs {
		configs[i] = map[string]any{"xrayJson": `{"outbounds":[{"protocol":"freedom","tag":"proxy"}]}`, "outboundTag": "proxy"}
	}
	payload := map[string]any{"configs": configs, "timeout": timeout, "url": url}
	if warmTimeout > 0 {
		payload["warmTimeout"] = warmTimeout
	}
	request, _ := json.Marshal(map[string]any{"apiVersion": 3, "method": "pingBatch", "payload": payload})
	raw := Invoke(string(request))
	var response struct {
		Success bool `json:"success"`
		Data    struct {
			Results []titanWarmResult `json:"results"`
		} `json:"data"`
	}
	if err := json.Unmarshal([]byte(raw), &response); err != nil || !response.Success || len(response.Data.Results) != n {
		t.Fatalf("pingBatch: %s (%v)", raw, err)
	}
	return response.Data.Results, raw
}

func TestTitanWarm(t *testing.T) {
	t.Run("the better of two, each over its own connection", func(t *testing.T) {
		url, requests, conns := titanWarmServer(t, func(n int64) time.Duration {
			if n == 1 {
				return 300 * time.Millisecond
			}
			return 0
		})
		results, raw := titanWarmPing(t, url, 1, 3, 2)
		r := results[0]
		if !r.Success || len(r.Delays) != 2 {
			t.Fatalf("got %s", raw)
		}
		if r.Delays[0] < 300 || r.Delays[1] < 0 || r.Delays[1] >= r.Delays[0] || r.Delay != r.Delays[1] {
			t.Fatalf("delay %d, delays %v: want the second (fast) one and both", r.Delay, r.Delays)
		}
		if requests.Load() != 2 || conns.Load() != 2 {
			t.Fatalf("%d requests over %d connections, want 2 over 2 (no keep-alive)", requests.Load(), conns.Load())
		}
		t.Logf("%s", raw)
	})

	t.Run("without warmTimeout: upstream's answer", func(t *testing.T) {
		url, requests, _ := titanWarmServer(t, func(int64) time.Duration { return 0 })
		results, raw := titanWarmPing(t, url, 1, 3, 0)
		if r := results[0]; !r.Success || r.Delay < 0 || r.Delays != nil || strings.Contains(raw, "delays") {
			t.Fatalf("got %s", raw)
		}
		if requests.Load() != 1 {
			t.Fatalf("%d requests, want 1", requests.Load())
		}
	})

	t.Run("a second probe that fails leaves the first", func(t *testing.T) {
		url, requests, _ := titanWarmServer(t, func(n int64) time.Duration {
			if n == 2 {
				return 3 * time.Second // past warmTimeout
			}
			return 0
		})
		results, raw := titanWarmPing(t, url, 1, 3, 1)
		r := results[0]
		if !r.Success || r.Error != "" || len(r.Delays) != 2 || r.Delays[1] != -1 || r.Delay != r.Delays[0] || r.Delay < 0 {
			t.Fatalf("got %s", raw)
		}
		if requests.Load() != 2 {
			t.Fatalf("%d requests, want 2", requests.Load())
		}
	})

	t.Run("no answer: no second probe", func(t *testing.T) {
		url, requests, _ := titanWarmServer(t, func(int64) time.Duration { return 3 * time.Second })
		results, raw := titanWarmPing(t, url, 1, 1, 2)
		if r := results[0]; r.Success || r.Delays != nil {
			t.Fatalf("got %s", raw)
		}
		if requests.Load() != 1 {
			t.Fatalf("%d requests, want 1", requests.Load())
		}
	})

	t.Run("several configs in one call: each its own two", func(t *testing.T) {
		url, requests, conns := titanWarmServer(t, func(int64) time.Duration { return 0 })
		results, raw := titanWarmPing(t, url, 3, 3, 2)
		for _, r := range results {
			if !r.Success || len(r.Delays) != 2 || r.Delays[1] < 0 || r.Delay != min(r.Delays[0], r.Delays[1]) {
				t.Fatalf("got %s", raw)
			}
		}
		if requests.Load() != 6 || conns.Load() != 6 {
			t.Fatalf("%d requests over %d connections, want 6 over 6", requests.Load(), conns.Load())
		}
	})
}
