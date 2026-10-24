package burst

// Titan patch, iOS memory (scripts/ios-patches/xray-mem): copied into app/observatory/burst.

import (
	"context"
	"math/rand/v2"
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
// of them, and goes in random order. The balancers choose from the results in so far, and the
// subjects come sorted by tag (outbound.Manager.Select): 4 at a time in that order, leastLoad
// chose among the first tags in sort order for 20-60 s after every start (and the panel's
// cheapest group sorts last). In random order what is in so far is a random sample of all
// the subjects, and 12 at a time it is all in after 85 / 12 ≈ 7 probe times (7-10 s on LTE).
const titanFirstAtOnce = 12

// First checks still running (a restart's new instance starts one).
var titanChecking atomic.Int32

// TitanChecking is how many first checks are still running: while one does, the new
// instance's probes are still adding connections to its pools (the tunnel reads what a start
// left once it's done).
func TitanChecking() int32 { return titanChecking.Load() }

// titanCheck: the order and the slots of a check, and what to call when it ends. A first
// check (no delay) gets a shuffled copy of the tags and slots of its own.
func (h *HealthPing) titanCheck(tags []string, duration time.Duration) ([]string, chan struct{}, func()) {
	if h.titanSlots == nil || duration > 0 {
		return tags, h.titanSlots, func() {}
	}
	tags = append([]string(nil), tags...)
	rand.Shuffle(len(tags), func(i, j int) { tags[i], tags[j] = tags[j], tags[i] })
	titanChecking.Add(1)
	return tags, make(chan struct{}, titanFirstAtOnce), func() { titanChecking.Add(-1) }
}

// titanSlot waits for a free slot; false if ctx ended first.
func (h *HealthPing) titanSlot(ctx context.Context, slots chan struct{}) bool {
	if slots == nil {
		return true
	}
	if ctx.Err() != nil {
		return false
	}
	select {
	case slots <- struct{}{}:
		return true
	case <-ctx.Done():
		return false
	}
}

func (h *HealthPing) titanFree(slots chan struct{}) {
	if slots != nil {
		<-slots
	}
}
