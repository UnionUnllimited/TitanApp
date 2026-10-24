package internet

// Titan patch, iOS memory (scripts/ios-patches/xray-mem): copied into transport/internet.

import (
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
// (connIdle: 300 s); with 16 MiB three new ones, two new and four old, or one new and ten old
// still did. No window is big enough for that and small enough for the tunnel. The patched
// quic-go (quic_go.py): the window is also updated when the peer runs short, not only once a
// quarter of it was read; the connection's is at this cap from its first update (it kept
// Chrome's 15 MiB: auto-tuning hardly ever ran); and once streams nothing was read from for 2 s
// hold more than three quarters of it (12 MiB), the one holding the most is cancelled (its app
// finds that connection reset when it reads again), so the others get 4 MiB at least within a
// few seconds. Two new stalled streams (12 MiB), or one new and one old (7), are left alone.
//
// The price is memory. At 8 MiB the connection's window capped new stalled streams too (two
// held 8 MiB, and froze it); at 16 two hold 12 MiB for as long as they stall, and any mix fills
// up to 16 MiB for the ~2 s before a cancel, 12 at most after it. Stored frames take 1.05-1.3x
// their size in heap (each in a 1452-byte buffer: two new stalled streams, 12 MiB, took 15.5 MiB;
// three, 16 MiB, 20.9, and 15.5 once one was cancelled). Next to the 11-17 MiB Go has over what
// is live after the first check (docs/HANDOFF.md), 12 MiB on one connection is at the edge and
// 16 past it: then the watchdog's memory restart (connections cut for seconds) catches it.
// Chosen over a smaller window that freezes; a fresh connection held 15 MiB before anyway. For
// HTTP/2 (splithttp) it differs: a stream holds 1 MiB at most whatever the connection's window.
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
