package titan

import (
	"errors"
	"runtime"
	"runtime/debug"
	"runtime/metrics"
	"sync"
	"time"

	"github.com/xtls/xray-core/app/observatory/burst"
	"github.com/xtls/xray-core/transport/internet"
)

// What memStats reads (runtime/metrics: no stop-the-world), by its key in the answer.
var memMetrics = [][2]string{
	{"total", "/memory/classes/total:bytes"},            // all the Go runtime has mapped
	{"released", "/memory/classes/heap/released:bytes"}, // of it, given back to the OS
	{"liveHeap", "/gc/heap/live:bytes"},                 // heap live after the last GC
	{"objects", "/memory/classes/heap/objects:bytes"},   // live and not yet swept dead objects
	{"free", "/memory/classes/heap/free:bytes"},         // free pages not given back yet
	{"stacks", "/memory/classes/heap/stacks:bytes"},
	{"goroutines", "/sched/goroutines:goroutines"},
	{"limit", "/gc/gomemlimit:bytes"}, // the soft limit (setMemoryLimit)
	{"gogc", "/gc/gogc:percent"},
}

func readMetrics(keys ...string) map[string]uint64 {
	samples := make([]metrics.Sample, 0, len(memMetrics))
	var names []string
	for _, m := range memMetrics {
		for _, k := range keys {
			if k == m[0] {
				samples = append(samples, metrics.Sample{Name: m[1]})
				names = append(names, m[0])
			}
		}
	}
	metrics.Read(samples)
	out := map[string]uint64{}
	for i, s := range samples {
		if s.Value.Kind() == metrics.KindUint64 {
			out[names[i]] = s.Value.Uint64()
		}
	}
	return out
}

func allMetrics() map[string]uint64 {
	keys := make([]string, len(memMetrics))
	for i, m := range memMetrics {
		keys[i] = m[0]
	}
	return readMetrics(keys...)
}

// memStats: the Go side of the tunnel's memory, for Технический режим and the watchdog;
// "checking" is how many first observatory checks still run (the xray-mem patch), "gcCPU"
// the GC's CPU in cores over the last setMemoryLimit interval.
func memStats() string {
	out := map[string]any{}
	for k, v := range allMetrics() {
		out[k] = v
	}
	out["checking"] = burst.TitanChecking()
	gcWatch.Lock()
	out["gcCPU"] = gcWatch.cores
	gcWatch.Unlock()
	return answer(out, nil)
}

// freeMemory: a GC, and the free pages back to the OS now (jetsam counts what Go keeps).
func freeMemory() string {
	debug.FreeOSMemory()
	return answer(map[string]any{}, nil)
}

// The Go memory limit in the tunnel. libXray sets GOGC 10, a 30 MiB soft limit and
// FreeOSMemory every second at its first runXray (memory/memory_ios.go: for a 50 MB
// extension); GOGC stays (lower doubles the GC's work for under 1 MiB, a GC comes every second
// anyway). The limit follows the extension's own limit instead: what remains of it after what
// isn't Go's and a margin. It only makes the GC work harder near it; what is live stays.
const (
	// What isn't Go's at the least: the Go binary's rebased data (~6 MiB), Swift,
	// NetworkExtension and its frameworks, malloc (11-15 MiB in all).
	minNonGo = 12 << 20
	// Overshoot past a soft limit and a restart's start: Go at its limit leaves the footprint
	// 3 MiB below the tunnel's watchdog (5 MiB under the extension's limit).
	limitMargin = 8 << 20
	// The watchdog's threshold under the extension's limit (PacketTunnelProvider): the
	// highest the limit goes, Go at it puts the footprint there and the watchdog decides.
	watchdogMargin = 5 << 20
	// The lowest limit in any case (a 30 MB extension, much outside Go).
	minGoLimit = 24 << 20
	// Room to allocate between GCs over the live heap: GOGC's share of it, this at least.
	// Bench (20 MiB live): the GC calm with a limit at live + the rest + 2 MiB, 0.4 cores 1
	// MiB under it; 3 MiB for what moves between two readings.
	minRoom = 3 << 20
	// The GC above this many cores (averaged over 5 s at least) is working against the
	// limit, not the traffic: a busy tunnel at GOGC 10 takes a few percent of a core. Bench
	// (20 MiB live, GOGC 10, FreeOSMemory every second, 6 procs): 30 MiB 0.03 cores of GC,
	// 29 MiB 0.4, 24-28 MiB 0.95 (more than a core of CPU in all) without freeing anything.
	busyCores = 0.25
	// How long the limit stays raised after that.
	busyFor = time.Minute
)

// The GC's CPU between setMemoryLimit calls (runtime/metrics, updated at each GC: one at
// least every second with libXray's FreeOSMemory).
var gcWatch struct {
	sync.Mutex
	gc, total float64
	at        time.Time
	cores     float64
	busyUntil time.Time
}

// gcBusy reads the GC's CPU since the last reading (5 s ago at least) and says if it has
// been over busyCores within busyFor.
func gcBusy(now time.Time) (bool, float64) {
	s := []metrics.Sample{{Name: "/cpu/classes/gc/total:cpu-seconds"}, {Name: "/cpu/classes/total:cpu-seconds"}}
	metrics.Read(s)
	if s[0].Value.Kind() != metrics.KindFloat64 || s[1].Value.Kind() != metrics.KindFloat64 {
		return false, 0
	}
	gc, total := s[0].Value.Float64(), s[1].Value.Float64()
	w := &gcWatch
	w.Lock()
	defer w.Unlock()
	if w.at.IsZero() || now.Sub(w.at) >= 5*time.Second {
		if !w.at.IsZero() {
			w.cores = 0
			if d := total - w.total; d > 0 {
				// total is GOMAXPROCS × the time: the share of it times GOMAXPROCS is cores.
				w.cores = (gc - w.gc) / d * float64(runtime.GOMAXPROCS(0))
			}
			if w.cores > busyCores {
				w.busyUntil = now.Add(busyFor)
			}
		}
		w.gc, w.total, w.at = gc, total, now
	}
	return now.Before(w.busyUntil), w.cores
}

// goNeed is what Go can't go under however hard the GC works: the live heap and what it
// holds besides objects and free pages (unused span space, stacks, metadata, the rest), and
// room to allocate between GCs (GOGC's share of the live heap, 3 MiB at least). A soft limit
// below it has the GC run nonstop for nothing.
func goNeed(m map[string]uint64) int64 {
	live := int64(m["liveHeap"])
	held := int64(m["total"]) - int64(m["released"]) - int64(m["free"]) - int64(m["objects"])
	gogc := int64(m["gogc"])
	if gogc <= 0 {
		gogc = 100
	}
	return live + max(held, 0) + max(live*gogc/100, minRoom)
}

// goLimitFor: Go's soft limit for the extension's limit and footprint now and Go's metrics.
// limit − max(footprint − Go's, 12 MiB) − 8 MiB ("fit"), raised to what Go needs ("need"),
// at most to the watchdog's threshold (limit − what isn't Go's − 5 MiB), and to that when
// the GC has been busy ("gc"); 24 MiB at least ("min").
func goLimitFor(limit, footprint int64, m map[string]uint64, busy bool) (goLimit, nonGo, need int64, why string) {
	goRetained := int64(m["total"] - m["released"]) // what the soft limit counts
	nonGo = max(footprint-goRetained, minNonGo)
	need = goNeed(m)
	fit := limit - nonGo - limitMargin
	top := limit - nonGo - watchdogMargin
	goLimit, why = fit, "fit"
	if busy {
		goLimit, why = top, "gc"
	} else if need > fit {
		goLimit, why = min(need, top), "need"
	}
	if goLimit < minGoLimit {
		goLimit, why = minGoLimit, "min"
	}
	return goLimit, nonGo, need, why
}

// setMemoryLimit sets the soft limit for the extension's limit and footprint now (bytes),
// goLimitFor's. On a 50 MB extension: 30 MiB (libXray's) while the rest is 12 MiB or less and
// Go needs no more, more as Go needs it (up to the watchdog's line), less as the rest grows.
// After runXray: the first one sets libXray's.
func setMemoryLimit(limit, footprint int64) string {
	if limit <= 0 || footprint <= 0 {
		return answer(nil, errors.New("setMemoryLimit: no limit or footprint"))
	}
	busy, cores := gcBusy(time.Now())
	goLimit, nonGo, need, why := goLimitFor(limit, footprint, allMetrics(), busy)
	debug.SetMemoryLimit(goLimit)
	return answer(map[string]any{"goLimit": goLimit, "nonGo": nonGo, "need": need, "why": why, "gcCPU": cores}, nil)
}

// closeTransports: the transports' pooled connections closed (the xray-mem patch,
// internet.TitanCloseTransports). Only between stopXray and runXray.
func closeTransports() string {
	return answer(map[string]any{"closed": internet.TitanCloseTransports()}, nil)
}
