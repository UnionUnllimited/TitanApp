package burst

import (
	"bufio"
	"context"
	"fmt"
	gonet "net"
	"net/http"
	"slices"
	"sync"
	"sync/atomic"
	"testing"
	"time"

	"github.com/xtls/xray-core/common/net"
	"github.com/xtls/xray-core/features/routing"
	"github.com/xtls/xray-core/transport/internet/tagged"
)

// The observatory's probes run a few at a time (xray-mem patch), and every subject still gets
// its result: the first check 12 at a time in random order, the rounds 4 at a time; a stop
// ends the ones still waiting.
func TestTitanPacing(t *testing.T) {
	var waiting, peak, dials, checking atomic.Int32
	var mu sync.Mutex
	var order []string
	tagged.Dialer = func(ctx context.Context, _ routing.Dispatcher, dest net.Destination, tag string) (net.Conn, error) {
		dials.Add(1)
		mu.Lock()
		order = append(order, tag)
		mu.Unlock()
		if TitanChecking() > 0 {
			checking.Add(1)
		}
		n := waiting.Add(1)
		for p := peak.Load(); n > p && !peak.CompareAndSwap(p, n); p = peak.Load() {
		}
		client, server := gonet.Pipe()
		go func() {
			defer server.Close()
			if _, err := http.ReadRequest(bufio.NewReader(server)); err != nil {
				waiting.Add(-1)
				return
			}
			time.Sleep(20 * time.Millisecond)
			// Counted out before the answer: its probe holds its slot until it has it.
			waiting.Add(-1)
			server.Write([]byte("HTTP/1.1 204 No Content\r\n\r\n"))
		}()
		return client, nil
	}
	defer func() { tagged.Dialer = nil }()
	newHP := func() *HealthPing {
		return NewHealthPing(context.Background(), nil, &HealthPingConfig{
			Destination: "http://probe.invalid/generate_204", Interval: int64(time.Minute), SamplingCount: 1, Timeout: int64(5 * time.Second),
		})
	}
	results := func(h *HealthPing) int {
		h.access.Lock()
		defer h.access.Unlock()
		return len(h.Results)
	}
	tags := make([]string, 40)
	for i := range tags {
		tags[i] = fmt.Sprintf("bal-%02d", i) // sorted, as outbound.Manager.Select gives them
	}

	// The first check: 12 at once, not in tag order, every subject, counted while it runs.
	h := newHP()
	h.Check(tags)
	if p := peak.Load(); p != titanFirstAtOnce {
		t.Fatalf("first check: %d probes at once, want %d", p, titanFirstAtOnce)
	}
	if got := results(h); got != len(tags) {
		t.Fatalf("first check: %d results for %d subjects", got, len(tags))
	}
	first := slices.Clone(order[:titanFirstAtOnce])
	slices.Sort(first)
	if slices.Equal(first, tags[:titanFirstAtOnce]) {
		t.Fatalf("first check probed in tag order: %v", order[:titanFirstAtOnce])
	}
	if !slices.IsSorted(tags) {
		t.Fatal("the caller's tags were reordered")
	}
	if n := checking.Load(); n != int32(len(tags)) || TitanChecking() != 0 {
		t.Fatalf("first check counted in %d of %d probes, %d still counted after it", n, len(tags), TitanChecking())
	}

	// A round (spread out over its duration): 4 at once, every subject, not counted.
	h = newHP()
	peak.Store(0)
	checking.Store(0)
	h.doCheck(h.ctx, tags, 60*time.Millisecond, 1)
	if p := peak.Load(); p > titanProbesAtOnce || p == 0 {
		t.Fatalf("round: %d probes at once, want at most %d", p, titanProbesAtOnce)
	}
	if got := results(h); got != len(tags) {
		t.Fatalf("round: %d results for %d subjects", got, len(tags))
	}
	if n := checking.Load(); n != 0 {
		t.Fatalf("a round counted as a first check in %d probes", n)
	}

	// Stopped while most wait: they don't probe, and the check isn't counted any more.
	more := make([]string, 80)
	for i := range more {
		more[i] = fmt.Sprintf("bal-%02d", i)
	}
	h2 := newHP()
	dials.Store(0)
	done := make(chan struct{})
	go func() { h2.doCheck(h2.ctx, more, 0, 1); close(done) }()
	time.Sleep(5 * time.Millisecond)
	h2.cancelCtx()
	<-done
	time.Sleep(200 * time.Millisecond)
	if n := dials.Load(); n > 2*titanFirstAtOnce {
		t.Fatalf("%d probes after the stop", n)
	}
	if TitanChecking() != 0 {
		t.Fatalf("a stopped first check still counted")
	}
}
