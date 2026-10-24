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
// What stalled streams hold of the connection's window, its other streams don't get. 16 MiB:
// two new streams that stall (12 MiB) leave the others 4, one new and one old (7 MiB) 9. With
// 8 MiB either froze the whole connection (all TCP through that server) until one of them
// closed: the two new ones take all of it, and quic-go sent MAX_DATA only once a quarter of
// the window was read, which stalled streams holding over 6 MiB never let happen. quic_go.py
// updates the window when the peer runs short too, so only stalled streams filling all of it
// stop the others; both are needed (16 MiB alone still froze with two new ones: 12 MiB, three
// quarters). The price: a connection may hold 16 MiB unread, not 8; only stalled streams fill
// it (each holds what it holds whatever the window), and a fresh one held 15 already.
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
