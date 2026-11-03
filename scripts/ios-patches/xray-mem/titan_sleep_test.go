package burst

import (
	"bufio"
	"context"
	"fmt"
	gonet "net"
	"net/http"
	"sync"
	"sync/atomic"
	"testing"
	"time"

	"github.com/xtls/xray-core/common/net"
	"github.com/xtls/xray-core/features/routing"
	"github.com/xtls/xray-core/transport/internet/tagged"
)

// The device's sleep (xray-mem patch: titan sleep / wake from the tunnel): asleep, the rounds send
// nothing (a round under way stops, those of the ticks do nothing), a first check (a restart's new
// instance) still runs, the results stay valid however long it sleeps; awake, after a long sleep a
// wake round probes every subject once, titanWakeDelay after the wake, within titanWakeSpread, a few
// at once; after a short one only the schedule goes on; a sleep stops a wake round too.
func TestTitanSleep(t *testing.T) {
	// The end, whatever the test did: its schedulers stopped (their goroutines gone), awake, then
	// the dialer and the variables back (nothing reads them by then).
	var hps []*HealthPing
	defer func(a, d, s time.Duration) {
		for _, h := range hps {
			h.StopScheduler()
		}
		time.Sleep(100 * time.Millisecond)
		TitanWake()
		time.Sleep(50 * time.Millisecond)
		tagged.Dialer = nil
		titanWakeAfter, titanWakeDelay, titanWakeSpread = a, d, s
	}(titanWakeAfter, titanWakeDelay, titanWakeSpread)
	titanWakeAfter, titanWakeDelay, titanWakeSpread = 200*time.Millisecond, 150*time.Millisecond, 60*time.Millisecond
	const answerIn = 80 * time.Millisecond

	type dial struct {
		tag string
		at  time.Time
	}
	var mu sync.Mutex
	var dials []dial
	var open, peak atomic.Int32
	tagged.Dialer = func(ctx context.Context, _ routing.Dispatcher, dest net.Destination, tag string) (net.Conn, error) {
		mu.Lock()
		dials = append(dials, dial{tag, time.Now()})
		mu.Unlock()
		n := open.Add(1)
		for p := peak.Load(); n > p && !peak.CompareAndSwap(p, n); p = peak.Load() {
		}
		client, server := gonet.Pipe()
		go func() {
			defer server.Close()
			defer open.Add(-1)
			if _, err := http.ReadRequest(bufio.NewReader(server)); err != nil {
				return
			}
			time.Sleep(answerIn)
			server.Write([]byte("HTTP/1.1 204 No Content\r\n\r\n"))
		}()
		return client, nil
	}
	since := func(at time.Time) []dial {
		mu.Lock()
		defer mu.Unlock()
		var out []dial
		for _, d := range dials {
			if !d.at.Before(at) {
				out = append(out, d)
			}
		}
		return out
	}
	idle := func() {
		for i := 0; i < 100 && open.Load() > 0; i++ {
			time.Sleep(10 * time.Millisecond)
		}
	}
	tags := make([]string, 8)
	for i := range tags {
		tags[i] = fmt.Sprintf("bal-%d", i)
	}
	selector := func() ([]string, error) { return tags, nil }
	newHP := func(interval time.Duration, sampling int) *HealthPing {
		h := NewHealthPing(context.Background(), nil, &HealthPingConfig{
			Destination: "http://probe.invalid/generate_204", Interval: int64(time.Minute), SamplingCount: int32(sampling), Timeout: int64(5 * time.Second),
		})
		h.Settings.Interval = interval // under NewHealthPing's 10 s at least
		hps = append(hps, h)
		return h
	}
	valid := func(h *HealthPing) int {
		h.access.Lock()
		defer h.access.Unlock()
		n := 0
		for _, r := range h.Results {
			if r.Get().All > 0 {
				n++
			}
		}
		return n
	}

	// Asleep the rounds send nothing: 8 subjects, a round a second with 10 probes each (80 a
	// second at awake, ~16 in a 200 ms stretch).
	h := newHP(100*time.Millisecond, 10)
	h.StartScheduler(selector)
	time.Sleep(400 * time.Millisecond)
	if n := len(since(time.Time{})); n < len(tags)+10 {
		t.Fatalf("awake: %d probes in 400 ms, want the first check's %d and the round's", n, len(tags))
	}
	if !TitanSleep() || TitanSleep() {
		t.Fatal("TitanSleep: not once")
	}
	idle()
	asleep := time.Now()
	time.Sleep(1200 * time.Millisecond) // the tick's next round too
	if got := since(asleep); len(got) != 0 {
		t.Fatalf("asleep: %d probes in 1.2 s (%s ...)", len(got), got[0].tag)
	}
	// The results stay: valid 2 s (interval × sampling × 2), 1.6 s or more since the first ones.
	if n := valid(h); n != len(tags) {
		t.Fatalf("asleep: %d of %d subjects with valid results", n, len(tags))
	}
	// A first check (a new instance) still runs.
	h2 := newHP(time.Hour, 5)
	at := time.Now()
	h2.Check(tags)
	if got := since(at); len(got) != len(tags) {
		t.Fatalf("a first check asleep: %d probes for %d subjects", len(got), len(tags))
	}
	h.StopScheduler()
	h2.StopScheduler()
	if TitanWake() < time.Second || TitanWake() != 0 {
		t.Fatal("TitanWake: not the sleep's length, or not once")
	}

	// Results: validity counts awake time only (here 2 × 50 ms × 2 = 200 ms).
	h3 := newHP(50*time.Millisecond, 2)
	for _, tag := range tags {
		h3.PutResult(tag, 30*time.Millisecond)
	}
	TitanSleep()
	time.Sleep(400 * time.Millisecond)
	if n := valid(h3); n != len(tags) {
		t.Fatalf("results asleep 400 ms: %d of %d valid", n, len(tags))
	}
	TitanWake()
	time.Sleep(100 * time.Millisecond)
	if n := valid(h3); n != len(tags) {
		t.Fatalf("results 100 ms after the wake: %d of %d valid", n, len(tags))
	}
	time.Sleep(150 * time.Millisecond)
	if n := valid(h3); n != 0 {
		t.Fatalf("results 250 ms awake: %d still valid (200 ms)", n)
	}

	// A wake round: a schedule of 5 h (none of its probes here), asleep 300 ms (over
	// titanWakeAfter): each subject once, titanWakeDelay after the wake, within titanWakeSpread,
	// at most titanProbesAtOnce at once (8 probes of 80 ms in 60 ms: all at once without the
	// slots).
	h4 := newHP(time.Hour, 5)
	h4.StartScheduler(selector)
	time.Sleep(50 * time.Millisecond)
	idle()
	rounds := titanWakeRounds.Load()
	TitanSleep()
	time.Sleep(300 * time.Millisecond)
	peak.Store(0)
	woke := time.Now()
	TitanWake()
	time.Sleep(titanWakeDelay - 20*time.Millisecond)
	if got := since(woke); len(got) != 0 {
		t.Fatalf("wake round: %d probes before its delay", len(got))
	}
	time.Sleep(titanWakeSpread + 3*answerIn + 200*time.Millisecond)
	got := since(woke)
	seen := map[string]int{}
	for _, d := range got {
		seen[d.tag]++
	}
	if len(got) != len(tags) || len(seen) != len(tags) {
		t.Fatalf("wake round: %d probes on %d of %d subjects", len(got), len(seen), len(tags))
	}
	if p := peak.Load(); p != titanProbesAtOnce {
		t.Fatalf("wake round: %d probes at once, want %d", p, titanProbesAtOnce)
	}
	if n := titanWakeRounds.Load() - rounds; n != 1 {
		t.Fatalf("%d wake rounds", n)
	}

	// A short sleep: no wake round, nothing probed.
	TitanSleep()
	time.Sleep(50 * time.Millisecond)
	woke = time.Now()
	TitanWake()
	time.Sleep(titanWakeDelay + titanWakeSpread + 3*answerIn)
	if got := since(woke); len(got) != 0 || titanWakeRounds.Load()-rounds != 1 {
		t.Fatalf("a short sleep: %d probes, %d wake rounds", len(got), titanWakeRounds.Load()-rounds-1)
	}

	// Asleep again before the wake round starts: it doesn't.
	TitanSleep()
	time.Sleep(300 * time.Millisecond)
	woke = time.Now()
	TitanWake()
	time.Sleep(titanWakeDelay / 3)
	TitanSleep()
	time.Sleep(titanWakeDelay + titanWakeSpread + 3*answerIn)
	if got := since(woke); len(got) != 0 {
		t.Fatalf("a wake round asleep again before its start: %d probes", len(got))
	}
}
