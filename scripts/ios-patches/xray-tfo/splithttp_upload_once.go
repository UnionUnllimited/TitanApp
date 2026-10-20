package splithttp

// Titan patch: over TCP Fast Open every HTTP/1.1 upload (XHTTP packet-up) goes on a
// connection of its own instead of a pooled one. A new connection carries the request in
// its SYN; a request on a reused one follows a finished handshake in plain sight, and
// where only TFO gets through the DPI box cuts the connection there.

import (
	"io"
	"net/http"
	"time"
)

func (c *DefaultDialerClient) titanReleaseUpload(h *H1Conn, conn any) {
	if !c.uploadOnce {
		c.uploadRawPool.Put(conn)
		return
	}
	go func() {
		defer h.Close()
		_ = h.SetReadDeadline(time.Now().Add(30 * time.Second))
		if resp, err := http.ReadResponse(h.RespBufReader, nil); err == nil {
			io.Copy(io.Discard, resp.Body)
			resp.Body.Close()
		}
	}()
}
