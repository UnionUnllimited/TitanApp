package burst

// Titan patch, iOS memory (scripts/ios-patches/xray-mem): copied into app/observatory/burst.

import "context"

// At most this many health probes at once. The observatory probed every subject together at
// each start (60-85 on the panel's balancers: a handshake, a pooled connection and pipes each,
// 16-24 MiB at once, and a restart had it all again), and each round at random times. Which
// subjects, how often, the timeouts and the results stay as they were: a probe that waits for
// a slot only starts later (its delay is measured from its own start).
const titanProbesAtOnce = 4

// titanSlot waits for a free slot; false if ctx ended first.
func (h *HealthPing) titanSlot(ctx context.Context) bool {
	if h.titanSlots == nil {
		return true
	}
	if ctx.Err() != nil {
		return false
	}
	select {
	case h.titanSlots <- struct{}{}:
		return true
	case <-ctx.Done():
		return false
	}
}

func (h *HealthPing) titanFree() {
	if h.titanSlots != nil {
		<-h.titanSlots
	}
}
