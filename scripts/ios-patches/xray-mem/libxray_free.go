package memory

// Titan patch, iOS (scripts/ios-patches/xray-mem/apply_libxray.py): copied into libXray's memory
// package as titan_free.go.

import "time"

// How often libXray's loop (memory_ios.go forceFree) gives Go's free memory back to iOS:
// debug.FreeOSMemory, a forced full GC (the whole live heap marked) and a scavenge of every free
// page. Every second upstream, traffic or not: a wake and a GC each second for as long as the
// tunnel runs. Go's own scavenger returns free pages too (toward the soft limit, which the tunnel
// sets, and toward the heap goal plus 10%), and the tunnel frees where the footprint matters
// (PacketTunnelProvider): every 5 s above 35 MiB, on memory pressure (iOS's own warning at 80% of
// the limit among them) and before a memory restart. Stand (60 subjects, burst observatory 5m × 5,
// Go 1.27, GOGC 10, no traffic; HANDOFF «Батарея на iOS»): GC cycles 62 → 8 a minute, the process's
// CPU 0.365 → 0.105 s a minute, RssAnon 8.9 → 9.8 MiB.
const titanFreeEvery = 60 * time.Second

// titanFreeLoop calls free every `every`, the first time `every` after it starts, until stop is
// closed (libXray's: never).
func titanFreeLoop(every time.Duration, free func(), stop <-chan struct{}) {
	t := time.NewTicker(every)
	defer t.Stop()
	for {
		select {
		case <-t.C:
			free()
		case <-stop:
			return
		}
	}
}
