package burst

// Titan patch, iOS memory (scripts/ios-patches/xray-mem): copied into app/observatory/burst.

import (
	"context"
	"math/rand/v2"
	"strings"
	"sync"
	"sync/atomic"
	"time"

	"github.com/xtls/xray-core/transport/internet"
)

// At most this many health probes at once in the scheduled rounds. The observatory probed
// every subject together at each start (60-85 on the panel's balancers: a handshake, a pooled
// connection and pipes each, 16-24 MiB at once, and a restart had it all again), and each
// round at random times. Which subjects, how often, the timeouts and the results stay as they
// were: a probe that waits for a slot only starts later (its delay is measured from its own
// start).
const titanProbesAtOnce = 4

// The first check (Check: every subject with no delay, at each start) has its own slots, more
// of them, and its own order. The balancers choose from the results in so far (leastLoad from
// the tags that have one), and the subjects come sorted by tag (outbound.Manager.Select): in
// that order leastLoad chose among the first tags for 20-60 s after every start. Shuffled as
// one list, a small group (8 of 60) had its first results seconds after the big one's, and a
// balancer over it chose from the big one's till then. So: each group of subjects (the
// observatory's subjectSelector prefix a tag has) shuffled, the groups taken in turn: the
// first 12 have members of every group, every balancer's own results come in the first wave
// as with every probe at once, and the rest come in random order. 12 at a time it is all in
// after 85 / 12 ≈ 7 probe times (7-10 s on LTE).
const titanFirstAtOnce = 12

// titanProbeContext is the probes' context, marked as theirs (internet.TitanProbeContext): the
// pooled transports give each probe a connection of its own, closed as it ends, so every probe is
// cold, not a mix of warm and cold ones whose deviation leastLoad would rank by.
func titanProbeContext(ctx context.Context) context.Context {
	return internet.TitanProbeContext(ctx)
}

// First checks still running (a restart's new instance starts one).
var titanChecking atomic.Int32

// TitanChecking is how many first checks are still running: while one does, the new
// instance's probes have their own connections up, 12 at a time (the tunnel reads what a start
// left once it's done).
func TitanChecking() int32 { return titanChecking.Load() }

// titanGate: the probes of one check, a few at once. A round's share the HealthPing's slots
// (one round still running when the next starts counts against it) and start in any order;
// a first check's start in its own order, turn by turn, not in whatever order their timers'
// goroutines run.
type titanGate struct {
	slots chan struct{}
	turn  map[string]int  // the tag's turn
	ready []chan struct{} // closed: that turn may start
	mu    sync.Mutex
	next  int // the next turn to open
	// A round's: closed when the device goes to sleep (titanSleep). A first check's: nil.
	asleep chan struct{}
}

// titanCheck: the order and the gate of a check, and what to call when it ends. A first
// check (no delay) gets its tags in titanOrder, each once, and a gate of its own.
func (h *HealthPing) titanCheck(tags []string, duration time.Duration) ([]string, *titanGate, func()) {
	if h.titanSlots == nil {
		return tags, nil, func() {}
	}
	if duration > 0 {
		// A round: none while the device sleeps (it stops a round under way too: doCheck).
		asleep := titanSleepCh()
		if titanClosed(asleep) {
			return nil, nil, func() {}
		}
		return tags, &titanGate{slots: h.titanSlots, asleep: asleep}, func() {}
	}
	tags = titanOrder(tags, h.titanSelectors)
	g := &titanGate{turn: make(map[string]int, len(tags)), ready: make([]chan struct{}, len(tags))}
	for i, tag := range tags {
		g.turn[tag] = i
		g.ready[i] = make(chan struct{})
	}
	for g.next < min(titanFirstAtOnce, len(tags)) {
		close(g.ready[g.next])
		g.next++
	}
	titanChecking.Add(1)
	return tags, g, func() { titanChecking.Add(-1) }
}

// titanOrder: the tags each once, grouped by the longest selector each starts with (none:
// a group too), shuffled within each group, the groups (in random order) taken in turn.
func titanOrder(tags, selectors []string) []string {
	groups := map[string][]string{}
	var keys []string
	seen := make(map[string]bool, len(tags))
	for _, tag := range tags {
		if seen[tag] {
			continue
		}
		seen[tag] = true
		key := ""
		for _, s := range selectors {
			if strings.HasPrefix(tag, s) && len(s) > len(key) {
				key = s
			}
		}
		if _, ok := groups[key]; !ok {
			keys = append(keys, key)
		}
		groups[key] = append(groups[key], tag)
	}
	rand.Shuffle(len(keys), func(i, j int) { keys[i], keys[j] = keys[j], keys[i] })
	for _, g := range groups {
		rand.Shuffle(len(g), func(i, j int) { g[i], g[j] = g[j], g[i] })
	}
	out := make([]string, 0, len(seen))
	for len(out) < len(seen) {
		for _, k := range keys {
			if g := groups[k]; len(g) > 0 {
				out = append(out, g[0])
				groups[k] = g[1:]
			}
		}
	}
	return out
}

// titanSlot waits for tag's slot (a first check: its turn); false if ctx ended first.
func (h *HealthPing) titanSlot(ctx context.Context, g *titanGate, tag string) bool {
	if g == nil {
		return true
	}
	if ctx.Err() != nil {
		return false
	}
	if g.ready != nil {
		i, ok := g.turn[tag]
		if !ok {
			return true
		}
		select {
		case <-g.ready[i]:
			return true
		case <-ctx.Done():
			return false
		}
	}
	if titanClosed(g.asleep) {
		return false
	}
	select {
	case g.slots <- struct{}{}:
		// Asleep while it waited: no probe.
		if titanClosed(g.asleep) {
			<-g.slots
			return false
		}
		return true
	case <-ctx.Done():
		return false
	case <-g.asleep:
		return false
	}
}

// titanFree: a probe done, the next may start (a first check: the next turn).
func (h *HealthPing) titanFree(g *titanGate) {
	if g == nil {
		return
	}
	if g.ready != nil {
		g.mu.Lock()
		if g.next < len(g.ready) {
			close(g.ready[g.next])
			g.next++
		}
		g.mu.Unlock()
		return
	}
	<-g.slots
}

// titanAsleep is closed when the device goes to sleep, for a round (nil for a first check, and
// without the patch's slots: never).
func (g *titanGate) titanAsleep() <-chan struct{} {
	if g == nil {
		return nil
	}
	return g.asleep
}

// The device's sleep (TitanSleep / TitanWake: titan's sleep and wake, from the tunnel's
// NEPacketTunnelProvider sleep and wake). The rounds' ~60 subjects at 5m × 5 are a probe every
// ~5 s, each a cold handshake on a connection of its own: with the phone asleep they kept the
// cellular radio from ever going idle. While it sleeps the rounds probe nothing (a round under
// way stops, the next ticks' do nothing); a first check (a new instance: a restart) still runs, the
// balancers have no results without it. The results the balancers choose from stay, and don't age
// while it sleeps (titanAge): validity (interval × sampling × 2) counts awake time only, so a
// night asleep doesn't leave every subject without results (not alive: no qualified outbound).
var titanSleep = struct {
	sync.Mutex
	asleep  bool
	sleepCh chan struct{} // closed when the device goes to sleep; a new one when it wakes
	wakeCh  chan struct{} // closed when it wakes; a new one then
	since   time.Time     // sleep's start, the wall clock's (Apple's monotonic clock stops in sleep)
	away    time.Duration // the last sleep, wall clock
	// Read for every result (titanAge), so atomic: the monotonic time asleep before this sleep,
	// and this sleep's start (since titanEpoch, ns; 0 while awake).
	slept   atomic.Int64
	sleptAt atomic.Int64
}{sleepCh: make(chan struct{}), wakeCh: make(chan struct{})}

// The monotonic clock's origin for titanSleep's atomics.
var titanEpoch = time.Now()

func titanMono() int64 { return max(int64(time.Since(titanEpoch)), 1) }

// After a sleep this long (the wall clock's) or longer, a wake round (titanWakeRound): every
// subject once, starting titanWakeDelay after the wake and within titanWakeSpread, a few at
// once (the rounds' slots). Shorter, the schedule just goes on (a round right away). The panel's
// interval (5 min): each subject has missed a probe on average by then. The wake round's start
// waits for the network to be back (a failed probe counts against its subject). Variables for
// the tests.
var (
	titanWakeAfter  = 5 * time.Minute
	titanWakeDelay  = 5 * time.Second
	titanWakeSpread = 25 * time.Second
)

// Wake rounds started (for the tests).
var titanWakeRounds atomic.Int32

// TitanSleep: the device goes to sleep; the rounds stop. Once until TitanWake.
func TitanSleep() bool {
	s := &titanSleep
	s.Lock()
	defer s.Unlock()
	if s.asleep {
		return false
	}
	s.asleep = true
	s.since = time.Now().Round(0)
	s.sleptAt.Store(titanMono())
	close(s.sleepCh)
	return true
}

// TitanWake: the device woke; the rounds go on (each scheduler a round at once, and after
// titanWakeAfter asleep a wake round first). It returns how long it slept (wall clock), 0 if
// it wasn't asleep.
func TitanWake() time.Duration {
	s := &titanSleep
	s.Lock()
	defer s.Unlock()
	if !s.asleep {
		return 0
	}
	s.asleep = false
	s.slept.Add(titanMono() - s.sleptAt.Load())
	s.sleptAt.Store(0)
	s.away = max(time.Since(s.since), 0)
	s.sleepCh = make(chan struct{})
	close(s.wakeCh)
	s.wakeCh = make(chan struct{})
	return s.away
}

// TitanAsleep says the device sleeps now (TitanSleep, no TitanWake since).
func TitanAsleep() bool {
	titanSleep.Lock()
	defer titanSleep.Unlock()
	return titanSleep.asleep
}

func titanSleepCh() chan struct{} {
	titanSleep.Lock()
	defer titanSleep.Unlock()
	return titanSleep.sleepCh
}

func titanClosed(ch chan struct{}) bool {
	if ch == nil {
		return false
	}
	select {
	case <-ch:
		return true
	default:
		return false
	}
}

// titanSlept is the monotonic time the device has slept so far (with a sleep under way). The
// wake's Add comes before its Store(0): read in between, a sleep counts twice for a moment (results
// look younger, never older).
func titanSlept() time.Duration {
	s := &titanSleep
	slept := s.slept.Load()
	if at := s.sleptAt.Load(); at > 0 {
		slept += titanMono() - at
	}
	return time.Duration(slept)
}

// titanAge is r's age for its validity (healthping_result.go getStatistics, slept titanSlept()
// once a call): the time since it was measured less the device's sleep since (none of it counts).
func titanAge(r *pingRTT, slept time.Duration) time.Duration {
	return time.Since(r.time) - (slept - r.titanSlept)
}

// titanWait waits for the scheduler's next tick (StartScheduler): true then (its round), false
// once h stops. A wake is a tick too: the round the sleep stopped is gone, the schedule goes on
// at once; after titanWakeAfter or more asleep, with a wake round first.
func (h *HealthPing) titanWait(ticker *time.Ticker, selector func() ([]string, error)) bool {
	titanSleep.Lock()
	woke := titanSleep.wakeCh
	titanSleep.Unlock()
	select {
	case <-ticker.C:
		return true
	case <-h.ctx.Done():
		return false
	case <-woke:
		titanSleep.Lock()
		away := titanSleep.away
		titanSleep.Unlock()
		if away >= titanWakeAfter {
			go h.titanWakeRound(selector)
		}
		return true
	}
}

// titanWakeRound: every subject once, titanWakeDelay after the wake, spread over titanWakeSpread,
// at most titanProbesAtOnce at once (a round's slots), so the balancers choose from what the
// network is now, not from before the sleep. A round of its own (the schedule's next one doesn't
// cancel it); a sleep stops it, as it does a round.
func (h *HealthPing) titanWakeRound(selector func() ([]string, error)) {
	titanWakeRounds.Add(1)
	asleep := titanSleepCh()
	t := time.NewTimer(titanWakeDelay)
	defer t.Stop()
	select {
	case <-t.C:
	case <-asleep:
		return
	case <-h.ctx.Done():
		return
	}
	tags, err := selector()
	if err != nil {
		return
	}
	h.doCheck(h.ctx, tags, titanWakeSpread, 1)
}
