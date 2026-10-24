package splithttp

// Titan patch, iOS memory (scripts/ios-patches/xray-mem): copied into
// transport/internet/splithttp.

import (
	"context"
	gotls "crypto/tls"
	"net/http"
	"sync"
	"time"

	"github.com/xtls/xray-core/common/net"
	"github.com/xtls/xray-core/transport/internet"
	"golang.org/x/net/http2"
)

// What an XHTTP HTTP/2 connection holds unread when its reader stalls (a player with a full
// buffer): Go's client defaults are 4 MiB per stream and 1 GiB per connection, 1 MiB still
// carries ~70 Mbit/s at 100 ms. The frames either side sends are 16 KiB at most (see
// titanH2Conn; ours, or the framer's buffer grows to the server's).
const (
	titanH2StreamWindow = 1 << 20
	titanH2ConnWindow   = 4 << 20
	titanH2FrameSize    = 16 << 10
)

// titanH2Transport is XHTTP's HTTP/2 transport with the limits above; dial gives the TLS /
// REALITY connection for streamSettings.
func titanH2Transport(dial func(context.Context) (net.Conn, error), streamSettings *internet.MemoryStreamConfig, idle, ping time.Duration) *http2.Transport {
	t, err := http2.ConfigureTransports(&http.Transport{HTTP2: &http.HTTP2Config{
		MaxReceiveBufferPerStream:     titanH2StreamWindow,
		MaxReceiveBufferPerConnection: titanH2ConnWindow,
		MaxReadFrameSize:              titanH2FrameSize,
	}})
	if err != nil { // only for an http.Transport that has HTTP/2 already
		t = &http2.Transport{}
	}
	t.DialTLSContext = func(ctx context.Context, network string, addr string, cfg *gotls.Config) (net.Conn, error) {
		conn, err := dial(ctx)
		if err != nil {
			return nil, err
		}
		return titanTrack(conn, streamSettings), nil
	}
	t.IdleConnTimeout = idle
	t.ReadIdleTimeout = ping
	return t
}

// titanH2Conn is an XHTTP HTTP/2 connection. In the server's frames it lowers the
// SETTINGS_MAX_FRAME_SIZE the server advertises (Xray's: 1 MiB) to 16 KiB: Go's client holds a
// scratch buffer of that size, up to 512 KiB, for each request body as long as the body lasts,
// and a stream-up upload lasts as long as its proxied connection. Frames we send stay within
// what the server accepts (and Xray writes 8 KiB at a time).
type titanH2Conn struct {
	net.Conn
	streamSettings *internet.MemoryStreamConfig
	closeOnce      sync.Once

	// Read's place in the server's frames.
	hdr      [9]byte // the frame header being read
	nhdr     int
	left     uint32 // payload bytes of the frame still to come
	settings bool   // the frame is SETTINGS (not its ACK)
	pos      uint32 // payload bytes of it seen
	id       uint16 // the setting being read
}

var titanMaxFrame = [4]byte{0, 0, titanH2FrameSize >> 8, 0}

func (c *titanH2Conn) Read(p []byte) (int, error) {
	n, err := c.Conn.Read(p)
	c.scan(p[:n])
	return n, err
}

// scan follows the frames in b, the server's next bytes, rewriting MAX_FRAME_SIZE in place.
func (c *titanH2Conn) scan(b []byte) {
	for len(b) > 0 {
		if c.nhdr < len(c.hdr) {
			k := copy(c.hdr[c.nhdr:], b)
			c.nhdr += k
			b = b[k:]
			if c.nhdr < len(c.hdr) {
				return
			}
			c.left = uint32(c.hdr[0])<<16 | uint32(c.hdr[1])<<8 | uint32(c.hdr[2])
			c.settings = c.hdr[3] == byte(http2.FrameSettings) && c.hdr[4]&byte(http2.FlagSettingsAck) == 0
			c.pos = 0
			if c.left == 0 {
				c.nhdr = 0
			}
			continue
		}
		k := min(uint32(len(b)), c.left)
		if c.settings {
			for i := range k {
				// Each setting: a 2-byte id, a 4-byte value.
				switch off := c.pos % 6; {
				case off == 0:
					c.id = uint16(b[i]) << 8
				case off == 1:
					c.id |= uint16(b[i])
				case c.id == uint16(http2.SettingMaxFrameSize):
					b[i] = titanMaxFrame[off-2]
				}
				c.pos++
			}
		}
		c.left -= k
		b = b[k:]
		if c.left == 0 {
			c.nhdr = 0
		}
	}
}

func (c *titanH2Conn) Close() error {
	c.closeOnce.Do(func() {
		titanH2Conns.Lock()
		delete(titanH2Conns.m, c)
		titanH2Conns.Unlock()
	})
	return c.Conn.Close()
}

// The HTTP/2 connections open now (the client closes each when it ends), for titanCloseAll.
var titanH2Conns struct {
	sync.Mutex
	m map[*titanH2Conn]struct{}
}

func titanTrack(conn net.Conn, streamSettings *internet.MemoryStreamConfig) *titanH2Conn {
	c := &titanH2Conn{Conn: conn, streamSettings: streamSettings}
	titanH2Conns.Lock()
	defer titanH2Conns.Unlock()
	if titanH2Conns.m == nil {
		titanH2Conns.m = map[*titanH2Conn]struct{}{}
	}
	titanH2Conns.m[c] = struct{}{}
	return c
}

func init() {
	internet.TitanRegisterCloser(titanCloseAll)
}

// titanCloseAll closes every pooled XHTTP connection and empties the pool (no instance runs:
// internet.TitanCloseTransports). A dial the stopped instance still had under way may pool
// one more under its stream settings: a sweep later closes those too.
func titanCloseAll() int {
	globalDialerAccess.Lock()
	stale := map[*internet.MemoryStreamConfig]bool{}
	for key := range globalDialerMap {
		stale[key.MemoryStreamConfig] = true
	}
	n := titanClose(func(s *internet.MemoryStreamConfig) bool { return true })
	globalDialerAccess.Unlock()
	if len(stale) > 0 {
		time.AfterFunc(internet.TitanSweepAfter, func() {
			globalDialerAccess.Lock()
			defer globalDialerAccess.Unlock()
			titanClose(func(s *internet.MemoryStreamConfig) bool { return stale[s] })
		})
	}
	return n
}

// titanClose closes the pool's entries and the HTTP/2 connections of the stream settings
// old says, under globalDialerAccess. Each in a goroutine: a TLS close may wait up to 5 s on a
// dead network (its close_notify) and the caller doesn't.
func titanClose(old func(*internet.MemoryStreamConfig) bool) int {
	var closers []func() error
	for key, m := range globalDialerMap {
		if !old(key.MemoryStreamConfig) {
			continue
		}
		delete(globalDialerMap, key)
		for _, x := range m.xmuxClients {
			// HTTP/3 has its own (DefaultDialerClient.Close); HTTP/2's are below.
			if c, ok := x.XmuxConn.(*DefaultDialerClient); ok && c.httpVersion == "3" {
				closers = append(closers, c.Close)
			}
		}
	}
	titanH2Conns.Lock()
	for c := range titanH2Conns.m {
		if old(c.streamSettings) {
			// Its streams end with it: the requests on it fail, their readers get an error.
			closers = append(closers, c.Close)
		}
	}
	titanH2Conns.Unlock()
	for _, f := range closers {
		go f()
	}
	return len(closers)
}
