package internet

// Titan patch check: only data that is safe to repeat goes in a TFO SYN, and a dial whose SYN
// went twice is told by the data the kernel sent again or by its handshake's time.

import (
	"context"
	"testing"
)

func TestTitanTFOSynPart(t *testing.T) {
	hello := append([]byte{0x16, 0x03, 0x01, 0x02, 0x00, 0x01, 0x00, 0x01, 0xfc}, make([]byte, 600)...)
	get := []byte("GET /p/7e1f HTTP/1.1\r\nHost: a\r\nX-Padding: XXXX\r\n\r\n")
	postHead := "POST /p/7e1f/3 HTTP/1.1\r\nHost: a\r\nContent-Length: 5\r\n\r\n"
	post := []byte(postHead + "hello")
	streamUp := []byte("POST /p/7e1f HTTP/1.1\r\nHost: a\r\nTransfer-Encoding: chunked\r\n\r\n")
	raw := []byte{0x00, 0x8a, 0x2c, 0x91, 0x10, 0x01, 0x01, 0xbb, 0x02, 'h', 'i'} // a VLESS header and data
	marked := TitanTFOReplaySafe(context.Background())
	for _, c := range []struct {
		name string
		ctx  context.Context
		b    []byte
		n    int
		kind string
	}{
		{"ClientHello", context.Background(), hello, len(hello), "tls"},
		{"GET", context.Background(), get, len(get), "http"},
		{"POST headers only (XHTTP stream-up)", context.Background(), streamUp, len(streamUp), "http"},
		{"POST with a body: headers only", context.Background(), post, len(postHead), "http headers"},
		{"POST with a body, XHTTP upload", marked, post, len(post), "http"},
		{"headers not finished yet", context.Background(), post[:30], 30, "http"},
		{"proxy protocol data", context.Background(), raw, 0, "raw"},
		{"proxy protocol data, marked", marked, raw, len(raw), "marked"},
		{"lowercase is not a method", context.Background(), []byte("get / HTTP/1.1\r\n\r\n"), 0, "raw"},
		{"empty", context.Background(), nil, 0, "raw"},
	} {
		n, kind := tfoSynPart(c.ctx, c.b)
		if n != c.n || kind != c.kind {
			t.Errorf("%s: got %d %q, want %d %q", c.name, n, kind, c.n, c.kind)
		}
	}
}

func TestTitanTFOSynTwice(t *testing.T) {
	for _, c := range []struct {
		handshakeMs int64
		srttMs      uint32
		twice       bool
	}{
		{80, 75, false},    // a handshake of one round trip
		{774, 75, false},   // just under 0.7 s over
		{775, 75, true},    // 0.7 s over
		{1090, 80, true},   // the SYN sent again after 1 s
		{1450, 420, true},  // the same on a slower path
		{1500, 900, false}, // a loaded path: its RTT is long too
		{1500, 0, false},   // no RTT known: nothing said
		{-1, 75, false},    // no handshake time known: nothing said
	} {
		if got := tfoSynTwice(c.handshakeMs, c.srttMs); got != c.twice {
			t.Errorf("handshake %d ms, rtt %d ms: got %v, want %v", c.handshakeMs, c.srttMs, got, c.twice)
		}
	}
	// Both signs, as the notes and synTwice count them (tfo_syn_twice.go).
	for _, c := range []struct {
		name         string
		synDataAcked bool
		resentBytes  uint64
		handshakeMs  int64
		srttMs       uint32
		twice        bool
	}{
		{"the SYN-ACK lost: its resend echoes the first SYN's timestamp", true, 438, 1080, 1075, true},
		{"cellular, no timestamps: the RTT timed from the first SYN", true, 438, 1095, 1090, true},
		{"Wi-Fi, no timestamps: no RTT", true, 438, 1095, 0, true},
		{"one SYN, acked", true, 0, 80, 75, false},
		{"SYN data not taken: sent again after the handshake", false, 438, 80, 75, false},
		{"a cookie request resent, with timestamps", false, 0, 1090, 80, true},
		{"a cookie request resent, RTT spanning it (not seen)", false, 0, 1090, 1085, false},
		{"the name lookup took 800 ms, the handshake one round trip", true, 0, 75, 75, false},
		{"no handshake time known", true, 0, -1, 75, false},
	} {
		if got := tfoSynSentAgain(c.synDataAcked, c.resentBytes, c.handshakeMs, c.srttMs); got != c.twice {
			t.Errorf("%s: got %v, want %v", c.name, got, c.twice)
		}
	}
}
