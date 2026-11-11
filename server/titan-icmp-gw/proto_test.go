package main

import (
	"bytes"
	"encoding/hex"
	"testing"
)

// Golden vectors pin the wire format byte-for-byte. The client (the Go "titan" package
// inside libXray) must produce exactly these bytes for the same inputs, or the two ends
// will not interoperate. magic is "TTN1" = 54 54 4e 31, then the big-endian session and
// seq, then the inner datagram verbatim.
var golden = []struct {
	name    string
	session uint16
	seq     uint16
	inner   string // hex
	encoded string // hex
}{
	{
		name:    "empty inner (pure poll / keepalive)",
		session: 0x0001,
		seq:     0x0000,
		inner:   "",
		encoded: "54544e3100010000",
	},
	{
		name:    "four bytes",
		session: 0x0001,
		seq:     0x0000,
		inner:   "deadbeef",
		encoded: "54544e3100010000deadbeef",
	},
	{
		name:    "max session and seq",
		session: 0xffff,
		seq:     0xffff,
		inner:   "00",
		encoded: "54544e31ffffffff00",
	},
	{
		name:    "wireguard-sized first byte and a payload",
		session: 0x1234,
		seq:     0x00ab,
		inner:   "0100000000000000",
		encoded: "54544e31123400ab0100000000000000",
	},
}

func mustHex(t *testing.T, s string) []byte {
	t.Helper()
	b, err := hex.DecodeString(s)
	if err != nil {
		t.Fatalf("bad hex %q: %v", s, err)
	}
	return b
}

func TestGoldenEncode(t *testing.T) {
	for _, g := range golden {
		t.Run(g.name, func(t *testing.T) {
			got := Encode(Header{Session: g.session, Seq: g.seq}, mustHex(t, g.inner))
			want := mustHex(t, g.encoded)
			if !bytes.Equal(got, want) {
				t.Fatalf("encode\n got %s\nwant %s", hex.EncodeToString(got), g.encoded)
			}
		})
	}
}

func TestGoldenDecode(t *testing.T) {
	for _, g := range golden {
		t.Run(g.name, func(t *testing.T) {
			h, inner, err := Decode(mustHex(t, g.encoded))
			if err != nil {
				t.Fatalf("decode: %v", err)
			}
			if h.Session != g.session || h.Seq != g.seq {
				t.Fatalf("header got {%#04x %#04x}, want {%#04x %#04x}", h.Session, h.Seq, g.session, g.seq)
			}
			if !bytes.Equal(inner, mustHex(t, g.inner)) {
				t.Fatalf("inner got %s, want %s", hex.EncodeToString(inner), g.inner)
			}
		})
	}
}

func TestDecodeRejects(t *testing.T) {
	if _, _, err := Decode([]byte{0x54, 0x54, 0x4e}); err != ErrShort {
		t.Fatalf("short: got %v, want ErrShort", err)
	}
	// Right length, wrong magic (an ordinary ping body shape).
	if _, _, err := Decode([]byte{0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00}); err != ErrMagic {
		t.Fatalf("magic: got %v, want ErrMagic", err)
	}
}

func TestIsOurs(t *testing.T) {
	if !IsOurs(mustHex(t, "54544e3100010000deadbeef")) {
		t.Fatal("tunnel datagram not recognised")
	}
	if IsOurs(mustHex(t, "0102030405060708")) {
		t.Fatal("ordinary ping body taken for ours")
	}
	if IsOurs(mustHex(t, "54544e31")) { // magic only, no session/seq: too short to be a header
		// IsOurs requires a full header; four bytes is not enough.
		t.Fatal("4-byte body taken for a full header")
	}
}
