package burst

import (
	"bufio"
	"context"
	"fmt"
	gonet "net"
	"net/http"
	"slices"
	"strings"
	"sync"
	"sync/atomic"
	"testing"
	"time"

	"github.com/xtls/xray-core/common/net"
	"github.com/xtls/xray-core/features/routing"
	"github.com/xtls/xray-core/transport/internet/tagged"
)

// The observatory's probes run a few at a time (xray-mem patch), and every subject still gets
// its result: the first check 12 at a time, every group of subjects in its first 12, the
// rounds 4 at a time; a stop ends the ones still waiting.
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
	// A small group that sorts last, as outbound.Manager.Select gives them.
	tags := make([]string, 44)
	for i := range tags {
		tags[i] = fmt.Sprintf("bal-%02d", i)
		if i >= 40 {
			tags[i] = fmt.Sprintf("yt-ru-%d", i-40)
		}
	}
	selectors := []string{"bal", "yt-ru"}

	// The order: each tag once, every group (the longest selector a tag starts with, or
	// none) in its first turn, each group's tags in random order.
	in := append(slices.Clone(tags), "bal-03", "other-1", "yt-ru-x", "yt-ru-y")
	orders := map[string]bool{}
	for i := 0; i < 20; i++ {
		o := titanOrder(in, []string{"bal", "yt-ru", "yt-ru-x"})
		if len(o) != len(in)-1 {
			t.Fatalf("order: %d tags of %d", len(o), len(in)-1)
		}
		groups := map[string]int{}
		for _, tag := range o[:4] {
			switch {
			case tag == "yt-ru-x":
				groups["yt-ru-x"]++
			case strings.HasPrefix(tag, "yt-ru"):
				groups["yt-ru"]++
			default:
				groups[tag[:3]]++
			}
		}
		if len(groups) != 4 {
			t.Fatalf("order: the first turn %v, not one of each group", o[:4])
		}
		orders[strings.Join(o, ",")] = true
	}
	if len(orders) < 2 {
		t.Fatal("order: not shuffled")
	}

	// The first check: 12 at once, the small group in its first 12, every subject, counted
	// while it runs.
	h := newHP()
	h.titanSelectors = selectors
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
	if !slices.Equal(first[len(first)-4:], tags[40:]) {
		t.Fatalf("first check: the small group not in the first %d: %v", titanFirstAtOnce, order[:titanFirstAtOnce])
	}
	if !slices.IsSorted(tags) {
		t.Fatal("the caller's tags were reordered")
	}
	if n := checking.Load(); n != int32(len(tags)) || TitanChecking() != 0 {
		t.Fatalf("first check counted in %d of %d probes, %d still counted after it", n, len(tags), TitanChecking())
	}

	// A tag twice: probed once, and the check ends.
	h = newHP()
	ended := make(chan struct{})
	go func() { h.Check(append([]string{tags[0]}, tags...)); close(ended) }()
	select {
	case <-ended:
	case <-time.After(10 * time.Second):
		t.Fatal("first check with a tag twice: not ended")
	}
	if got := results(h); got != len(tags) {
		t.Fatalf("first check with a tag twice: %d results for %d subjects", got, len(tags))
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
