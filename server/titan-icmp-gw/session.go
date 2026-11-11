// The session layer: it turns a stream of ICMP echo requests into per-session UDP
// flows to the node's inner inbound, and turns the inbound's replies back into ICMP
// echo replies. It knows nothing about raw sockets — all ICMP I/O goes through the
// Transport interface, so the whole thing is driven by an in-memory fake in tests.
//
// Reply-slot (credit) logic, correction 3. Downstream traffic exists only as replies
// to requests: every echo reply the daemon sends must answer some echo request the
// client sent (hans / ptunnel style). So the client keeps several requests
// outstanding ("polls") and the daemon treats each incoming request as one credit —
// a slot it may spend to push one downstream datagram back. Per session the daemon
// keeps two bounded FIFOs:
//
//   - slots: requests received for which no downstream datagram was ready yet. Each
//     remembers the request's peer / identifier / ICMP sequence so a later reply can
//     mirror them (correction 2: the client kernel and any NAT on the path match the
//     reply to the socket by those fields, so we must echo them back unchanged).
//   - pending: downstream datagrams read from the inner inbound for which no slot was
//     available yet.
//
// At most one of the two is ever non-empty: a new datagram spends a waiting slot at
// once, a new slot spends a waiting datagram at once. A slot the client left unspent
// for slotTTL is returned as an empty echo reply, which keeps carrier NAT mappings
// warm and lets the client's adaptive poller account for it. Both FIFOs are bounded;
// on overflow the oldest is dropped, because the inner protocol recovers from loss
// and must never be allowed to stall this daemon.
package main

import (
	"log"
	"net"
	"net/netip"
	"sync"
	"time"
)

// Datagram is one ICMP echo message crossing the Transport. For a received request it
// is what arrived; for a reply the caller fills Peer/ID/ICMPSeq from the request being
// answered and Body with the bytes to send.
type Datagram struct {
	Peer    netip.Addr // source of the request / destination of the reply
	ID      uint16     // ICMP identifier, mirrored from request to reply
	ICMPSeq uint16     // ICMP sequence number, mirrored from request to reply
	Body    []byte     // ICMP echo payload (shim header + inner, or a raw ping body)
}

// Transport moves ICMP echo messages. The real implementation (icmp.go) uses a raw
// socket; tests use an in-memory fake.
type Transport interface {
	// Recv returns the next echo request, or an error when the transport is closed.
	Recv() (Datagram, error)
	// SendReply sends an ICMP echo reply. d.Peer/ID/ICMPSeq address it; d.Body is the
	// payload.
	SendReply(d Datagram) error
	// Close stops the transport; a blocked Recv then returns an error.
	Close() error
}

// Config holds the tunable limits. Zero values are replaced by defaults in New.
type Config struct {
	Inner        string        // inner inbound address, e.g. "127.0.0.1:51820"
	SlotTTL      time.Duration // how long an unspent reply slot is held before an empty reply
	IdleTimeout  time.Duration // tear a session down after this long with no traffic either way
	MaxDatagram  int           // largest inner datagram accepted (read buffer)
	SlotQueue    int           // max outstanding reply slots per session
	DownQueue    int           // max buffered downstream datagrams per session
	ReqQueue     int           // dispatch channel depth per session
	JanitorEvery time.Duration // how often idle sessions are reaped
}

// DefaultSlotTTL is how long a reply slot is held for downstream data before it goes back
// empty. Long, so an idle client's one poll costs one request and one reply per this long
// and the phone's cellular radio can go idle in between; well under carrier ICMP NAT
// timeouts (30-60 s), so the reply still finds its mapping; and under the client's loss
// timeout (lossMs, 15 s), so a held poll isn't taken for lost.
const DefaultSlotTTL = 10 * time.Second

func (c *Config) withDefaults() {
	if c.SlotTTL <= 0 {
		c.SlotTTL = DefaultSlotTTL
	}
	if c.IdleTimeout <= 0 {
		c.IdleTimeout = 120 * time.Second
	}
	if c.MaxDatagram <= 0 {
		c.MaxDatagram = 1500
	}
	if c.SlotQueue <= 0 {
		c.SlotQueue = 64
	}
	if c.DownQueue <= 0 {
		c.DownQueue = 256
	}
	if c.ReqQueue <= 0 {
		c.ReqQueue = 64
	}
	if c.JanitorEvery <= 0 {
		c.JanitorEvery = 10 * time.Second
	}
}

// dialInner is how a session opens its socket to the inner inbound. It is a field so
// tests can point it anywhere; by default it dials UDP.
type dialFunc func(addr string) (net.Conn, error)

func dialUDP(addr string) (net.Conn, error) { return net.Dial("udp", addr) }

// Server demultiplexes echo requests into per-session UDP flows and back.
type Server struct {
	cfg  Config
	tr   Transport
	dial dialFunc

	mu       sync.Mutex
	sessions map[uint16]*session
	closed   bool
	done     chan struct{}
	wg       sync.WaitGroup
}

// New creates a Server over the given transport. dial may be nil (UDP is used).
func New(tr Transport, cfg Config, dial dialFunc) *Server {
	cfg.withDefaults()
	if dial == nil {
		dial = dialUDP
	}
	return &Server{
		cfg:      cfg,
		tr:       tr,
		dial:     dial,
		sessions: map[uint16]*session{},
		done:     make(chan struct{}),
	}
}

// Serve reads requests until the transport fails or Close is called. It is the single
// reader of the transport, so session-map access on this path needs no extra ordering
// beyond the mutex that the janitor and sessions also take.
func (s *Server) Serve() error {
	s.wg.Add(1)
	go s.janitor()
	for {
		d, err := s.tr.Recv()
		if err != nil {
			s.shutdown()
			s.wg.Wait()
			return err
		}
		s.dispatch(d)
	}
}

// Close stops the server and all sessions.
func (s *Server) Close() error {
	err := s.tr.Close() // unblocks Serve's Recv
	s.shutdown()
	return err
}

func (s *Server) shutdown() {
	s.mu.Lock()
	if s.closed {
		s.mu.Unlock()
		return
	}
	s.closed = true
	close(s.done)
	for id, ses := range s.sessions {
		ses.stop()
		delete(s.sessions, id)
	}
	s.mu.Unlock()
}

// dispatch routes one request. Ordinary pings (no magic) are answered here and never
// reach a session, so monitoring keeps working (correction 4). A tunnel request is
// handed to its session, which is created on first sight.
func (s *Server) dispatch(d Datagram) {
	if !IsOurs(d.Body) {
		// An ordinary ping: echo its body back as an echo reply, exactly as the
		// kernel would have before we silenced it.
		_ = s.tr.SendReply(d)
		return
	}
	h, inner, err := Decode(d.Body)
	if err != nil {
		return
	}
	s.mu.Lock()
	if s.closed {
		s.mu.Unlock()
		return
	}
	ses := s.sessions[h.Session]
	if ses == nil {
		ses = s.newSession(h.Session)
		if ses == nil {
			s.mu.Unlock()
			return
		}
		s.sessions[h.Session] = ses
	}
	s.mu.Unlock()

	// A copy: d.Body aliases the transport's read buffer, reused after dispatch.
	req := slot{peer: d.Peer, id: d.ID, icmpSeq: d.ICMPSeq, at: time.Now()}
	if len(inner) > 0 {
		req.up = append([]byte(nil), inner...)
	}
	select {
	case ses.reqs <- req:
	default:
		// The session loop is behind; drop this credit. The client polls again.
	}
}

// newSession opens the inner socket and starts the session goroutines. Called with
// s.mu held.
func (s *Server) newSession(id uint16) *session {
	conn, err := s.dial(s.cfg.Inner)
	if err != nil {
		log.Printf("session %d: dial inner: %v", id, err)
		return nil
	}
	ses := &session{
		id:    id,
		srv:   s,
		inner: conn,
		reqs:  make(chan slot, s.cfg.ReqQueue),
		down:  make(chan []byte, s.cfg.DownQueue),
		stopC: make(chan struct{}),
	}
	ses.touch()
	s.wg.Add(2)
	go ses.readInner()
	go ses.loop()
	if debug {
		log.Printf("session %d: open -> %s", id, s.cfg.Inner)
	} else {
		log.Printf("session %d: open", id)
	}
	return ses
}

// janitor reaps sessions idle longer than IdleTimeout.
func (s *Server) janitor() {
	defer s.wg.Done()
	t := time.NewTicker(s.cfg.JanitorEvery)
	defer t.Stop()
	for {
		select {
		case <-s.done:
			return
		case <-t.C:
			now := time.Now()
			s.mu.Lock()
			for id, ses := range s.sessions {
				if now.Sub(ses.lastSeen()) > s.cfg.IdleTimeout {
					ses.stop()
					delete(s.sessions, id)
					log.Printf("session %d: idle, closed", id)
				}
			}
			s.mu.Unlock()
		}
	}
}

// sessionCount is used by tests.
func (s *Server) sessionCount() int {
	s.mu.Lock()
	defer s.mu.Unlock()
	return len(s.sessions)
}

// slot is one received request kept as a reply credit, plus any upstream payload it
// carried.
type slot struct {
	peer    netip.Addr
	id      uint16
	icmpSeq uint16
	at      time.Time
	up      []byte // inner datagram to forward upstream, nil for a pure poll
}

// session is one client flow: a header session id, one UDP socket to the inner
// inbound, and the two FIFOs of the reply-slot logic.
type session struct {
	id    uint16
	srv   *Server
	inner net.Conn

	reqs  chan slot
	down  chan []byte
	stopC chan struct{}
	once  sync.Once

	mu   sync.Mutex
	last time.Time

	downSeq uint16 // header seq for datagrams we send to the client
}

func (ses *session) touch() {
	ses.mu.Lock()
	ses.last = time.Now()
	ses.mu.Unlock()
}

func (ses *session) lastSeen() time.Time {
	ses.mu.Lock()
	defer ses.mu.Unlock()
	return ses.last
}

// stop is idempotent; it closes the inner socket, which unblocks readInner, and signals
// loop to exit. Safe to call from the janitor and from shutdown.
func (ses *session) stop() {
	ses.once.Do(func() {
		close(ses.stopC)
		ses.inner.Close()
	})
}

// readInner pumps datagrams from the inner inbound into the down channel. On overflow
// the newest is dropped (the channel is full), which is fine: the inner protocol
// recovers from loss and must not block this reader.
func (ses *session) readInner() {
	defer ses.srv.wg.Done()
	buf := make([]byte, ses.srv.cfg.MaxDatagram)
	for {
		n, err := ses.inner.Read(buf)
		if err != nil {
			return
		}
		if n == 0 {
			continue
		}
		pkt := append([]byte(nil), buf[:n]...)
		select {
		case ses.down <- pkt:
		case <-ses.stopC:
			return
		default:
			// down queue full: drop.
		}
	}
}

// loop is the heart of the reply-slot logic. It owns slotsQ and pendingQ and is the
// only goroutine that sends replies for this session, so no locking is needed around
// them.
func (ses *session) loop() {
	defer ses.srv.wg.Done()
	cfg := ses.srv.cfg
	var (
		slotsQ   []slot   // unspent reply credits, oldest first
		pendingQ [][]byte // downstream datagrams waiting for a credit, oldest first
	)
	// A short ticker drives slot-TTL expiry. It runs at a fraction of SlotTTL, at most a
	// second, so a slot is released close to its deadline (and well inside the client's
	// loss timeout) rather than up to a full period late.
	tick := cfg.SlotTTL / 4
	if tick > time.Second {
		tick = time.Second
	}
	if tick < 10*time.Millisecond {
		tick = 10 * time.Millisecond
	}
	t := time.NewTicker(tick)
	defer t.Stop()

	for {
		select {
		case <-ses.stopC:
			return

		case req := <-ses.reqs:
			ses.touch()
			if len(req.up) > 0 {
				if _, err := ses.inner.Write(req.up); err != nil && debug {
					log.Printf("session %d: inner write: %v", ses.id, err)
				}
			}
			if len(pendingQ) > 0 {
				// Spend this credit at once on the oldest waiting datagram.
				ses.reply(req, pendingQ[0])
				pendingQ = pendingQ[1:]
			} else {
				if len(slotsQ) >= cfg.SlotQueue {
					// Too many unspent credits: release the oldest as an empty reply
					// so the client is not left waiting on it.
					ses.reply(slotsQ[0], nil)
					slotsQ = slotsQ[1:]
				}
				slotsQ = append(slotsQ, req)
			}

		case pkt := <-ses.down:
			ses.touch()
			if len(slotsQ) > 0 {
				ses.reply(slotsQ[0], pkt)
				slotsQ = slotsQ[1:]
			} else {
				if len(pendingQ) >= cfg.DownQueue {
					pendingQ = pendingQ[1:] // drop oldest
				}
				pendingQ = append(pendingQ, pkt)
			}

		case now := <-t.C:
			// Release every slot held past its TTL as an empty reply. slotsQ is oldest
			// first, so stop at the first one still within its TTL.
			i := 0
			for i < len(slotsQ) && now.Sub(slotsQ[i].at) >= cfg.SlotTTL {
				ses.reply(slotsQ[i], nil)
				i++
			}
			slotsQ = slotsQ[i:]
		}
	}
}

// reply sends one echo reply answering the request held in sl, carrying inner (nil for
// an empty keepalive reply). It mirrors the request's peer, identifier and ICMP
// sequence so the reply reaches the client's unprivileged ICMP socket through any NAT
// (correction 2).
func (ses *session) reply(sl slot, inner []byte) {
	body := Encode(Header{Session: ses.id, Seq: ses.downSeq}, inner)
	ses.downSeq++
	if err := ses.srv.tr.SendReply(Datagram{
		Peer:    sl.peer,
		ID:      sl.id,
		ICMPSeq: sl.icmpSeq,
		Body:    body,
	}); err != nil && debug {
		log.Printf("session %d: send reply: %v", ses.id, err)
	}
}
