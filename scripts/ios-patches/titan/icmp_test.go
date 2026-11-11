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

	// A full-MTU downstream reply: the body (our header + a 1232-byte inner) must fit exactly in a
	// icmpHeaderLen+mtu buffer, so the shim's downstream buffer never truncates it.
	const mtu = 1232
	raw := make([]byte, icmpEchoHeaderLen+icmpHeaderLen+mtu)
	raw[0] = icmpTypeEchoReply
	rbody, _, _, ok := parseEchoReply(raw)
	if !ok || len(rbody) != icmpHeaderLen+mtu {
		t.Fatalf("full-MTU reply body = %d bytes, want %d", len(rbody), icmpHeaderLen+mtu)
	}
	dst := make([]byte, icmpHeaderLen+mtu)
	if copy(dst, rbody) != len(dst) {
		t.Fatal("full-MTU body does not fit the shim downstream buffer")
	}
}

// --- credit: polling, adaptation, expiry, rtt ---

func ms(d int) int64 { return int64(time.Duration(d) * time.Millisecond) }

func TestICMPCredit(t *testing.T) {
	// Idle: target starts at min; polls are wanted until outstanding reaches it.
	c := newCredit(2, 8, 100*time.Millisecond)
	if !c.shouldPoll() {
		t.Fatal("want a poll at the start")
	}
	c.onSent(0)
	c.onSent(0)
	if c.shouldPoll() {
		t.Fatal("outstanding reached target, no poll wanted")
	}

	// A data reply frees a slot and grows the target (downstream is busy).
	c.onReply(true, ms(10))
	if out, tgt, _ := c.snapshot(); out != 1 || tgt != 4 {
		t.Fatalf("after data reply: outstanding %d, target %d; want 1, 4", out, tgt)
	}

	// Expiry: two requests at t=0 are lost by t=200ms (timeout 100ms); the tick reclaims them.
	c2 := newCredit(2, 8, 100*time.Millisecond)
	c2.onSent(0)
	c2.onSent(0)
	top, exp := c2.tick(ms(200))
	if exp != 2 || top != 2 {
		t.Fatalf("tick expired %d (want 2), topUp %d (want 2)", exp, top)
	}
	if out, _, _ := c2.snapshot(); out != 0 {
		t.Fatalf("after expiry outstanding %d, want 0", out)
	}

	// Target grows +2 per data reply, capped at max; then decays toward min across idle ticks.
	c3 := newCredit(2, 8, time.Second)
	for i := 0; i < 3; i++ {
		c3.onReply(true, ms(1)) // 2 -> 4 -> 6 -> 8, capped at max 8
	}
	if _, tgt, _ := c3.snapshot(); tgt != 8 {
		t.Fatalf("grown target %d, want 8 (max)", tgt)
	}
	for i := 0; i < 12; i++ {
		c3.tick(ms(1)) // idle ticks decay by 1 each (the first keeps the replies' window), floored at min
	}
	if _, tgt, _ := c3.snapshot(); tgt != 2 {
		t.Fatalf("decayed target %d, want 2 (min)", tgt)
	}

	// RTT: first sample sets the EWMA.
	c4 := newCredit(2, 8, time.Second)
	c4.onSent(0)
	c4.onReply(true, ms(50))
	if _, _, rtt := c4.snapshot(); rtt != ms(50) {
		t.Fatalf("rtt %v, want 50ms", time.Duration(rtt))
	}
}

// --- the pipe, over an in-memory fake transport ---

type fakePkt struct {
	payload []byte
	id, seq uint16
}

type fakeTransport struct {
	sent   chan fakePkt
	recv   chan []byte
	closed chan struct{}
	once   sync.Once
}

func newFakeTransport(n int) *fakeTransport {
	return &fakeTransport{sent: make(chan fakePkt, n), recv: make(chan []byte, n), closed: make(chan struct{})}
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
	case b := <-f.recv:
		return copy(buf, b), 0, 0, nil
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
	var dataSeq uint16
	deadline := time.Now().Add(2 * time.Second)
	for {
		p, ok := f.readSent(t, time.Until(deadline))
		if !ok {
			t.Fatal("no data request reached the transport")
		}
		inner, seq, good := parseFrame(p.payload, cfg.Magic, cfg.Session)
		if good && string(inner) == "hello" {
			dataSeq = seq
			if p.id != cfg.Session {
				t.Fatalf("data echo id %#x, want session %#x", p.id, cfg.Session)
			}
			if p.seq != seq {
				t.Fatalf("echo seq %d != header seq %d", p.seq, seq)
			}
			break
		}
	}

	// The node replies downstream (a frame carrying "world"); it reaches the Xray outbound.
	reply := make([]byte, icmpHeaderLen+len("world"))
	putFrameHeader(reply, cfg.Magic, cfg.Session, dataSeq)
	copy(reply[icmpHeaderLen:], "world")
	f.recv <- reply

	xray.SetReadDeadline(time.Now().Add(2 * time.Second))
	got := make([]byte, 64)
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
	// A reply that doesn't match our magic is counted and dropped, not forwarded.
	f.recv <- []byte{0, 0, 0, 0, 0, 0, 0, 0}
	for i := 0; i < 50 && s.stats.dropMagic.Load() == 0; i++ {
		time.Sleep(10 * time.Millisecond)
	}
	if s.stats.dropMagic.Load() == 0 {
		t.Fatal("a non-magic reply was not counted as a drop")
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
