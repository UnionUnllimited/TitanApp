package burst

import (
	"bufio"
	"context"
	"fmt"
	gonet "net"
	"net/http"
	"sync/atomic"
	"testing"
	"time"

	"github.com/xtls/xray-core/common/net"
	"github.com/xtls/xray-core/features/routing"
	"github.com/xtls/xray-core/transport/internet/tagged"
)

// The observatory's probes run a few at a time (xray-mem patch), and every subject still gets
// its result; a stop ends the ones still waiting.
func TestTitanPacing(t *testing.T) {
	var waiting, peak, dials atomic.Int32
	tagged.Dialer = func(ctx context.Context, _ routing.Dispatcher, dest net.Destination, tag string) (net.Conn, error) {
		dials.Add(1)
		n := waiting.Add(1)
		for p := peak.Load(); n > p && !peak.CompareAndSwap(p, n); p = peak.Load() {
		}
		client, server := gonet.Pipe()
		go func() {
			defer server.Close()
			if _, err := http.ReadRequest(bufio.NewReader(server)); err != nil {
				waiting.Add(-1)
				return
			}
			time.Sleep(20 * time.Millisecond)
			// Counted out before the answer: its probe holds its slot until it has it.
			waiting.Add(-1)
			server.Write([]byte("HTTP/1.1 204 No Content\r\n\r\n"))
		}()
		return client, nil
	}
	defer func() { tagged.Dialer = nil }()

	h := NewHealthPing(context.Background(), nil, &HealthPingConfig{
		Destination:   "http://probe.invalid/generate_204",
		Interval:      int64(time.Minute),
		SamplingCount: 1,
		Timeout:       int64(5 * time.Second),
	})
	tags := make([]string, 30)
	for i := range tags {
		tags[i] = fmt.Sprintf("bal-%d", i)
	}
	h.Check(tags)
	if p := peak.Load(); p != titanProbesAtOnce {
		t.Fatalf("%d probes at once, want %d", p, titanProbesAtOnce)
	}
	h.access.Lock()
	got := len(h.Results)
	h.access.Unlock()
	if got != len(tags) {
		t.Fatalf("%d results for %d subjects", got, len(tags))
	}

	// Stopped while most wait: they don't probe.
	h2 := NewHealthPing(context.Background(), nil, &HealthPingConfig{
		Destination: "http://probe.invalid/generate_204", Interval: int64(time.Minute), SamplingCount: 1, Timeout: int64(5 * time.Second),
	})
	dials.Store(0)
	done := make(chan struct{})
	go func() { h2.doCheck(h2.ctx, tags, 0, 1); close(done) }()
	time.Sleep(5 * time.Millisecond)
	h2.cancelCtx()
	<-done
	time.Sleep(200 * time.Millisecond)
	if n := dials.Load(); n > 2*titanProbesAtOnce {
		t.Fatalf("%d probes after the stop", n)
	}
}
