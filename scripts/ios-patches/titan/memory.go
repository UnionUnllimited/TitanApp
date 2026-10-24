package titan

import (
	"errors"
	"runtime/debug"
	"runtime/metrics"

	"github.com/xtls/xray-core/transport/internet"
)

// What memStats reads (runtime/metrics: no stop-the-world), by its key in the answer.
var memMetrics = [][2]string{
	{"total", "/memory/classes/total:bytes"},            // all the Go runtime has mapped
	{"released", "/memory/classes/heap/released:bytes"}, // of it, given back to the OS
	{"liveHeap", "/gc/heap/live:bytes"},                 // heap live after the last GC
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

// memStats: the Go side of the tunnel's memory, for Технический режим.
func memStats() string {
	keys := make([]string, len(memMetrics))
	for i, m := range memMetrics {
		keys[i] = m[0]
	}
	out := map[string]any{}
	for k, v := range readMetrics(keys...) {
		out[k] = v
	}
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
	// Below it a busy tunnel's live heap (15-25 MiB) had the GC running nonstop (up to half
	// the CPU, the GC's own cap) without freeing anything.
	minGoLimit = 24 << 20
)

// setMemoryLimit sets the soft limit for the extension's limit and footprint now (bytes):
// limit − max(footprint − Go's, 12 MiB) − 8 MiB, at least 24 MiB. On a 50 MB extension: 30
// MiB (libXray's) while the rest is 12 MiB or less, less as it grows. After runXray: the
// first one sets libXray's.
func setMemoryLimit(limit, footprint int64) string {
	if limit <= 0 || footprint <= 0 {
		return answer(nil, errors.New("setMemoryLimit: no limit or footprint"))
	}
	m := readMetrics("total", "released")
	goRetained := int64(m["total"] - m["released"]) // what the soft limit counts
	nonGo := max(footprint-goRetained, minNonGo)
	goLimit := max(limit-nonGo-limitMargin, minGoLimit)
	debug.SetMemoryLimit(goLimit)
	return answer(map[string]any{"goLimit": goLimit, "nonGo": nonGo}, nil)
}

// closeTransports: the transports' pooled connections closed (the xray-mem patch,
// internet.TitanCloseTransports). Only between stopXray and runXray.
func closeTransports() string {
	return answer(map[string]any{"closed": internet.TitanCloseTransports()}, nil)
}
