package splithttp

// Titan patch: over TCP Fast Open every HTTP/1.1 upload (XHTTP packet-up) goes on a
// connection of its own instead of a pooled one. A new connection carries the request in
// its SYN; a request on a reused one follows a finished handshake in plain sight, and
// where only TFO gets through the DPI box cuts the connection there.

import (
	"context"
	"io"
	"net/http"
	"time"

	"github.com/xtls/xray-core/transport/internet"
)

// titanUploadCtx marks the dial of a packet-up upload connection as safe to repeat for TCP
// Fast Open: the server takes each upload once by its sequence number and drops it if it
// comes again, so the whole request may go in the SYN.
func titanUploadCtx(ctx context.Context) context.Context {
	return internet.TitanTFOReplaySafe(ctx)
}

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
