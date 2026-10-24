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
// connection waits unread in memory when the reader stalls (a player with a full buffer).
// With Chrome parroting every new stream still opens with Chrome's 6 MiB (the handshake's
// fingerprint, granted at once): a new stream that stalls may hold that much, and only what
// the connection has left goes to its other streams. 8 MiB leaves them 2 MiB with one such
// stream (2 MiB left them nothing: the video playing on the connection froze).
const (
	TitanQUICStreamWindow = 1 << 20
	TitanQUICConnWindow   = 8 << 20
)

// TitanQUICWindows caps c's receive windows (0, quic-go's default, is 6 / 15 MiB: capped too).
// With Chrome parroting quic-go still opens with Chrome's 6 / 15 MiB (the handshake's
// fingerprint); the patched quic-go keeps to the caps from then on.
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
