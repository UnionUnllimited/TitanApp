package quic

// Titan patch, iOS memory (scripts/ios-patches/xray-mem): copied into quic-go as titan_mem.go
// by quic_go.py, which hooks these in.

import (
	"cmp"
	"slices"
	"time"

	"github.com/apernet/quic-go/internal/monotime"
	"github.com/apernet/quic-go/internal/protocol"
	"github.com/apernet/quic-go/internal/wire"
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
// them closed (connIdle: 300 s); two new and three old ones left the others 1 MiB. A stream read
// slower than it comes (a player topping up its full buffer, an app reading at the playback
// rate) holds its window the same way until it has read it: two new stalled streams and one new
// slow one filled the 16 MiB, and every other stream got only what the slow one's reads gave
// back (tens of KiB/s, until it had read 2 MiB: 30 s at 64 KiB/s). No window is big enough for
// that and small enough for the tunnel's memory.
//
// So a stream counts once more than titanMinUpdate waited on it for titanStallAfter
// (titanSince: when it was last below), read now and then or not at all; one read at line rate
// drains it every few reads and never counts. Once the streams that count hold more than half
// the connection's window, those nothing was read from for titanStallAfter are cancelled
// (STOP_SENDING; what waits on it is dropped at once and its part of the window given back), the
// one holding the most first, until all that count hold half at most; those still read only
// while all hold more than three quarters, the same way. The others get half (8 MiB of 16)
// within a few seconds, a quarter next to streams that are read, and what stalled streams pin
// per connection for as long as they stall (up to connIdle) is half the window, not three
// quarters: two new stalled streams held 12 MiB, +15.6 MiB of heap, and one or two such
// connections used all of Go's headroom. Streams read slowly hold their part only until they
// have read it, and resetting a player that plays costs more: two new ones read at 64 KiB/s
// (11 MiB) are left alone, of three one is cancelled. The app of a cancelled stream finds that
// connection reset once it reads again (a player asks again); the alternative was every
// connection through that server standing for minutes, or the watchdog's memory restart cutting
// all of them. One new and one old stalled stream (7 MiB) are left alone, of two new ones one
// is cancelled.
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

// titanStallShare: what the streams that count may hold of the connection's window: half its
// ceiling, three quarters with those still read (a window still smaller, quic-go's default
// growing from 512 KiB, is as before).
func (c *connectionFlowController) titanStallShare() protocol.ByteCount {
	return c.maxReceiveWindowSize / 2
}

func (c *connectionFlowController) titanReadShare() protocol.ByteCount {
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

// titanCheck cancels the streams that count, those nothing is read from and the most first,
// until they hold their share at most, and looks again soon while streams that may come to
// count hold data.
func (c *connectionFlowController) titanCheck() {
	c.mutex.Lock()
	c.titan.armed = false
	share, readShare := c.titanStallShare(), c.titanReadShare()
	streams := make([]*ReceiveStream, 0, len(c.titan.streams))
	for s := range c.titan.streams {
		streams = append(streams, s)
	}
	c.mutex.Unlock()

	type held struct {
		s       *ReceiveStream
		unread  protocol.ByteCount
		since   monotime.Time
		reading int // 1: read within titanStallAfter
	}
	now := monotime.Now()
	var stalled []held
	var total protocol.ByteCount
	pending := false
	for _, s := range streams {
		unread, since, read, gone := s.titanHeld()
		switch {
		case gone:
			c.titanForget(s)
		case unread < titanMinUpdate:
		case now.Sub(since) < titanStallAfter:
			pending = true
		default:
			h := held{s: s, unread: unread, since: since}
			if now.Sub(read) < titanStallAfter {
				h.reading = 1
			}
			stalled = append(stalled, h)
			total += unread
		}
	}
	// Those nothing is read from first; the most first; of those holding as much, the one
	// stalled longest.
	slices.SortFunc(stalled, func(a, b held) int {
		return cmp.Or(cmp.Compare(a.reading, b.reading), cmp.Compare(b.unread, a.unread), cmp.Compare(a.since, b.since))
	})
	for _, h := range stalled {
		if total <= share || h.reading == 1 && total <= readShare {
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

// titanHeld: what waits unread on s, since when that stayed at titanMinUpdate or more, and when
// s was last read (0: never); gone once the connection is closed.
func (s *ReceiveStream) titanHeld() (unread protocol.ByteCount, since, read monotime.Time, gone bool) {
	s.mutex.Lock()
	defer s.mutex.Unlock()
	if s.closeForShutdownErr != nil {
		return 0, 0, 0, true
	}
	if s.cancelledLocally || s.cancelledRemotely {
		return 0, 0, 0, false
	}
	fc := s.flowController
	fc.mutex.Lock()
	defer fc.mutex.Unlock()
	return fc.highestReceived - fc.bytesRead, fc.titanSince, fc.titanRead, false
}

// titanArrived / titanReadNow: the stream's flow controller, with its mutex held. What waits on the
// stream is below titanMinUpdate when data arrives or after a read: titanSince starts again.
func (c *streamFlowController) titanArrived(now monotime.Time) {
	if c.highestReceived-c.bytesRead < titanMinUpdate {
		c.titanSince = now
	}
}

func (c *streamFlowController) titanReadNow() {
	now := monotime.Now()
	c.titanRead = now
	c.titanArrived(now)
}

// quic-go keeps each STREAM frame of 128 bytes or more in its packet buffer, 1452 bytes (1536 in
// Go's heap), with its StreamFrame, until it is read; the stall check counts the frame's bytes.
// Full packets (bulk data: 1.2-1.4 KB of data each) take about 1.2x their data that way, small
// ones far more: a stalled stream fed small writes (a websocket ticker, chat, SSE, push: each
// write its own packet) held 6 MiB unread in +50 MiB of heap at 200 bytes (8x), +74 at 130.
// So a frame using less than three quarters of its buffer is copied out at once and its buffer
// given back, and one that continues the stream's last copied one while it still waits is
// appended to it, up to titanChunk: many tiny frames don't each cost a queue entry either.
// Bulk data is queued as before, without a copy.
const (
	titanCopyBelow = protocol.MaxPacketBufferSize * 3 / 4
	titanChunk     = 4 << 10
)

// titanPush queues frame's data (handleStreamFrameImpl, with s.mutex held).
func (s *ReceiveStream) titanPush(frame *wire.StreamFrame) error {
	n := len(frame.Data)
	if n == 0 || n >= titanCopyBelow {
		return s.frameQueue.Push(frame.Data, frame.Offset, frame.PutBack)
	}
	q, offset, data := s.frameQueue, frame.Offset, frame.Data
	// Queued as it is first, so the sorter places it (duplicates, overlaps); replaced below.
	if err := q.Push(data, offset, nil); err != nil {
		return err
	}
	if e, ok := q.queue[offset]; !ok || len(e.Data) != n || &e.Data[0] != &data[0] {
		// A duplicate, or cut against data already queued (a retransmission): what of it was
		// queued, if anything, keeps its packet buffer, which is left to the GC.
		return nil
	}
	if p, ok := q.queue[s.titanTail]; ok && p.DoneCb == nil && s.titanTail+protocol.ByteCount(len(p.Data)) == offset &&
		len(p.Data)+n <= titanChunk {
		// Every entry without a callback holds its own buffer (this one's, the sorter's copies).
		if cap(p.Data)-len(p.Data) < n {
			grown := make([]byte, len(p.Data), titanChunk)
			copy(grown, p.Data)
			p.Data = grown
		}
		p.Data = append(p.Data, data...)
		q.queue[s.titanTail] = p
		delete(q.queue, offset)
	} else {
		q.queue[offset] = frameSorterEntry{Data: append([]byte(nil), data...)}
		s.titanTail = offset
	}
	frame.PutBack()
	return nil
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
