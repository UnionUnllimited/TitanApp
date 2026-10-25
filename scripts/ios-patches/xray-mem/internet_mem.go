package internet

// Titan patch, iOS memory (scripts/ios-patches/xray-mem): copied into transport/internet.

import (
	"context"
	"sync"
	"time"

	"github.com/apernet/quic-go"
)

// XHTTP, gRPC and Hysteria keep their connections in package-level pools keyed by the
// instance's own stream settings: a new instance never reuses an old one's, and nothing
// closed them, so each restart left a whole set alive (gRPC's for 30 min, Hysteria's with a
// keep-alive for good). Each registers its closer here.
var titanClosers struct {
	sync.Mutex
	f []func() int
}

// TitanRegisterCloser adds a pool's closer: it closes every pooled connection and empties
// the pool, returning how many it closed.
func TitanRegisterCloser(f func() int) {
	titanClosers.Lock()
	defer titanClosers.Unlock()
	titanClosers.f = append(titanClosers.f, f)
}

// A dial the stopped instance still had under way (a dead server: retries of 16 s each)
// may pool a connection under its stream settings after TitanCloseTransports: each pool
// closes those this much later (a variable for the tests).
var TitanSweepAfter = 90 * time.Second

// How long a pooled connection stays up with no stream on it: XHTTP's HTTP/2 connections
// (IdleConnTimeout, was net.ConnIdleTimeout: 300 s), gRPC's ClientConns (WithIdleTimeout, was
// grpc-go's 30 min) and Hysteria's QUIC connections (with no TCP stream and no UDP session; the
// panel's keep-alive under QUIC's idle timeout kept them for good, the cleaner only dropped dead
// ones). A connection with a stream on it is never closed (each pool's own idle rule: HTTP/2 and
// gRPC count their streams, Hysteria's cleaner its streams and sessions under the client's lock,
// which a new stream takes too); the next stream after the idle close dials again. XHTTP over
// HTTP/3 keeps its connections as before (quic-go's http3 counts a request until its headers
// only). A variable for the tests.
//
// The pools carry only the tunnel's flows: the observatory's probes (TitanProbe) get a connection
// of their own each, closed as the probe ends (XHTTP: a client of its own, gRPC: a ClientConn,
// Hysteria: a client; each with its handshake), on a subject the tunnel's flows use too. Every
// subject's connection was there for the observatory's next probe (interval × sampling: 5 per
// subject each round), though user traffic rides only the balancers' picks, and they stayed: with
// 60 outbounds (30 XHTTP, 10 gRPC, 10 Hysteria, 10 TCP REALITY) 21-28 HTTP/2, 10 gRPC and 10 QUIC
// connections, ~60 / 72 / 100 KiB of heap each (3.1 MiB in all, 3.7 MiB of Go with the
// goroutines' stacks), and the HTTP/2 ones went up to 59 for the minutes after every start
// (XMUX's default 3 per subject). In the pool with this minute, next to probes about a minute
// apart (1m × 5), a subject's probes were a random mix of warm ones and cold ones (a TCP + TLS /
// REALITY or QUIC + auth handshake: +2 RTT); leastLoad ranks by the deviation of each subject's
// last probes (the burst observatory's HealthPingRTTS), the mix added ~0.46 × 2 RTT that the path
// didn't have, and leastLoad left gRPC and Hysteria for TCP REALITY. Now each probe is cold, as
// TCP REALITY's always were (it pools nothing), the pick's too: a pick's probes warm on its
// connection and the others' cold switched its samples from warm to cold as it stopped being the
// pick and back, and in the review's model leastLoad changed its pick 1.7-1.8 times as often
// (docs/HANDOFF.md, TestTitanProbeDeviation).
var TitanIdleAfter = 60 * time.Second

type titanProbeKey struct{}

// TitanProbeContext marks ctx as the observatory's probes' (the burst observatory's ping client:
// burst_mem.go). The mark goes with each probe's flow through the outbound to its transport's
// dial, and on through a dialerProxy hop to the hop's transport (XHTTP's and Hysteria's dials
// keep the flow's values; gRPC's copies it into grpc-go's dial context): the hop gives the probe
// a connection of its own too. A flow through Xray's mux (mux.cool) loses it and goes in the
// pool, as the tunnel's do.
func TitanProbeContext(ctx context.Context) context.Context {
	return context.WithValue(ctx, titanProbeKey{}, true)
}

// TitanProbe says ctx is the context of an observatory probe's flow.
func TitanProbe(ctx context.Context) bool {
	p, _ := ctx.Value(titanProbeKey{}).(bool)
	return p
}

// TitanCloseTransports closes the pooled connections of every transport, without waiting
// for them. Only once no Xray instance runs in the process (the tunnel, between stopXray and
// the next runXray): the pools are the process's, a running instance would lose its
// connections too.
func TitanCloseTransports() int {
	titanClosers.Lock()
	f := append([]func() int(nil), titanClosers.f...)
	titanClosers.Unlock()
	n := 0
	for _, c := range f {
		n += c()
	}
	return n
}

// QUIC receive windows (Hysteria, XHTTP over HTTP/3): at most this much of a stream or a
// connection waits unread in memory when readers stall (a paused or full-buffered player, a
// suspended app), once the windows the handshake gave are used up. With Chrome parroting
// (Xray's default) the handshake gives Chrome's (the fingerprint) and they can't be taken
// back: each new stream may hold 6 MiB until about 5 MiB of it is read, a fresh connection
// 15 MiB (test: 3 streams never read on a fresh connection hold 15 MiB; with
// DisableChromeParrot 1 MiB each). Only quicParams' disableChromeParrot bounds that from the
// handshake on, and it changes the fingerprint: not done here.
//
// What stalled streams hold of the connection's window, its other streams don't get, and a new
// one holds Chrome's 6 MiB whatever the window. With 8 MiB one new and one old (7 MiB) or two
// new ones froze the whole connection (all TCP through that server) until one of them closed
// (connIdle: 300 s); with 16 MiB three new ones, two new and four old, one new and ten old, or
// two new and one read slower than it comes (a player topping up its full buffer) still did. No
// window is big enough for that and small enough for the tunnel. The patched quic-go
// (quic_go.py): the window is also updated when the peer runs short, not only once a quarter of
// it was read; the connection's is at this cap from its first update (it kept Chrome's 15 MiB:
// auto-tuning hardly ever ran); a stream counts as stalled once more than 64 KiB waited on it for
// 2 s, read now and then or not at all; and once those hold more than half the window (8 MiB),
// the ones nothing is read from are cancelled, the largest first, until all hold half at most;
// those still read only past three quarters (its app finds that connection reset when it reads
// again). The others get 8 MiB at least within a few seconds, 4 next to streams still read. One
// new and one old stalled stream (7 MiB) are left alone, of two new ones one is cancelled.
//
// The price is memory: what stalled streams pin per connection, for as long as they stall, is
// half the window after the check, 8 MiB (any mix up to 16 for the ~2 s before it; with streams
// still read up to 12, until those have read their part). Half, not three quarters: at 12 MiB
// two new stalled streams were left alone with +15.6 MiB of heap, all of the 11-17 MiB Go has
// over what is live after the first check (docs/HANDOFF.md), and with two such connections (the
// YouTube and the default balancer pick different servers, each with its own connection) only
// the watchdog's memory restart was left. Now one of the two is cancelled: 6 MiB, +7.5 MiB of
// heap (two connections +14.9 MiB, not +31.5). A frame takes 1.2x its data in heap as a full
// packet (each in its 1452-byte buffer) and 1.04x as a small one (a ticker's 200 bytes took 8x,
// 6 MiB unread +50 MiB, before quic-go copied them out). Chosen over a smaller window that
// freezes; a fresh connection held 15 MiB before anyway. For HTTP/2 (splithttp) it differs: a
// stream holds 1 MiB at most whatever the connection's window.
const (
	TitanQUICStreamWindow = 1 << 20
	TitanQUICConnWindow   = 16 << 20
)

// TitanQUICWindows caps c's receive windows (0, quic-go's default, is 6 / 15 MiB: capped too).
// With Chrome parroting quic-go still opens with Chrome's 6 / 15 MiB (the handshake's
// fingerprint); the patched quic-go keeps to the caps once those are used up.
func TitanQUICWindows(c *quic.Config) {
	if c.MaxStreamReceiveWindow == 0 || c.MaxStreamReceiveWindow > TitanQUICStreamWindow {
		c.MaxStreamReceiveWindow = TitanQUICStreamWindow
	}
	c.InitialStreamReceiveWindow = min(c.InitialStreamReceiveWindow, c.MaxStreamReceiveWindow)
	if c.MaxConnectionReceiveWindow == 0 || c.MaxConnectionReceiveWindow > TitanQUICConnWindow {
		c.MaxConnectionReceiveWindow = TitanQUICConnWindow
	}
	c.InitialConnectionReceiveWindow = min(c.InitialConnectionReceiveWindow, c.MaxConnectionReceiveWindow)
}
