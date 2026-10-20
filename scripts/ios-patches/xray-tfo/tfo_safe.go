package internet

// Titan patch: what of a TCP Fast Open connection's first write may go in the SYN.
//
// Data in a SYN can reach the server twice: a duplicated or replayed SYN with a valid
// cookie opens a second connection that carries it again (RFC 7413, 6.3.2). So only data
// that does no harm when repeated goes there:
//   - a TLS ClientHello (a repeat only starts a handshake nobody finishes);
//   - HTTP request headers, and the body of a request that is safe to repeat: GET, HEAD,
//     OPTIONS, or one the dial is marked for (TitanTFOReplaySafe: XHTTP uploads, which
//     the server takes once per sequence number and drops when they come again).
// Anything else (a proxy protocol's header with the user's data right behind it) is not
// put in the SYN: the connection is dialed the usual way.

import (
	"bytes"
	"context"
)

type titanTFOSafeKey struct{}

// TitanTFOReplaySafe marks a dial whose first request the server takes only once even if
// it arrives twice.
func TitanTFOReplaySafe(ctx context.Context) context.Context {
	return context.WithValue(ctx, titanTFOSafeKey{}, true)
}

// tfoSynPart returns how many leading bytes of a connection's first write may go in the
// SYN (0: none) and what the write is, for the notes.
func tfoSynPart(ctx context.Context, b []byte) (int, string) {
	marked, _ := ctx.Value(titanTFOSafeKey{}).(bool)
	switch {
	case tfoIsClientHello(b):
		return len(b), "tls"
	case tfoIsHTTPRequest(b):
		end := bytes.Index(b, []byte("\r\n\r\n"))
		if end < 0 || end+4 == len(b) {
			return len(b), "http"
		}
		method := string(b[:bytes.IndexByte(b, ' ')])
		if marked || method == "GET" || method == "HEAD" || method == "OPTIONS" {
			return len(b), "http"
		}
		return end + 4, "http headers"
	case marked:
		return len(b), "marked"
	}
	return 0, "raw"
}

// A TLS handshake record (content type 22, version 3.x) starting with a ClientHello.
func tfoIsClientHello(b []byte) bool {
	return len(b) >= 6 && b[0] == 0x16 && b[1] == 0x03 && b[5] == 0x01
}

// An HTTP/1.x request line ("METHOD target HTTP/1.x"), or the HTTP/2 connection preface.
func tfoIsHTTPRequest(b []byte) bool {
	line := b
	if i := bytes.IndexByte(b, '\n'); i >= 0 {
		line = b[:i]
	} else if len(b) > 8192 {
		return false
	}
	sp := bytes.IndexByte(line, ' ')
	if sp < 3 || sp > 16 {
		return false
	}
	for _, c := range line[:sp] {
		if c < 'A' || c > 'Z' {
			return false
		}
	}
	return bytes.Contains(line, []byte(" HTTP/1.")) || bytes.Contains(line, []byte(" HTTP/2.0"))
}
