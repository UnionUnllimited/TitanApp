// Wire format of the ICMP shim. Both ends — the Darwin client (the Go "titan"
// package inside libXray) and this node daemon — encode the same 8-byte header in
// front of every inner datagram:
//
//	offset  size  field
//	0       4     magic   (big-endian uint32, always Magic)
//	4       2     session (big-endian uint16, chosen by the client)
//	6       2     seq     (big-endian uint16, per-direction datagram counter)
//	8       ...   inner datagram (the core's UDP payload: WireGuard / Hysteria2 /
//	              VLESS-or-VMess over mKCP), verbatim
//
// The header rides in the ICMP Echo payload. magic lets the daemon tell a tunnel
// packet from an ordinary ping (which it answers itself, see main.go). session
// carries the client identity because the ICMP identifier cannot: on Darwin the
// client uses an unprivileged datagram ICMP socket whose identifier is owned by the
// kernel, and carrier NAT may rewrite it on the way out. seq is informational — the
// shim is a dumb datagram pipe with no reliability or reordering of its own; the
// inner protocol handles loss.
package main

import (
	"encoding/binary"
	"errors"
)

// Magic is the first four header bytes, ASCII "TTN1" (Titan tunnel, format 1). It is
// a protocol constant, not a secret.
const Magic uint32 = 0x54544E31

// HeaderLen is the fixed size of the shim header in bytes.
const HeaderLen = 8

// ErrShort is returned when a buffer is too small to hold a header.
var ErrShort = errors.New("icmp shim: datagram shorter than header")

// ErrMagic is returned when the header magic does not match; the datagram is not ours
// (e.g. an ordinary ping).
var ErrMagic = errors.New("icmp shim: wrong magic")

// Header is the parsed shim header.
type Header struct {
	Session uint16
	Seq     uint16
}

// IsOurs reports whether body starts with our magic, i.e. whether it is a shim
// datagram rather than an ordinary ICMP echo payload.
func IsOurs(body []byte) bool {
	return len(body) >= HeaderLen && binary.BigEndian.Uint32(body) == Magic
}

// Encode writes the header followed by inner into a freshly allocated buffer.
func Encode(h Header, inner []byte) []byte {
	out := make([]byte, HeaderLen+len(inner))
	binary.BigEndian.PutUint32(out[0:4], Magic)
	binary.BigEndian.PutUint16(out[4:6], h.Session)
	binary.BigEndian.PutUint16(out[6:8], h.Seq)
	copy(out[HeaderLen:], inner)
	return out
}

// Decode parses the header from body and returns it together with the inner datagram,
// which aliases body (no copy). It returns ErrShort or ErrMagic when body is not a
// valid shim datagram.
func Decode(body []byte) (Header, []byte, error) {
	if len(body) < HeaderLen {
		return Header{}, nil, ErrShort
	}
	if binary.BigEndian.Uint32(body) != Magic {
		return Header{}, nil, ErrMagic
	}
	return Header{
		Session: binary.BigEndian.Uint16(body[4:6]),
		Seq:     binary.BigEndian.Uint16(body[6:8]),
	}, body[HeaderLen:], nil
}
