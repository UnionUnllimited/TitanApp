package quic

// Titan patch, iOS memory (scripts/ios-patches/xray-mem): copied into quic-go as titan_mem.go
// by quic_go.py, which hooks these in.

import (
	"cmp"
	"slices"
	"time"

	"github.com/apernet/quic-go/internal/monotime"
	"github.com/apernet/quic-go/internal/protocol"
)

// titanGrow: at its first update the connection's window goes to the ceiling if the handshake's
// is half of it or more (Chrome's 15 MiB, a 16 MiB ceiling) and that is allowed. Auto-tuning
// would double it to the ceiling at once, but it only runs once half the window is read within
// ~2 RTT, which with 1 MiB stream windows hardly ever happens: the connection kept Chrome's
// 15 MiB. A smaller one (quic-go's default 512 KiB) grows from where it is, as before.
func (c *receiveFlowController) titanGrow() {
	if !c.titanConnection || c.titanGrown {
		return
	}
	c.titanGrown = true
	if c.receiveWindowSize >= c.maxReceiveWindowSize || c.receiveWindowSize < c.maxReceiveWindowSize/2 {
		return
	}
	if c.allowWindowIncrease == nil || c.allowWindowIncrease(c.maxReceiveWindowSize-c.receiveWindowSize) {
		c.receiveWindowSize = c.maxReceiveWindowSize
	}
}

// titanShort: the connection's window goes up once an update would at least double what the
// peer has left of it, by titanMinUpdate at the least (Go's HTTP/2 rule). With a quarter of the
// window read alone, once stalled streams held three quarters of it no update came again, and
// every stream stood.
func (c *receiveFlowController) titanShort() bool {
	if !c.titanConnection {
		return false
	}
	update := c.bytesRead + c.receiveWindowSize - c.receiveWindow
	return update >= titanMinUpdate && update >= c.receiveWindow-c.highestReceived
}

const titanMinUpdate protocol.ByteCount = 64 << 10

// What waits on a stream nobody reads (a paused or full-buffered player, a suspended app) is
// never read, so its part of the connection's window never comes back: the connection's other
// streams get only what the stalled ones leave. With Chrome parroting each new stream opens with
// Chrome's 6 MiB, which can't be taken back, so three new streams that stall early fill all of
// 16 MiB and every stream of the connection (all TCP through that server) stood until one of
// them closed (connIdle: 300 s); two new and three old ones left the others 1 MiB. No window is
// big enough for that and small enough for the tunnel's memory.
//
// So once the streams nothing was read from for titanStallAfter hold more than three quarters
// of the connection's window, the one holding the most is cancelled (STOP_SENDING; what waits
// on it is dropped at once and its part of the window given back), then the next, until they
// hold three quarters at most: the others always get a quarter (4 MiB of 16) within a few
// seconds. Its app finds that connection reset once it reads again (a player asks again); the
// alternative was every connection through that server standing for minutes. Two new stalled
// streams (12 MiB), or one new and six old ones, are left alone.
const (
	titanStallAfter = 2 * time.Second
	// H3_REQUEST_CANCELLED for HTTP/3 (XHTTP); Hysteria doesn't look at the code.
	titanStallCode StreamErrorCode = 0x10c
)

// titanStalls: a connection's receive streams, under its flow controller's mutex.
type titanStalls struct {
	streams map[*ReceiveStream]struct{}
	armed   bool
}

// titanStallShare: what the stalled streams may hold of the connection's window: three quarters
// of its ceiling (a window still smaller, quic-go's default growing from 512 KiB, is as before).
func (c *connectionFlowController) titanStallShare() protocol.ByteCount {
	return c.maxReceiveWindowSize - c.maxReceiveWindowSize/4
}

// titanArm looks at the streams after d once the connection holds more unread than the stalled
// ones may. With c.mutex held.
func (c *connectionFlowController) titanArm(d time.Duration) {
	if c.titan.armed || len(c.titan.streams) == 0 || c.highestReceived-c.bytesRead <= c.titanStallShare() {
		return
	}
	c.titan.armed = true
	time.AfterFunc(d, c.titanCheck)
}

func (c *connectionFlowController) titanForget(s *ReceiveStream) {
	c.mutex.Lock()
	delete(c.titan.streams, s)
	c.mutex.Unlock()
}

// titanCheck cancels the stalled streams holding the most until the stalled ones hold their
// share at most, and looks again soon while streams that may be stalling hold data.
func (c *connectionFlowController) titanCheck() {
	c.mutex.Lock()
	c.titan.armed = false
	share := c.titanStallShare()
	streams := make([]*ReceiveStream, 0, len(c.titan.streams))
	for s := range c.titan.streams {
		streams = append(streams, s)
	}
	c.mutex.Unlock()

	type held struct {
		s      *ReceiveStream
		unread protocol.ByteCount
		since  monotime.Time
	}
	now := monotime.Now()
	var stalled []held
	var total protocol.ByteCount
	pending := false
	for _, s := range streams {
		unread, since, gone := s.titanHeld()
		switch {
		case gone:
			c.titanForget(s)
		case unread < titanMinUpdate:
		case now.Sub(since) < titanStallAfter:
			pending = true
		default:
			stalled = append(stalled, held{s, unread, since})
			total += unread
		}
	}
	// The most first; of those holding as much, the one stalled longest.
	slices.SortFunc(stalled, func(a, b held) int {
		return cmp.Or(cmp.Compare(b.unread, a.unread), cmp.Compare(a.since, b.since))
	})
	for _, h := range stalled {
		if total <= share {
			break
		}
		h.s.titanCancel()
		total -= h.unread
	}
	if pending {
		c.mutex.Lock()
		c.titanArm(titanStallAfter / 4)
		c.mutex.Unlock()
	}
}

// titanAdd lists s with its connection's streams, titanDone takes it off.
func (s *ReceiveStream) titanAdd() {
	if s.flowController == nil || s.flowController.connection == nil {
		return
	}
	c := s.flowController.connection
	c.mutex.Lock()
	if c.titan.streams == nil {
		c.titan.streams = make(map[*ReceiveStream]struct{})
	}
	c.titan.streams[s] = struct{}{}
	c.mutex.Unlock()
}

// titanDone: s is done receiving (isNewlyCompleted, with s.mutex held).
func (s *ReceiveStream) titanDone() {
	if s.flowController != nil && s.flowController.connection != nil {
		s.flowController.connection.titanForget(s)
	}
}

// titanHeld: what waits unread on s, and since when nothing of it was read; gone once the
// connection is closed.
func (s *ReceiveStream) titanHeld() (unread protocol.ByteCount, since monotime.Time, gone bool) {
	s.mutex.Lock()
	defer s.mutex.Unlock()
	if s.closeForShutdownErr != nil {
		return 0, 0, true
	}
	if s.cancelledLocally || s.cancelledRemotely {
		return 0, 0, false
	}
	fc := s.flowController
	fc.mutex.Lock()
	defer fc.mutex.Unlock()
	return fc.highestReceived - fc.bytesRead, fc.titanSince, false
}

// titanCancel is CancelRead that also drops what waits on s now, not once its reader comes back
// (minutes later, or never) and lets go of the stream, and gives its part of the connection's
// window back now, not once the peer's RESET_STREAM comes.
func (s *ReceiveStream) titanCancel() {
	s.mutex.Lock()
	queued := s.cancelReadImpl(titanStallCode)
	if s.cancelledLocally && s.closeForShutdownErr == nil {
		if s.currentFrameDone != nil {
			s.currentFrameDone()
		}
		s.currentFrame, s.currentFrameDone, s.readPosInFrame = nil, nil, 0
		for _, e := range s.frameQueue.queue {
			if e.DoneCb != nil {
				e.DoneCb()
			}
		}
		s.frameQueue = newFrameSorter()
		// What is still on its way is given back with the final size (FIN or RESET_STREAM).
		s.flowController.Abandon()
	}
	completed := s.isNewlyCompleted()
	s.mutex.Unlock()
	if queued {
		s.sender.onHasStreamControlFrame(s.streamID, s)
	}
	if completed {
		s.sender.onStreamCompleted(s.streamID)
	}
	s.sender.onHasConnectionData() // the MAX_DATA
}
