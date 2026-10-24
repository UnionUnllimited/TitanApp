package burst

// Titan patch, iOS memory (scripts/ios-patches/xray-mem): copied into app/observatory/burst.

import (
	"context"
	"math/rand/v2"
	"strings"
	"sync"
	"sync/atomic"
	"time"
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

// First checks still running (a restart's new instance starts one).
var titanChecking atomic.Int32

// TitanChecking is how many first checks are still running: while one does, the new
// instance's probes are still adding connections to its pools (the tunnel reads what a start
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
}

// titanCheck: the order and the gate of a check, and what to call when it ends. A first
// check (no delay) gets its tags in titanOrder, each once, and a gate of its own.
func (h *HealthPing) titanCheck(tags []string, duration time.Duration) ([]string, *titanGate, func()) {
	if h.titanSlots == nil {
		return tags, nil, func() {}
	}
	if duration > 0 {
		return tags, &titanGate{slots: h.titanSlots}, func() {}
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
	select {
	case g.slots <- struct{}{}:
		return true
	case <-ctx.Done():
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
