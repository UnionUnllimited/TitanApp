package titan

import (
	"runtime/debug"
	"runtime/metrics"
)

// What memStats reads (runtime/metrics: no stop-the-world), by its key in the answer.
var memMetrics = [][2]string{
	{"total", "/memory/classes/total:bytes"},            // all the Go runtime has mapped
	{"released", "/memory/classes/heap/released:bytes"}, // of it, given back to the OS
	{"liveHeap", "/gc/heap/live:bytes"},                 // heap live after the last GC
	{"stacks", "/memory/classes/heap/stacks:bytes"},
	{"goroutines", "/sched/goroutines:goroutines"},
}

// memStats: the Go side of the tunnel's memory, for Технический режим.
func memStats() string {
	samples := make([]metrics.Sample, len(memMetrics))
	for i, m := range memMetrics {
		samples[i].Name = m[1]
	}
	metrics.Read(samples)
	out := map[string]any{}
	for i, s := range samples {
		if s.Value.Kind() == metrics.KindUint64 {
			out[memMetrics[i][0]] = s.Value.Uint64()
		}
	}
	return answer(out, nil)
}

// freeMemory: a GC, and the free pages back to the OS now (jetsam counts what Go keeps).
func freeMemory() string {
	debug.FreeOSMemory()
	return answer(map[string]any{}, nil)
}
