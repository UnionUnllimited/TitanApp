package memory

import (
	"os"
	"strings"
	"sync/atomic"
	"testing"
	"time"
)

// libXray's loop gives Go's free memory back once a titanFreeEvery (the xray-mem patch), not
// every second, the first time one period after the start, and the iOS file (not built here)
// uses it.
func TestTitanFree(t *testing.T) {
	if titanFreeEvery < 30*time.Second || titanFreeEvery > 2*time.Minute {
		t.Fatalf("titanFreeEvery %v: want 30 s to 2 min", titanFreeEvery)
	}
	src, err := os.ReadFile("memory_ios.go")
	if err != nil {
		t.Fatal(err)
	}
	for _, want := range []string{"forceFree(titanFreeEvery)", "go titanFreeLoop(interval, debug.FreeOSMemory, nil)"} {
		if !strings.Contains(string(src), want) {
			t.Fatalf("memory_ios.go doesn't have %q (the patch not applied)", want)
		}
	}
	if strings.Contains(string(src), "time.Sleep(interval)") {
		t.Fatal("memory_ios.go still has upstream's loop")
	}

	const every = 50 * time.Millisecond
	var n atomic.Int32
	stop, done := make(chan struct{}), make(chan struct{})
	start := time.Now()
	go func() { titanFreeLoop(every, func() { n.Add(1) }, stop); close(done) }()
	time.Sleep(every / 2)
	if got := n.Load(); got != 0 {
		t.Fatalf("%d frees in the first half period", got)
	}
	time.Sleep(time.Until(start.Add(10*every + every/2)))
	got := n.Load()
	close(stop)
	<-done
	if got < 8 || got > 10 {
		t.Fatalf("%d frees in 10.5 periods, want 10 (8-10)", got)
	}
	time.Sleep(2 * every)
	if n.Load() != got {
		t.Fatal("frees after the stop")
	}
}
