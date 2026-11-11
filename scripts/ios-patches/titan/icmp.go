package titan

// Emergency ICMP transport: a dumb datagram pipe that wraps the tunnel's UDP datagrams in ICMP
// echo, for a node whose IP answers ICMP while TCP/UDP to it are blocked. The data path is
//
//	Xray UDP outbound -> 127.0.0.1:LOCAL (UDP) -> this shim -> Echo Request to the node
//	-> node daemon -> 127.0.0.1:INNER (UDP) -> the node's existing inbound
//
// and the replies come back as Echo Reply. No reliability or reordering is added here: the inner
// protocol (WireGuard, Hysteria2, VLESS/VMess over mKCP) handles its own loss. The shim only
// frames each datagram and keeps enough requests outstanding that the node always has a reply
// slot for downstream data (replies exist only as answers to requests; hans/ptunnel style).
//
// On Darwin the real transport is an unprivileged datagram ICMP socket (x/net/icmp
// ListenPacket("udp4", ...) = SOCK_DGRAM/IPPROTO_ICMP): no root, the kernel owns the ICMP
// identifier and fills the checksum. The id can't carry our session (the kernel overwrites it on
// send and demuxes replies by it, and carrier NAT may rewrite it), so the session lives in our
// own header inside the echo data, and the node answers with the same id it received.
//
// The ICMP side sits behind the icmpTransport interface so the framing, polling/credit and UDP
// pipe are unit-testable with an in-memory fake, with no socket and no root.

import (
	"encoding/binary"
	"encoding/json"
	"errors"
	"io"
	"math/rand/v2"
	"net"
	"net/netip"
	"sync"
	"sync/atomic"
	"time"

	"golang.org/x/net/icmp"
)

// Wire format inside the ICMP echo data: magic u32, session u16, seq u16, then the inner
// datagram. The magic lets the node tell our packets from ordinary pings (which it answers by
// echoing back, so monitoring keeps working); the session demuxes when the id is unreliable; the
// seq is a per-datagram counter for stats (no reordering is done on it).
const (
	// icmpMagic marks our echo payloads. ASCII "TTN1" (Titan tunnel, format 1). Not a secret; the
	// node daemon (server/titan-icmp-gw) uses the same constant, and both ends must agree.
	icmpMagic = 0x54544E31
	// icmpHeaderLen is our header: magic(4) + session(2) + seq(2).
	icmpHeaderLen = 8
	// icmpEchoHeaderLen is the ICMP echo message header: type, code, checksum(2), id(2), seq(2).
	icmpEchoHeaderLen = 8

	icmpTypeEchoRequest = 8 // IPv4 Echo Request
	icmpTypeEchoReply   = 0 // IPv4 Echo Reply
)

// icmpConfig is the startICMP request (req.Config). Only Node is required.
type icmpConfig struct {
	Node        string `json:"node"`        // the node's IPv4 (resolved on the Swift side), reachable by ICMP
	Port        int    `json:"port"`        // local UDP port on 127.0.0.1 (0: a free one, returned in the answer)
	Session     uint16 `json:"session"`     // our session in the header (0: a random non-zero one)
	Magic       uint32 `json:"magic"`       // header magic (0: icmpMagic)
	MTU         int    `json:"mtu"`         // inner datagram cap, bytes
	MinPolls    int    `json:"minPolls"`    // outstanding requests kept while idle
	MaxPolls    int    `json:"maxPolls"`    // ceiling for outstanding requests
	KeepaliveMs int    `json:"keepaliveMs"` // at least one request per this long
	TickMs      int    `json:"tickMs"`      // maintenance tick (top-up, expiry, keepalive)
	LossMs      int    `json:"lossMs"`      // an unanswered request older than this is deemed lost
}

func applyICMPDefaults(c *icmpConfig) {
	if c.Magic == 0 {
		c.Magic = icmpMagic
	}
	if c.Session == 0 {
		c.Session = randSession()
	}
	if c.MTU <= 0 {
		c.MTU = 1232 // ~WireGuard-safe; the owner's inner MTU is ~1200-1280
	}
	if c.MinPolls <= 0 {
		c.MinPolls = 4
	}
	if c.MaxPolls < c.MinPolls {
		c.MaxPolls = 64
	}
	if c.KeepaliveMs <= 0 {
		c.KeepaliveMs = 17000 // the owner's ~15-20 s
	}
	if c.TickMs <= 0 {
		c.TickMs = 500
	}
	if c.LossMs <= 0 {
		c.LossMs = 2500
	}
}

func randSession() uint16 {
	for {
		if v := uint16(rand.Uint32()); v != 0 {
			return v
		}
	}
}

// icmpStart is the monotonic base for nowNanos (time.Since keeps the monotonic clock).
var icmpStart = time.Now()

func nowNanos() int64 { return int64(time.Since(icmpStart)) }

// --- wire helpers (pure, so the format is golden-tested without a socket) ---

// putFrameHeader writes magic, session and seq into the first icmpHeaderLen bytes of buf.
func putFrameHeader(buf []byte, magic uint32, session, seq uint16) {
	binary.BigEndian.PutUint32(buf[0:], magic)
	binary.BigEndian.PutUint16(buf[4:], session)
	binary.BigEndian.PutUint16(buf[6:], seq)
}

// parseFrame validates magic and session and returns the inner datagram and the header seq.
func parseFrame(frame []byte, magic uint32, session uint16) (inner []byte, seq uint16, ok bool) {
	if len(frame) < icmpHeaderLen ||
		binary.BigEndian.Uint32(frame[0:]) != magic ||
		binary.BigEndian.Uint16(frame[4:]) != session {
		return nil, 0, false
	}
	return frame[icmpHeaderLen:], binary.BigEndian.Uint16(frame[6:]), true
}

// marshalEchoInto writes an ICMP Echo Request (header + payload) into buf and returns its length.
// The checksum is left 0: the kernel fills it for a datagram socket (as does Apple's SimplePing).
func marshalEchoInto(buf, payload []byte, id, seq uint16) int {
	buf[0] = icmpTypeEchoRequest
	buf[1] = 0
	buf[2], buf[3] = 0, 0
	binary.BigEndian.PutUint16(buf[4:], id)
	binary.BigEndian.PutUint16(buf[6:], seq)
	n := copy(buf[icmpEchoHeaderLen:], payload)
	return icmpEchoHeaderLen + n
}

// parseEchoReply returns the body of an ICMP Echo Reply and its id and seq.
func parseEchoReply(msg []byte) (body []byte, id, seq uint16, ok bool) {
	if len(msg) < icmpEchoHeaderLen || msg[0] != icmpTypeEchoReply {
		return nil, 0, 0, false
	}
	return msg[icmpEchoHeaderLen:], binary.BigEndian.Uint16(msg[4:]), binary.BigEndian.Uint16(msg[6:]), true
}

// --- transport ---

// icmpTransport carries our echo payloads to and from the node. The datagram-ICMP socket is the
// real one; tests use an in-memory fake, so everything above it runs without a socket or root.
type icmpTransport interface {
	// writeTo sends one ICMP Echo Request carrying payload with the given echo id and seq. On a
	// Darwin datagram socket the kernel owns the id (overwrites it, demuxes replies by it), so id
	// is best-effort; the node copies whatever id it sees into the reply. Safe for concurrent use.
	writeTo(payload []byte, id, seq uint16) error
	// readFrom reads one ICMP Echo Reply body into buf, returning its length and the reply's echo
	// id and seq. It blocks until a reply arrives, the read deadline passes, or close is called.
	readFrom(buf []byte) (n int, id, seq uint16, err error)
	close() error
}

// darwinICMP is the real transport over x/net/icmp's datagram socket.
type darwinICMP struct {
	conn *icmp.PacketConn
	dst  net.Addr // the node; a udp4 icmp socket takes a *net.UDPAddr (the port is ignored)
	wmu  sync.Mutex
	wbuf []byte // reused send buffer: echo header + payload (no per-packet allocation)
	rbuf []byte // reused read buffer: the raw echo message (echo header + body); one reader
}

func dialICMP(nodeIP net.IP, maxPayload int) (*darwinICMP, error) {
	conn, err := icmp.ListenPacket("udp4", "0.0.0.0")
	if err != nil {
		return nil, err
	}
	return &darwinICMP{
		conn: conn,
		dst:  &net.UDPAddr{IP: nodeIP},
		wbuf: make([]byte, icmpEchoHeaderLen+maxPayload),
		rbuf: make([]byte, icmpEchoHeaderLen+maxPayload),
	}, nil
}

func (d *darwinICMP) writeTo(payload []byte, id, seq uint16) error {
	if len(payload) > len(d.wbuf)-icmpEchoHeaderLen {
		return errors.New("icmp: payload over MTU")
	}
	d.wmu.Lock()
	n := marshalEchoInto(d.wbuf, payload, id, seq)
	_, err := d.conn.WriteTo(d.wbuf[:n], d.dst)
	d.wmu.Unlock()
	return err
}

// readFrom is called only by the single downstream goroutine, so rbuf needs no lock. It reads the
// raw echo message into rbuf (echo header + body) and copies just the body into buf, so the
// interface contract ("buf receives the body") holds whatever the ICMP framing is.
func (d *darwinICMP) readFrom(buf []byte) (int, uint16, uint16, error) {
	for {
		n, _, err := d.conn.ReadFrom(d.rbuf)
		if err != nil {
			return 0, 0, 0, err
		}
		body, id, seq, ok := parseEchoReply(d.rbuf[:n])
		if !ok {
			continue // not an echo reply for us
		}
		return copy(buf, body), id, seq, nil
	}
}

func (d *darwinICMP) close() error { return d.conn.Close() }

// --- credit: keep enough requests outstanding for the node's downstream slots ---

// credit is a FIFO of the send times of outstanding Echo Requests. target rides up when replies
// carry data and decays toward min when idle; requests whose reply is lost are expired by age so
// outstanding can't inflate forever. One bounded ring is the single source of truth for
// outstanding, expiry and the RTT estimate. All operations are O(1) (expiry is O(expired)).
type credit struct {
	mu            sync.Mutex
	target        int
	min, max      int
	times         []int64 // ring of send times (nanos); head writes, tail is oldest
	head, tail, n int     // n = outstanding (tracked)
	timeout       int64   // nanos; older unanswered requests are deemed lost
	rtt           int64   // EWMA, nanos
	hadData       bool    // a data reply since the last tick
}

func newCredit(min, max int, timeout time.Duration) *credit {
	// Ring capacity: the poll ceiling plus headroom for an upstream burst. Beyond it the oldest
	// send time is dropped (its request is presumed answered) so the ring stays bounded.
	capacity := max + 64
	return &credit{
		target:  min,
		min:     min,
		max:     max,
		times:   make([]int64, capacity),
		timeout: int64(timeout),
	}
}

func (c *credit) push(now int64) {
	if c.n == len(c.times) { // full: drop the oldest
		c.tail = (c.tail + 1) % len(c.times)
		c.n--
	}
	c.times[c.head] = now
	c.head = (c.head + 1) % len(c.times)
	c.n++
}

func (c *credit) popOldest() (int64, bool) {
	if c.n == 0 {
		return 0, false
	}
	t := c.times[c.tail]
	c.tail = (c.tail + 1) % len(c.times)
	c.n--
	return t, true
}

// onSent records a request just sent (data or poll).
func (c *credit) onSent(now int64) {
	c.mu.Lock()
	c.push(now)
	c.mu.Unlock()
}

// onReply records a reply received; data says it carried an inner datagram. It frees one slot and
// adapts target.
func (c *credit) onReply(data bool, now int64) {
	c.mu.Lock()
	if t, ok := c.popOldest(); ok {
		sample := now - t
		if sample > 0 {
			if c.rtt == 0 {
				c.rtt = sample
			} else {
				c.rtt += (sample - c.rtt) / 8 // EWMA, 1/8 weight
			}
		}
	}
	if data {
		c.hadData = true
		if c.target < c.max {
			c.target += 2
			if c.target > c.max {
				c.target = c.max
			}
		}
	}
	c.mu.Unlock()
}

// tick advances one maintenance interval: it expires lost requests and decays target when
// downstream was idle, then returns how many polls to send to reach target and how many requests
// expired.
func (c *credit) tick(now int64) (topUp, expired int) {
	c.mu.Lock()
	for c.n > 0 && now-c.times[c.tail] > c.timeout {
		c.popOldest()
		expired++
	}
	if !c.hadData && c.target > c.min {
		c.target--
	}
	c.hadData = false
	if c.n < c.target {
		topUp = c.target - c.n
	}
	c.mu.Unlock()
	return topUp, expired
}

// shouldPoll says whether another poll is needed to reach target.
func (c *credit) shouldPoll() bool {
	c.mu.Lock()
	defer c.mu.Unlock()
	return c.n < c.target
}

func (c *credit) snapshot() (outstanding, target int, rtt int64) {
	c.mu.Lock()
	defer c.mu.Unlock()
	return c.n, c.target, c.rtt
}

// --- shim ---

type icmpStatsCounters struct {
	upPackets, upBytes     atomic.Uint64
	downPackets, downBytes atomic.Uint64
	reqSent, replyRecv     atomic.Uint64
	polls, keepalives      atomic.Uint64
	dropMagic, dropSession atomic.Uint64
	dropBig, dropNoPeer    atomic.Uint64
	expired                atomic.Uint64
	outstanding, target    atomic.Int64
	rttMs                  atomic.Int64
	lastReply              atomic.Int64 // nanos of the most recent reply (0: none yet)
}

type icmpShim struct {
	udp     *net.UDPConn
	tr      icmpTransport
	cr      *credit
	magic   uint32
	session uint16
	mtu     int

	keepalive time.Duration
	tickEvery time.Duration

	seq      atomic.Uint32 // header seq counter
	lastSend atomic.Int64  // nanos of the last request sent (for keepalive)
	peer     atomic.Pointer[netip.AddrPort]

	stats     icmpStatsCounters
	done      chan struct{}
	wg        sync.WaitGroup
	closeOnce sync.Once
}

func newICMPShim(cfg icmpConfig, tr icmpTransport, udp *net.UDPConn) *icmpShim {
	return &icmpShim{
		udp:       udp,
		tr:        tr,
		cr:        newCredit(cfg.MinPolls, cfg.MaxPolls, time.Duration(cfg.LossMs)*time.Millisecond),
		magic:     cfg.Magic,
		session:   cfg.Session,
		mtu:       cfg.MTU,
		keepalive: time.Duration(cfg.KeepaliveMs) * time.Millisecond,
		tickEvery: time.Duration(cfg.TickMs) * time.Millisecond,
		done:      make(chan struct{}),
	}
}

func (s *icmpShim) start() {
	s.wg.Add(3)
	go s.upstream()
	go s.downstream()
	go s.maintain()
	s.fill(nowNanos()) // prime the node's reply slots before any upstream traffic
}

// nextSeq returns the next header/echo seq (wraps at 16 bits).
func (s *icmpShim) nextSeq() uint16 { return uint16(s.seq.Add(1)) }

// send writes one frame as an Echo Request (echo id = session) and books the credit.
func (s *icmpShim) send(frame []byte, seq uint16) error {
	if err := s.tr.writeTo(frame, s.session, seq); err != nil {
		return err
	}
	now := nowNanos()
	s.cr.onSent(now)
	s.lastSend.Store(now)
	s.stats.reqSent.Add(1)
	return nil
}

// sendPoll sends an empty (header-only) Echo Request: a reply slot for the node.
func (s *icmpShim) sendPoll(seq uint16) error {
	var hdr [icmpHeaderLen]byte
	putFrameHeader(hdr[:], s.magic, s.session, seq)
	return s.send(hdr[:], seq)
}

// fill sends polls until outstanding reaches target.
func (s *icmpShim) fill(now int64) {
	for s.cr.shouldPoll() {
		if err := s.sendPoll(s.nextSeq()); err != nil {
			return
		}
		s.stats.polls.Add(1)
	}
	_ = now
}

// upstream: UDP datagrams from the Xray outbound -> Echo Requests to the node.
func (s *icmpShim) upstream() {
	defer s.wg.Done()
	buf := make([]byte, icmpHeaderLen+s.mtu)
	for {
		n, ap, err := s.udp.ReadFromUDPAddrPort(buf[icmpHeaderLen:])
		if err != nil {
			return // the conn was closed by Close
		}
		// One peer (the Xray outbound); remember where downstream replies go.
		if cur := s.peer.Load(); cur == nil || *cur != ap {
			p := ap
			s.peer.Store(&p)
		}
		if n > s.mtu {
			s.stats.dropBig.Add(1)
			continue
		}
		seq := s.nextSeq()
		putFrameHeader(buf, s.magic, s.session, seq)
		if err := s.send(buf[:icmpHeaderLen+n], seq); err != nil {
			continue
		}
		s.stats.upPackets.Add(1)
		s.stats.upBytes.Add(uint64(n))
	}
}

// downstream: Echo Replies from the node -> UDP datagrams to the Xray outbound.
func (s *icmpShim) downstream() {
	defer s.wg.Done()
	buf := make([]byte, icmpHeaderLen+s.mtu)
	for {
		n, _, _, err := s.tr.readFrom(buf)
		if err != nil {
			return // the transport was closed by Close
		}
		s.stats.replyRecv.Add(1)
		now := nowNanos()
		s.stats.lastReply.Store(now)
		inner, _, ok := parseFrame(buf[:n], s.magic, s.session)
		if !ok {
			// A reply we can't read still answers one of our requests: free the slot.
			if n >= icmpHeaderLen && binary.BigEndian.Uint32(buf[0:]) != s.magic {
				s.stats.dropMagic.Add(1)
			} else {
				s.stats.dropSession.Add(1)
			}
			s.cr.onReply(false, now)
			s.refill(now)
			continue
		}
		if len(inner) == 0 {
			// An empty reply: a long-poll refresh / keepalive from the node, no data.
			s.cr.onReply(false, now)
			s.refill(now)
			continue
		}
		s.cr.onReply(true, now)
		if ap := s.peer.Load(); ap != nil {
			if _, err := s.udp.WriteToUDPAddrPort(inner, *ap); err == nil {
				s.stats.downPackets.Add(1)
				s.stats.downBytes.Add(uint64(len(inner)))
			}
		} else {
			s.stats.dropNoPeer.Add(1)
		}
		s.refill(now)
	}
}

// refill tops the outstanding requests back up to target after a reply freed a slot.
func (s *icmpShim) refill(now int64) {
	for s.cr.shouldPoll() {
		if err := s.sendPoll(s.nextSeq()); err != nil {
			return
		}
		s.stats.polls.Add(1)
	}
	_ = now
}

// maintain runs the slow loop: expire lost requests, keep target topped up, keepalive, and
// publish the gauges for icmpStats.
func (s *icmpShim) maintain() {
	defer s.wg.Done()
	t := time.NewTicker(s.tickEvery)
	defer t.Stop()
	for {
		select {
		case <-s.done:
			return
		case <-t.C:
			now := nowNanos()
			_, expired := s.cr.tick(now)
			if expired > 0 {
				s.stats.expired.Add(uint64(expired))
			}
			s.fill(now)
			// Keepalive: at least one request per keepalive interval, even when the node holds all
			// our polls without replying (so a NAT mapping on the path doesn't lapse).
			if now-s.lastSend.Load() >= int64(s.keepalive) {
				if err := s.sendPoll(s.nextSeq()); err == nil {
					s.stats.keepalives.Add(1)
				}
			}
			out, tgt, rtt := s.cr.snapshot()
			s.stats.outstanding.Store(int64(out))
			s.stats.target.Store(int64(tgt))
			s.stats.rttMs.Store(rtt / int64(time.Millisecond))
		}
	}
}

// Close stops the shim and releases the socket and goroutines. Safe to call more than once.
func (s *icmpShim) Close() error {
	s.closeOnce.Do(func() {
		close(s.done)
		s.udp.Close()
		s.tr.close()
		unregisterICMP(s)
	})
	s.wg.Wait()
	return nil
}

// --- registry and dispatcher glue ---

var (
	icmpMu      sync.Mutex
	icmpRunning = map[*icmpShim]struct{}{}
)

func registerICMP(s *icmpShim)   { icmpMu.Lock(); icmpRunning[s] = struct{}{}; icmpMu.Unlock() }
func unregisterICMP(s *icmpShim) { icmpMu.Lock(); delete(icmpRunning, s); icmpMu.Unlock() }

// startICMP opens the local UDP listener and the ICMP transport and starts the pipe. It returns
// the chosen local UDP port so the Swift side can point Xray's outbound at 127.0.0.1:port.
func startICMP(configJSON string) (io.Closer, int, error) {
	var cfg icmpConfig
	if err := json.Unmarshal([]byte(configJSON), &cfg); err != nil {
		return nil, 0, err
	}
	ip := net.ParseIP(cfg.Node)
	if ip == nil || ip.To4() == nil {
		return nil, 0, errors.New("startICMP: node must be an IPv4 address")
	}
	applyICMPDefaults(&cfg)
	tr, err := dialICMP(ip.To4(), icmpHeaderLen+cfg.MTU)
	if err != nil {
		return nil, 0, err
	}
	udp, err := net.ListenUDP("udp4", &net.UDPAddr{IP: net.IPv4(127, 0, 0, 1), Port: cfg.Port})
	if err != nil {
		tr.close()
		return nil, 0, err
	}
	s := newICMPShim(cfg, tr, udp)
	registerICMP(s)
	s.start()
	return s, udp.LocalAddr().(*net.UDPAddr).Port, nil
}

// icmpStatsMap sums the running shims' stats for Технический режим.
func icmpStatsMap() map[string]any {
	icmpMu.Lock()
	defer icmpMu.Unlock()
	out := map[string]any{"sessions": len(icmpRunning)}
	var up, upB, down, downB, req, rep, polls, ka uint64
	var dMagic, dSession, dBig, dNoPeer, exp uint64
	var outstanding, target, rtt, lastReply int64
	for s := range icmpRunning {
		up += s.stats.upPackets.Load()
		upB += s.stats.upBytes.Load()
		down += s.stats.downPackets.Load()
		downB += s.stats.downBytes.Load()
		req += s.stats.reqSent.Load()
		rep += s.stats.replyRecv.Load()
		polls += s.stats.polls.Load()
		ka += s.stats.keepalives.Load()
		dMagic += s.stats.dropMagic.Load()
		dSession += s.stats.dropSession.Load()
		dBig += s.stats.dropBig.Load()
		dNoPeer += s.stats.dropNoPeer.Load()
		exp += s.stats.expired.Load()
		outstanding += s.stats.outstanding.Load()
		target += s.stats.target.Load()
		if r := s.stats.rttMs.Load(); r > rtt {
			rtt = r
		}
		if lr := s.stats.lastReply.Load(); lr > lastReply {
			lastReply = lr
		}
	}
	out["upPackets"], out["upBytes"] = up, upB
	out["downPackets"], out["downBytes"] = down, downB
	out["reqSent"], out["replyRecv"] = req, rep
	out["polls"], out["keepalives"] = polls, ka
	out["dropMagic"], out["dropSession"] = dMagic, dSession
	out["dropBig"], out["dropNoPeer"] = dBig, dNoPeer
	out["expired"] = exp
	out["outstanding"], out["target"] = outstanding, target
	out["rttMs"] = rtt
	// Age of the most recent reply across the running shims, for Технический режим (-1: none yet).
	if lastReply > 0 {
		out["lastReplyAgeMs"] = (nowNanos() - lastReply) / int64(time.Millisecond)
	} else {
		out["lastReplyAgeMs"] = int64(-1)
	}
	return out
}
