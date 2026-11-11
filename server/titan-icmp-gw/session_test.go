package main

import (
	"errors"
	"io"
	"net"
	"net/netip"
	"sync"
	"testing"
	"time"
)

// testPeer is a TEST-NET-1 placeholder address (RFC 5737); never a real node.
var testPeer = netip.MustParseAddr("192.0.2.1")

var errFakeClosed = errors.New("fake transport closed")

// fakeTransport is an in-memory Transport: the test feeds requests on in and reads the
// daemon's replies from out.
type fakeTransport struct {
	in     chan Datagram
	out    chan Datagram
	closed chan struct{}
	once   sync.Once
}

func newFakeTransport() *fakeTransport {
	return &fakeTransport{
		in:     make(chan Datagram, 64),
		out:    make(chan Datagram, 1024),
		closed: make(chan struct{}),
	}
}

func (f *fakeTransport) Recv() (Datagram, error) {
	select {
	case d := <-f.in:
		return d, nil
	case <-f.closed:
		return Datagram{}, errFakeClosed
	}
}

func (f *fakeTransport) SendReply(d Datagram) error {
	cp := Datagram{Peer: d.Peer, ID: d.ID, ICMPSeq: d.ICMPSeq, Body: append([]byte(nil), d.Body...)}
	select {
	case f.out <- cp:
	case <-f.closed:
	}
	return nil
}

func (f *fakeTransport) Close() error {
	f.once.Do(func() { close(f.closed) })
	return nil
}

// fakeInner is an in-memory net.Conn standing in for the node's inner inbound. The test
// reads what the daemon forwarded upstream from writes, and injects downstream datagrams
// on reads.
type fakeInner struct {
	writes chan []byte
	reads  chan []byte
	closed chan struct{}
	once   sync.Once
}

func newFakeInner() *fakeInner {
	return &fakeInner{
		writes: make(chan []byte, 64),
		reads:  make(chan []byte, 64),
		closed: make(chan struct{}),
	}
}

func (c *fakeInner) Read(p []byte) (int, error) {
	select {
	case b := <-c.reads:
		return copy(p, b), nil
	case <-c.closed:
		return 0, io.EOF
	}
}

func (c *fakeInner) Write(p []byte) (int, error) {
	select {
	case c.writes <- append([]byte(nil), p...):
	case <-c.closed:
	}
	return len(p), nil
}

func (c *fakeInner) Close() error {
	c.once.Do(func() { close(c.closed) })
	return nil
}

func (c *fakeInner) LocalAddr() net.Addr              { return dummyAddr{} }
func (c *fakeInner) RemoteAddr() net.Addr             { return dummyAddr{} }
func (c *fakeInner) SetDeadline(time.Time) error      { return nil }
func (c *fakeInner) SetReadDeadline(time.Time) error  { return nil }
func (c *fakeInner) SetWriteDeadline(time.Time) error { return nil }

type dummyAddr struct{}

func (dummyAddr) Network() string { return "fake" }
func (dummyAddr) String() string  { return "fake" }

// harness wires a Server to a fake transport and a fake inner inbound.
type harness struct {
	tr    *fakeTransport
	inner *fakeInner
	srv   *Server
}

func newHarness(t *testing.T, cfg Config) *harness {
	t.Helper()
	tr := newFakeTransport()
	inner := newFakeInner()
	srv := New(tr, cfg, func(string) (net.Conn, error) { return inner, nil })
	go srv.Serve()
	t.Cleanup(func() { srv.Close() })
	return &harness{tr: tr, inner: inner, srv: srv}
}

func tunnelReq(session, seq, id, icmpSeq uint16, inner []byte) Datagram {
	return Datagram{Peer: testPeer, ID: id, ICMPSeq: icmpSeq, Body: Encode(Header{Session: session, Seq: seq}, inner)}
}

func mustReply(t *testing.T, f *fakeTransport, within time.Duration) Datagram {
	t.Helper()
	select {
	case d := <-f.out:
		return d
	case <-time.After(within):
		t.Fatal("no reply within deadline")
		return Datagram{}
	}
}

func mustNoReply(t *testing.T, f *fakeTransport, within time.Duration) {
	t.Helper()
	select {
	case d := <-f.out:
		h, inner, _ := Decode(d.Body)
		t.Fatalf("unexpected reply: session %d seq %d inner %x", h.Session, h.Seq, inner)
	case <-time.After(within):
	}
}

func mustUpstream(t *testing.T, c *fakeInner, within time.Duration) []byte {
	t.Helper()
	select {
	case b := <-c.writes:
		return b
	case <-time.After(within):
		t.Fatal("no upstream datagram within deadline")
		return nil
	}
}

// A request carrying payload is forwarded upstream; its downstream echo comes back as a
// reply mirroring the request's identifier, ICMP sequence and peer (correction 2), and
// using the same header session id.
func TestRoundTrip(t *testing.T) {
	h := newHarness(t, Config{SlotTTL: time.Second})

	h.tr.in <- tunnelReq(7, 0, 0x1111, 1, []byte("hello"))
	if up := mustUpstream(t, h.inner, time.Second); string(up) != "hello" {
		t.Fatalf("upstream got %q, want hello", up)
	}

	// The inner inbound answers; the daemon spends the held slot on it.
	h.inner.reads <- []byte("world")
	d := mustReply(t, h.tr, time.Second)
	hdr, inner, err := Decode(d.Body)
	if err != nil {
		t.Fatal(err)
	}
	if hdr.Session != 7 || string(inner) != "world" {
		t.Fatalf("reply session %d inner %q, want 7/world", hdr.Session, inner)
	}
	if d.ID != 0x1111 || d.ICMPSeq != 1 || d.Peer != testPeer {
		t.Fatalf("reply not mirrored: id %#x seq %d peer %v", d.ID, d.ICMPSeq, d.Peer)
	}
	if hdr.Seq != 0 {
		t.Fatalf("first downstream header seq %d, want 0", hdr.Seq)
	}
}

// Downstream datagrams that arrive with no outstanding slot are buffered and delivered,
// oldest first, as polls arrive. The per-direction header seq increments.
func TestCreditBufferingAndOrder(t *testing.T) {
	h := newHarness(t, Config{SlotTTL: time.Hour}) // no empty-reply releases during the test

	// Create the session with one poll, then immediately spend its slot so no credit is
	// left outstanding.
	h.tr.in <- tunnelReq(3, 0, 0x1000, 0, nil)
	h.inner.reads <- []byte("prime")
	if d := mustReply(t, h.tr, time.Second); d.ID != 0x1000 {
		t.Fatalf("prime reply id %#x", d.ID)
	}

	// Two downstream datagrams with no slots: buffered, no replies yet.
	h.inner.reads <- []byte("A")
	h.inner.reads <- []byte("B")
	mustNoReply(t, h.tr, 100*time.Millisecond)

	// Poll: spends on the oldest buffered datagram.
	h.tr.in <- tunnelReq(3, 0, 0x2000, 7, nil)
	d1 := mustReply(t, h.tr, time.Second)
	hdr1, inner1, _ := Decode(d1.Body)
	if string(inner1) != "A" || d1.ID != 0x2000 || d1.ICMPSeq != 7 {
		t.Fatalf("first drain: inner %q id %#x seq %d, want A/0x2000/7", inner1, d1.ID, d1.ICMPSeq)
	}

	// Second poll: the next buffered datagram.
	h.tr.in <- tunnelReq(3, 0, 0x3000, 8, nil)
	d2 := mustReply(t, h.tr, time.Second)
	hdr2, inner2, _ := Decode(d2.Body)
	if string(inner2) != "B" {
		t.Fatalf("second drain inner %q, want B", inner2)
	}
	// Header seq increments per downstream datagram sent on this session: prime=0, A=1, B=2.
	if hdr1.Seq != 1 || hdr2.Seq != 2 {
		t.Fatalf("header seqs %d,%d, want 1,2", hdr1.Seq, hdr2.Seq)
	}
}

// A slot the client leaves unspent is returned as an empty reply after SlotTTL, which
// keeps NAT warm and frees the client's poll accounting (correction 3).
func TestEmptyReplyOnTTL(t *testing.T) {
	h := newHarness(t, Config{SlotTTL: 30 * time.Millisecond})

	h.tr.in <- tunnelReq(9, 0, 0x4444, 2, nil) // a poll with nothing to carry
	d := mustReply(t, h.tr, time.Second)
	hdr, inner, err := Decode(d.Body)
	if err != nil {
		t.Fatal(err)
	}
	if len(inner) != 0 {
		t.Fatalf("empty reply carried %d bytes", len(inner))
	}
	if hdr.Session != 9 || d.ID != 0x4444 || d.ICMPSeq != 2 {
		t.Fatalf("empty reply not mirrored: session %d id %#x seq %d", hdr.Session, d.ID, d.ICMPSeq)
	}
}

// An ordinary ping (no magic) is answered verbatim and creates no session, so monitoring
// of the node keeps working (correction 4).
func TestPlainPingAnswered(t *testing.T) {
	h := newHarness(t, Config{})

	ping := Datagram{Peer: testPeer, ID: 0x5555, ICMPSeq: 42, Body: []byte{0x01, 0x02, 0x03, 0x04}}
	h.tr.in <- ping
	d := mustReply(t, h.tr, time.Second)
	if string(d.Body) != string(ping.Body) || d.ID != 0x5555 || d.ICMPSeq != 42 {
		t.Fatalf("ping not echoed verbatim: body %x id %#x seq %d", d.Body, d.ID, d.ICMPSeq)
	}
	time.Sleep(20 * time.Millisecond)
	if n := h.srv.sessionCount(); n != 0 {
		t.Fatalf("ordinary ping created %d sessions", n)
	}
}

// An idle session is reaped and its inner socket closed.
func TestIdleReap(t *testing.T) {
	h := newHarness(t, Config{IdleTimeout: 50 * time.Millisecond, JanitorEvery: 20 * time.Millisecond, SlotTTL: time.Hour})

	h.tr.in <- tunnelReq(1, 0, 0x6666, 0, []byte("x"))
	mustUpstream(t, h.inner, time.Second)
	if n := h.srv.sessionCount(); n != 1 {
		t.Fatalf("session count %d, want 1", n)
	}

	deadline := time.After(2 * time.Second)
	for h.srv.sessionCount() != 0 {
		select {
		case <-deadline:
			t.Fatal("session not reaped")
		case <-time.After(10 * time.Millisecond):
		}
	}
	select {
	case <-h.inner.closed:
	case <-time.After(time.Second):
		t.Fatal("inner socket not closed on reap")
	}
}

// Two clients with distinct session ids get independent flows.
func TestTwoSessions(t *testing.T) {
	tr := newFakeTransport()
	var mu sync.Mutex
	inners := map[string]*fakeInner{}
	dialn := 0
	srv := New(tr, Config{SlotTTL: time.Hour}, func(string) (net.Conn, error) {
		mu.Lock()
		defer mu.Unlock()
		dialn++
		c := newFakeInner()
		inners[string(rune(dialn))] = c
		return c, nil
	})
	go srv.Serve()
	defer srv.Close()

	tr.in <- tunnelReq(100, 0, 0xAAAA, 0, []byte("a"))
	tr.in <- tunnelReq(200, 0, 0xBBBB, 0, []byte("b"))

	deadline := time.After(2 * time.Second)
	for srv.sessionCount() != 2 {
		select {
		case <-deadline:
			t.Fatalf("session count %d, want 2", srv.sessionCount())
		case <-time.After(10 * time.Millisecond):
		}
	}
}
