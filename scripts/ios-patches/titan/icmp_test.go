package titan

import (
	"bytes"
	"errors"
	"net"
	"runtime"
	"sync"
	"testing"
	"time"
)

// --- golden vectors: the wire format is locked, byte for byte ---

func TestICMPFrameGolden(t *testing.T) {
	// Our header + inner "hi": magic "TTN1", session 0x1234, seq 0x0001.
	frame := make([]byte, icmpHeaderLen+2)
	putFrameHeader(frame, icmpMagic, 0x1234, 0x0001)
	copy(frame[icmpHeaderLen:], "hi")
	wantFrame := []byte{0x54, 0x54, 0x4e, 0x31, 0x12, 0x34, 0x00, 0x01, 0x68, 0x69}
	if !bytes.Equal(frame, wantFrame) {
		t.Fatalf("frame = %x, want %x", frame, wantFrame)
	}
	inner, seq, ok := parseFrame(frame, icmpMagic, 0x1234)
	if !ok || seq != 1 || string(inner) != "hi" {
		t.Fatalf("parseFrame = %q, %d, %v", inner, seq, ok)
	}
	if _, _, ok := parseFrame(frame, icmpMagic+1, 0x1234); ok {
		t.Fatal("parseFrame accepted a wrong magic")
	}
	if _, _, ok := parseFrame(frame, icmpMagic, 0x9999); ok {
		t.Fatal("parseFrame accepted a wrong session")
	}
	if _, _, ok := parseFrame(frame[:7], icmpMagic, 0x1234); ok {
		t.Fatal("parseFrame accepted a short frame")
	}

	// The full Echo Request: type 8, code 0, checksum 0 (the kernel fills it), id/seq, then frame.
	buf := make([]byte, icmpEchoHeaderLen+len(frame))
	n := marshalEchoInto(buf, frame, 0x1234, 0x0001)
	wantEcho := []byte{
		0x08, 0x00, 0x00, 0x00, 0x12, 0x34, 0x00, 0x01,
		0x54, 0x54, 0x4e, 0x31, 0x12, 0x34, 0x00, 0x01, 0x68, 0x69,
	}
	if !bytes.Equal(buf[:n], wantEcho) {
		t.Fatalf("echo = %x, want %x", buf[:n], wantEcho)
	}

	// An Echo Reply (type 0) carrying a body, id 0x1234, seq 5.
	reply := []byte{0x00, 0x00, 0xab, 0xcd, 0x12, 0x34, 0x00, 0x05, 0xde, 0xad}
	body, id, rseq, ok := parseEchoReply(reply)
	if !ok || id != 0x1234 || rseq != 5 || !bytes.Equal(body, []byte{0xde, 0xad}) {
		t.Fatalf("parseEchoReply = %x, %#x, %d, %v", body, id, rseq, ok)
	}
	if _, _, _, ok := parseEchoReply([]byte{0x08, 0, 0, 0, 0, 0, 0, 0}); ok {
		t.Fatal("parseEchoReply accepted an echo request (type 8)")
	}

	// A downstream reply bigger than anything we send (the node's inbound decides its size): the
	// shim's receive side takes any echo, so it is never cut to the upstream cap.
	const bigInner = 1452 // e.g. a WireGuard inbound at MTU 1420
	raw := make([]byte, icmpEchoHeaderLen+icmpHeaderLen+bigInner)
	raw[0] = icmpTypeEchoReply
	rbody, _, _, ok := parseEchoReply(raw)
	if !ok || len(rbody) != icmpHeaderLen+bigInner {
		t.Fatalf("big reply body = %d bytes, want %d", len(rbody), icmpHeaderLen+bigInner)
	}
	if icmpEchoHeaderLen+icmpMaxEchoData != 65535-20 {
		t.Fatalf("receive buffer %d, want the largest IPv4 echo (65515)", icmpEchoHeaderLen+icmpMaxEchoData)
	}
}

// --- credit: polling, adaptation, loss, expiry, rtt ---

func ms(d int) int64 { return int64(time.Duration(d) * time.Millisecond) }

func TestICMPCredit(t *testing.T) {
	// Idle: target starts at min; polls are wanted until outstanding reaches it.
	c := newCredit(2, 8, 100*time.Millisecond)
	if !c.shouldPoll() {
		t.Fatal("want a poll at the start")
	}
	c.onSent(0, 1, false)
	c.onSent(0, 2, false)
	if c.shouldPoll() {
		t.Fatal("outstanding reached target, no poll wanted")
	}

	// A data reply frees its slot and grows the target (downstream is busy).
	if lost := c.onReply(1, true, ms(10)); lost != 0 {
		t.Fatalf("lost %d, want 0", lost)
	}
	if out, tgt, _ := c.snapshot(); out != 1 || tgt != 4 {
		t.Fatalf("after data reply: outstanding %d, target %d; want 1, 4", out, tgt)
	}

	// A seq that isn't outstanding (a duplicate, or someone else's) frees nothing.
	if lost := c.onReply(1, false, ms(11)); lost != 0 {
		t.Fatalf("duplicate reply lost %d, want 0", lost)
	}
	if out, _, _ := c.snapshot(); out != 1 {
		t.Fatalf("after a duplicate reply outstanding %d, want 1", out)
	}

	// The node answers in order: the reply to seq 13 gives away 11 and 12 as lost at once, long
	// before their timeout.
	cl := newCredit(1, 8, time.Hour)
	cl.onSent(0, 11, true)
	cl.onSent(0, 12, false)
	cl.onSent(0, 13, false)
	cl.onSent(0, 14, false)
	if lost := cl.onReply(13, false, ms(30)); lost != 2 {
		t.Fatalf("lost %d, want 2 (seq 11 and 12)", lost)
	}
	if out, _, _ := cl.snapshot(); out != 1 {
		t.Fatalf("after in-order loss outstanding %d, want 1 (seq 14)", out)
	}
	if lost := cl.onReply(12, false, ms(31)); lost != 0 { // late, reordered: already given up on
		t.Fatalf("late reply lost %d, want 0", lost)
	}
	if out, _, _ := cl.snapshot(); out != 1 {
		t.Fatalf("a late reply changed outstanding to %d, want 1", out)
	}

	// Expiry: two requests at t=0 are lost by t=200ms (timeout 100ms); the tick reclaims them.
	c2 := newCredit(2, 8, 100*time.Millisecond)
	c2.onSent(0, 1, false)
	c2.onSent(0, 2, false)
	top, exp := c2.tick(ms(200))
	if exp != 2 || top != 2 {
		t.Fatalf("tick expired %d (want 2), topUp %d (want 2)", exp, top)
	}
	if out, _, _ := c2.snapshot(); out != 0 {
		t.Fatalf("after expiry outstanding %d, want 0", out)
	}

	// Target grows +2 per data reply, capped at max; then decays toward min across idle ticks.
	c3 := newCredit(1, 64, time.Second)
	for i := 0; i < 40; i++ {
		c3.onReply(0, true, ms(1)) // 1 -> 3 -> ... capped at max 64
	}
	if _, tgt, _ := c3.snapshot(); tgt != 64 {
		t.Fatalf("grown target %d, want 64 (max)", tgt)
	}
	c3.tick(ms(1)) // the first tick keeps the replies' window
	if _, tgt, _ := c3.snapshot(); tgt != 64 {
		t.Fatalf("target %d after a busy tick, want 64", tgt)
	}
	for i := 0; i < 20; i++ {
		c3.tick(ms(1)) // idle: a quarter of the excess a tick, floored at min
	}
	if _, tgt, _ := c3.snapshot(); tgt != 1 {
		t.Fatalf("decayed target %d after 10 s idle, want 1 (min)", tgt)
	}

	// RTT: only a data request answered with data is a sample, and the lowest one is kept.
	c4 := newCredit(1, 8, time.Hour)
	c4.onSent(0, 1, false) // a poll the node held 300 ms (its slot hold), answered empty
	c4.onReply(1, false, ms(300))
	c4.onSent(ms(300), 2, false) // a poll answered with data after a 2 s hold
	c4.onReply(2, true, ms(2300))
	if _, _, rtt := c4.snapshot(); rtt != 0 {
		t.Fatalf("rtt %v from held polls, want none", time.Duration(rtt))
	}
	c4.onSent(ms(3000), 3, true)
	c4.onReply(3, true, ms(3080))
	c4.onSent(ms(3100), 4, true)
	c4.onReply(4, true, ms(3150))
	c4.onSent(ms(3200), 5, true) // a data request answered empty: no sample
	c4.onReply(5, false, ms(4200))
	if _, _, rtt := c4.snapshot(); rtt != ms(50) {
		t.Fatalf("rtt %v, want 50ms (the lowest data round trip)", time.Duration(rtt))
	}
	// The window rolls: the last window's lowest stands until a new sample, then the lower wins.
	c4.tick(ms(15000))
	if _, _, rtt := c4.snapshot(); rtt != ms(50) {
		t.Fatalf("rtt %v after the window rolled, want 50ms kept", time.Duration(rtt))
	}
	c4.tick(ms(30000))
	c4.onSent(ms(30000), 6, true)
	c4.onReply(6, true, ms(30070))
	c4.tick(ms(45000))
	if _, _, rtt := c4.snapshot(); rtt != ms(70) {
		t.Fatalf("rtt %v, want 70ms (the latest window's)", time.Duration(rtt))
	}
}

// --- the pipe, over an in-memory fake transport ---

type fakePkt struct {
	payload []byte
	id, seq uint16
}

// fakeReply is an Echo Reply body and the echo seq the node mirrored into it.
type fakeReply struct {
	body []byte
	seq  uint16
}

type fakeTransport struct {
	sent   chan fakePkt
	recv   chan fakeReply
	closed chan struct{}
	once   sync.Once
}

func newFakeTransport(n int) *fakeTransport {
	return &fakeTransport{sent: make(chan fakePkt, n), recv: make(chan fakeReply, n), closed: make(chan struct{})}
}

func (f *fakeTransport) writeTo(p []byte, id, seq uint16) error {
	cp := append([]byte(nil), p...)
	select {
	case <-f.closed:
		return errors.New("closed")
	case f.sent <- fakePkt{cp, id, seq}:
		return nil
	default:
		return nil // full: drop, as a lossy transport would, without blocking the shim
	}
}

func (f *fakeTransport) readFrom(buf []byte) (int, uint16, uint16, error) {
	select {
	case <-f.closed:
		return 0, 0, 0, errors.New("closed")
	case r := <-f.recv:
		return copy(buf, r.body), 0, r.seq, nil
	}
}

func (f *fakeTransport) close() error {
	f.once.Do(func() { close(f.closed) })
	return nil
}

func (f *fakeTransport) readSent(t *testing.T, d time.Duration) (fakePkt, bool) {
	t.Helper()
	select {
	case p := <-f.sent:
		return p, true
	case <-time.After(d):
		return fakePkt{}, false
	}
}

func testConfig() icmpConfig {
	cfg := icmpConfig{
		Node:     "127.0.0.1",
		Session:  0x2222,
		MinPolls: 2,
		MaxPolls: 8,
		// Keep the slow loop out of the way of the deterministic checks.
		KeepaliveMs: 600000,
		TickMs:      600000,
		LossMs:      600000,
	}
	applyICMPDefaults(&cfg)
	return cfg
}

func TestICMPPipe(t *testing.T) {
	f := newFakeTransport(64)
	udp, err := net.ListenUDP("udp4", &net.UDPAddr{IP: net.IPv4(127, 0, 0, 1)})
	if err != nil {
		t.Fatal(err)
	}
	cfg := testConfig()
	s := newICMPShim(cfg, f, udp)
	s.start()
	defer s.Close()

	// Priming: min polls are sent so the node has reply slots at once.
	var firstPoll uint16
	for i := 0; i < cfg.MinPolls; i++ {
		p, ok := f.readSent(t, time.Second)
		if !ok {
			t.Fatalf("missing priming poll %d", i)
		}
		inner, _, good := parseFrame(p.payload, cfg.Magic, cfg.Session)
		if !good || len(inner) != 0 {
			t.Fatalf("priming poll %d not an empty framed request: %x", i, p.payload)
		}
		if p.id != cfg.Session {
			t.Fatalf("poll echo id %#x, want session %#x", p.id, cfg.Session)
		}
		if i == 0 {
			firstPoll = p.seq
		}
	}

	// The Xray outbound sends a datagram; the shim wraps it to the node.
	xray, err := net.DialUDP("udp4", nil, udp.LocalAddr().(*net.UDPAddr))
	if err != nil {
		t.Fatal(err)
	}
	defer xray.Close()
	if _, err := xray.Write([]byte("hello")); err != nil {
		t.Fatal(err)
	}
	deadline := time.Now().Add(2 * time.Second)
	for {
		p, ok := f.readSent(t, time.Until(deadline))
		if !ok {
			t.Fatal("no data request reached the transport")
		}
		inner, seq, good := parseFrame(p.payload, cfg.Magic, cfg.Session)
		if good && string(inner) == "hello" {
			if p.id != cfg.Session {
				t.Fatalf("data echo id %#x, want session %#x", p.id, cfg.Session)
			}
			if p.seq != seq {
				t.Fatalf("echo seq %d != header seq %d", p.seq, seq)
			}
			break
		}
	}

	// The node replies downstream (a frame carrying "world") in its oldest slot, mirroring that
	// request's echo seq; it reaches the Xray outbound.
	reply := make([]byte, icmpHeaderLen+len("world"))
	putFrameHeader(reply, cfg.Magic, cfg.Session, 0)
	copy(reply[icmpHeaderLen:], "world")
	f.recv <- fakeReply{reply, firstPoll}

	xray.SetReadDeadline(time.Now().Add(2 * time.Second))
	got := make([]byte, 2048)
	n, err := xray.Read(got)
	if err != nil || string(got[:n]) != "world" {
		t.Fatalf("downstream = %q, %v; want \"world\"", got[:n], err)
	}

	// Counters moved.
	if s.stats.upPackets.Load() != 1 || s.stats.upBytes.Load() != 5 {
		t.Fatalf("up %d pkt / %d B, want 1 / 5", s.stats.upPackets.Load(), s.stats.upBytes.Load())
	}
	if s.stats.downPackets.Load() != 1 || s.stats.downBytes.Load() != 5 {
		t.Fatalf("down %d pkt / %d B, want 1 / 5", s.stats.downPackets.Load(), s.stats.downBytes.Load())
	}

	// A reply bigger than the upstream cap (the node's inbound sends what it sends) arrives whole.
	big := make([]byte, icmpHeaderLen+cfg.MTU+200)
	putFrameHeader(big, cfg.Magic, cfg.Session, 0)
	for i := icmpHeaderLen; i < len(big); i++ {
		big[i] = byte(i)
	}
	f.recv <- fakeReply{big, 0}
	n, err = xray.Read(got)
	if err != nil || !bytes.Equal(got[:n], big[icmpHeaderLen:]) {
		t.Fatalf("big downstream = %d bytes, %v; want %d intact", n, err, len(big)-icmpHeaderLen)
	}

	// Upstream, a datagram of exactly the cap goes; one byte over is dropped and counted, never
	// forwarded cut to the cap.
	for _, size := range []int{cfg.MTU + 1, cfg.MTU} {
		if _, err := xray.Write(make([]byte, size)); err != nil {
			t.Fatal(err)
		}
	}
	for {
		p, ok := f.readSent(t, 2*time.Second)
		if !ok {
			t.Fatal("the full-cap datagram did not reach the transport")
		}
		if inner, _, good := parseFrame(p.payload, cfg.Magic, cfg.Session); good && len(inner) > 0 {
			if len(inner) != cfg.MTU {
				t.Fatalf("upstream forwarded %d bytes, want only the %d-byte one", len(inner), cfg.MTU)
			}
			break
		}
	}
	if s.stats.dropBig.Load() != 1 {
		t.Fatalf("dropBig %d, want 1 (the datagram over the cap)", s.stats.dropBig.Load())
	}

	// A reply that doesn't match our magic is counted and dropped, not forwarded.
	f.recv <- fakeReply{[]byte{0, 0, 0, 0, 0, 0, 0, 0}, 0}
	for i := 0; i < 50 && s.stats.dropMagic.Load() == 0; i++ {
		time.Sleep(10 * time.Millisecond)
	}
	if s.stats.dropMagic.Load() == 0 {
		t.Fatal("a non-magic reply was not counted as a drop")
	}
}

// waitPolls drains what the shim sent within d and returns the polls among it.
func waitPolls(t *testing.T, f *fakeTransport, d time.Duration) []fakePkt {
	t.Helper()
	var polls []fakePkt
	for {
		p, ok := f.readSent(t, d)
		if !ok {
			return polls
		}
		if inner, _, good := parseFrame(p.payload, icmpMagic, 0x2222); good && len(inner) == 0 {
			polls = append(polls, p)
		}
	}
}

// emptyReply is the node releasing the slot of the request with echo seq.
func emptyReply(seq uint16) fakeReply {
	b := make([]byte, icmpHeaderLen)
	putFrameHeader(b, icmpMagic, 0x2222, 0)
	return fakeReply{b, seq}
}

// Idle, one poll is kept at the node: an empty reply (the node's slot hold ran out) is answered
// by one new poll, not a burst. Asleep, it isn't re-polled at all; on wake the node gets its slot
// back at once.
func TestICMPIdleAndSleep(t *testing.T) {
	f := newFakeTransport(64)
	udp, err := net.ListenUDP("udp4", &net.UDPAddr{IP: net.IPv4(127, 0, 0, 1)})
	if err != nil {
		t.Fatal(err)
	}
	cfg := icmpConfig{Node: "127.0.0.1", Session: 0x2222, KeepaliveMs: 600000, TickMs: 600000, LossMs: 600000}
	applyICMPDefaults(&cfg)
	if cfg.MinPolls != 1 {
		t.Fatalf("idle polls %d, want 1", cfg.MinPolls)
	}
	s := newICMPShim(cfg, f, udp)
	registerICMP(s)
	s.start()
	defer s.Close()

	polls := waitPolls(t, f, 100*time.Millisecond)
	if len(polls) != 1 {
		t.Fatalf("primed %d polls, want 1", len(polls))
	}
	f.recv <- emptyReply(polls[0].seq)
	polls = waitPolls(t, f, 100*time.Millisecond)
	if len(polls) != 1 {
		t.Fatalf("an empty reply brought %d polls, want 1", len(polls))
	}

	icmpSleep(true)
	defer icmpSleep(false)
	f.recv <- emptyReply(polls[0].seq)
	if polls := waitPolls(t, f, 100*time.Millisecond); len(polls) != 0 {
		t.Fatalf("asleep, an empty reply brought %d polls, want none", len(polls))
	}
	icmpSleep(false)
	if polls := waitPolls(t, f, 100*time.Millisecond); len(polls) != 1 {
		t.Fatalf("on wake %d polls, want 1", len(polls))
	}
}

func TestICMPStopNoLeak(t *testing.T) {
	base := runtime.NumGoroutine()
	f := newFakeTransport(8)
	udp, err := net.ListenUDP("udp4", &net.UDPAddr{IP: net.IPv4(127, 0, 0, 1)})
	if err != nil {
		t.Fatal(err)
	}
	port := udp.LocalAddr().(*net.UDPAddr).Port
	cfg := testConfig()
	s := newICMPShim(cfg, f, udp)
	registerICMP(s)
	s.start()
	time.Sleep(50 * time.Millisecond)

	if err := s.Close(); err != nil {
		t.Fatal(err)
	}
	s.Close() // idempotent

	// The registry entry is gone.
	icmpMu.Lock()
	_, present := icmpRunning[s]
	icmpMu.Unlock()
	if present {
		t.Fatal("shim still registered after Close")
	}
	// The UDP port is released.
	reuse, err := net.ListenUDP("udp4", &net.UDPAddr{IP: net.IPv4(127, 0, 0, 1), Port: port})
	if err != nil {
		t.Fatalf("port %d not released: %v", port, err)
	}
	reuse.Close()
	// Goroutines return to the baseline.
	for i := 0; i < 100; i++ {
		if runtime.NumGoroutine() <= base {
			return
		}
		time.Sleep(10 * time.Millisecond)
	}
	t.Fatalf("goroutines leaked: %d > baseline %d", runtime.NumGoroutine(), base)
}

// TestICMPReal exercises the real datagram ICMP socket against loopback. It is skipped where the
// sandbox forbids the socket or loopback doesn't echo (the on-device spike decides the real case).
func TestICMPReal(t *testing.T) {
	tr, err := dialICMP(net.IPv4(127, 0, 0, 1), icmpHeaderLen+1232)
	if err != nil {
		t.Skipf("no datagram ICMP socket here: %v", err)
	}
	defer tr.close()

	frame := make([]byte, icmpHeaderLen+4)
	putFrameHeader(frame, icmpMagic, 0x1234, 1)
	copy(frame[icmpHeaderLen:], "ping")
	if err := tr.writeTo(frame, 0x1234, 1); err != nil {
		t.Fatalf("writeTo: %v", err)
	}
	tr.conn.SetReadDeadline(time.Now().Add(2 * time.Second))
	buf := make([]byte, 1500)
	n, _, _, err := tr.readFrom(buf)
	if err != nil {
		t.Skipf("no loopback echo reply: %v", err)
	}
	if !bytes.Equal(buf[:n], frame) {
		t.Fatalf("echo body = %x, want %x", buf[:n], frame)
	}
}
