//go:build darwin

package internet

// Titan patch: real TCP Fast Open on iOS/macOS. Setting TCP_FASTOPEN on the socket is not
// enough there: the data has to go with connectx(). The connection is opened on the first
// Write, carrying that data in the SYN (tfo-go), like sing-box does.
//
// Without the host's TFO cookie the first connection carries no data in the SYN: it only
// fetches the cookie, and its data goes after the handshake, where a DPI box (TSPU) may cut
// it - the very case TFO is used for. connectx() returns before the handshake, so such a
// connection just stays silent. Until the server's first byte arrives, what was written is
// kept; with no answer within tfoReplyTimeout the host is dialed again with TFO (now with
// the cookie: data in the SYN) and the kept data resent; if that is silent too, the usual
// way. A host where both failed goes without TFO for tfoRetryAfter.

import (
	"context"
	"fmt"
	stdnet "net"
	"net/netip"
	"os"
	"strings"
	"sync"
	"time"

	"github.com/database64128/tfo-go/v2"
	"github.com/xtls/xray-core/common/errors"
)

const (
	// How long a TFO dial may take before the host is tried without TFO.
	tfoDialTimeout = 3 * time.Second
	// How long the first answer may take on a TFO connection.
	tfoReplyTimeout = 1500 * time.Millisecond
	// Data kept for a resend; past that the connection stays as it is.
	tfoKeepLimit = 256 << 10
	// How long a host where TFO failed is dialed without it.
	tfoRetryAfter = 2 * time.Minute
)

// Hosts where TFO failed, and when: dialed without it for tfoRetryAfter.
var tfoBroken sync.Map

// What the last TFO connections did, for the app to show next to a failed ping.
var (
	tfoLogMu sync.Mutex
	tfoLog   []string
)

func tfoNote(format string, args ...any) {
	tfoLogMu.Lock()
	defer tfoLogMu.Unlock()
	tfoLog = append(tfoLog, fmt.Sprintf(format, args...))
	if len(tfoLog) > 6 {
		tfoLog = tfoLog[len(tfoLog)-6:]
	}
}

// TFO connections still open, for the notes.
var tfoLive sync.Map // *tfoConn -> struct{}

// TitanTFOLog returns (and clears) the notes about the last TFO connections, with the
// state of those still open.
func TitanTFOLog() string {
	tfoLogMu.Lock()
	notes := tfoLog
	tfoLog = nil
	tfoLogMu.Unlock()
	tfoLive.Range(func(k, _ any) bool {
		notes = append(notes, "open: "+k.(*tfoConn).traffic())
		return true
	})
	return strings.Join(notes, "; ")
}

func (c *tfoConn) traffic() string {
	c.mu.Lock()
	defer c.mu.Unlock()
	first := "never"
	if c.got > 0 {
		first = fmt.Sprintf("%d ms", c.firstByte.Milliseconds())
	}
	return fmt.Sprintf("%d ms, sent %d B, got %d B, first byte %s", time.Since(c.dialed).Milliseconds(), c.sent, c.got, first)
}

func dialTFO(ctx context.Context, d *stdnet.Dialer, network, address string) (stdnet.Conn, error) {
	return &tfoConn{
		// The caller's context may end once Dial returns; the dial timeout still applies.
		ctx:     context.WithoutCancel(ctx),
		dialer:  &tfo.Dialer{Dialer: *d, Fallback: true},
		network: network,
		address: address,
		ready:   make(chan struct{}),
	}, nil
}

type tfoConn struct {
	ctx     context.Context
	dialer  *tfo.Dialer
	network string
	address string

	mu       sync.Mutex
	started  bool
	conn     stdnet.Conn
	err      error
	ready    chan struct{}
	deadline [2]time.Time // read, write

	// Traffic through this connection, for the notes.
	sent, got int
	viaTFO    bool
	firstByte time.Duration

	// While waiting for the server's first byte on a TFO connection.
	waiting bool
	tries   int // TFO connections made
	dialed  time.Time
	kept    []byte
}

func (c *tfoConn) Write(b []byte) (int, error) {
	c.mu.Lock()
	if !c.started {
		c.started = true
		c.mu.Unlock()
		conn, viaTFO, err := c.open(b)
		c.mu.Lock()
		c.conn, c.err = conn, err
		c.viaTFO = viaTFO
		if err == nil {
			c.sent += len(b)
		}
		if conn != nil {
			_ = conn.SetReadDeadline(c.deadline[0])
			_ = conn.SetWriteDeadline(c.deadline[1])
			if viaTFO {
				tfoLive.Store(c, struct{}{})
				c.waiting, c.dialed, c.tries = true, time.Now(), 1
				c.kept = append([]byte(nil), b...)
			}
		}
		c.mu.Unlock()
		close(c.ready)
		if err != nil {
			return 0, err
		}
		return len(b), nil
	}
	c.mu.Unlock()
	if err := c.wait(time.Time{}); err != nil {
		return 0, err
	}
	c.mu.Lock()
	if c.waiting && len(c.kept)+len(b) <= tfoKeepLimit {
		// Kept and written under the lock, so a redial resends everything in order.
		defer c.mu.Unlock()
		c.kept = append(c.kept, b...)
		n, err := c.conn.Write(b)
		c.sent += n
		return n, err
	}
	c.waiting, c.kept = false, nil
	conn := c.conn
	c.mu.Unlock()
	n, err := conn.Write(b)
	c.mu.Lock()
	c.sent += n
	c.mu.Unlock()
	return n, err
}

// open dials with TFO (b in the SYN), or the usual way for hosts where TFO failed.
func (c *tfoConn) open(b []byte) (stdnet.Conn, bool, error) {
	if since, broken := tfoBroken.Load(c.address); !broken || time.Since(since.(time.Time)) > tfoRetryAfter {
		start := time.Now()
		ctx, cancel := context.WithTimeout(c.ctx, tfoDialTimeout)
		conn, err := c.dialer.DialContext(ctx, c.network, c.address, b)
		cancel()
		if err == nil {
			tfoNote("tfo %d B in %d ms", len(b), time.Since(start).Milliseconds())
			return conn, true, nil
		}
		c.markBroken(fmt.Sprintf("dial: %v", err))
	}
	conn, err := c.dialPlain(b)
	return conn, false, err
}

func (c *tfoConn) dialPlain(data []byte) (stdnet.Conn, error) {
	conn, err := c.dialer.Dialer.DialContext(c.ctx, c.network, c.address)
	if err != nil {
		tfoNote("plain dial: %v", err)
		return nil, err
	}
	if len(data) > 0 {
		if _, err := conn.Write(data); err != nil {
			conn.Close()
			return nil, err
		}
	}
	return conn, nil
}

func (c *tfoConn) markBroken(why string) {
	tfoBroken.Store(c.address, time.Now())
	tfoNote("no tfo to this host (%s)", why)
	errors.LogWarning(c.ctx, "TCP Fast Open to ", c.address, " failed (", why, "), dialing without it")
}

// Read waits for the first Write: the protocols this is used for speak first.
func (c *tfoConn) Read(b []byte) (int, error) {
	c.mu.Lock()
	deadline := c.deadline[0]
	c.mu.Unlock()
	if err := c.wait(deadline); err != nil {
		return 0, err
	}
	for {
		c.mu.Lock()
		conn, waiting, userDeadline := c.conn, c.waiting, c.deadline[0]
		replyBy := c.dialed.Add(tfoReplyTimeout)
		if waiting {
			d := replyBy
			if !userDeadline.IsZero() && userDeadline.Before(d) {
				d = userDeadline
			}
			_ = conn.SetReadDeadline(d)
		}
		c.mu.Unlock()

		n, err := conn.Read(b)
		if n > 0 {
			c.mu.Lock()
			if c.got == 0 && !c.dialed.IsZero() {
				c.firstByte = time.Since(c.dialed)
			}
			c.got += n
			c.mu.Unlock()
		}
		if !waiting {
			return n, err
		}
		if n > 0 {
			c.answered(conn)
			return n, err
		}
		// No answer on the TFO connection yet.
		if !userDeadline.IsZero() && !time.Now().Before(userDeadline) {
			return 0, err
		}
		if timeout, ok := err.(stdnet.Error); ok && timeout.Timeout() && time.Now().Before(replyBy) {
			return 0, err
		}
		if !c.redial(conn, err) {
			return 0, err
		}
	}
}

// answered: the server spoke on conn; nothing to keep any more.
func (c *tfoConn) answered(conn stdnet.Conn) {
	c.mu.Lock()
	defer c.mu.Unlock()
	if c.conn == conn && c.waiting {
		c.waiting, c.kept = false, nil
		_ = conn.SetReadDeadline(c.deadline[0])
	}
}

// redial replaces a silent TFO connection carrying the kept data: once more with TFO (the
// cookie is there now), then the usual way.
func (c *tfoConn) redial(old stdnet.Conn, readErr error) bool {
	c.mu.Lock()
	defer c.mu.Unlock()
	if c.conn != old || !c.waiting {
		return c.conn != old // already replaced
	}
	why := "no answer"
	if readErr != nil {
		why = readErr.Error()
	}
	old.Close()
	if c.tries == 1 {
		c.tries = 2
		tfoNote("tfo silent %d ms, again", time.Since(c.dialed).Milliseconds())
		ctx, cancel := context.WithTimeout(c.ctx, tfoDialTimeout)
		conn, err := c.dialer.DialContext(ctx, c.network, c.address, c.kept)
		cancel()
		if err == nil {
			_ = conn.SetWriteDeadline(c.deadline[1])
			c.conn, c.dialed = conn, time.Now()
			return true
		}
		why = err.Error()
	}
	c.waiting = false
	c.markBroken(fmt.Sprintf("%s in %d ms", why, time.Since(c.dialed).Milliseconds()))
	conn, err := c.dialPlain(c.kept)
	c.kept = nil
	if err != nil {
		return false
	}
	tfoNote("plain redial ok")
	_ = conn.SetReadDeadline(c.deadline[0])
	_ = conn.SetWriteDeadline(c.deadline[1])
	c.conn = conn
	return true
}

func (c *tfoConn) wait(deadline time.Time) error {
	if deadline.IsZero() {
		<-c.ready
	} else {
		t := time.NewTimer(time.Until(deadline))
		defer t.Stop()
		select {
		case <-c.ready:
		case <-t.C:
			return os.ErrDeadlineExceeded
		}
	}
	return c.err
}

func (c *tfoConn) Close() error {
	c.mu.Lock()
	if !c.started {
		c.started = true
		c.err = stdnet.ErrClosed
		c.mu.Unlock()
		close(c.ready)
		return nil
	}
	c.mu.Unlock()
	<-c.ready
	if _, live := tfoLive.LoadAndDelete(c); live {
		tfoNote("closed: %s", c.traffic())
	}
	c.mu.Lock()
	defer c.mu.Unlock()
	if c.conn != nil {
		return c.conn.Close()
	}
	return nil
}

func (c *tfoConn) current() stdnet.Conn {
	select {
	case <-c.ready:
		c.mu.Lock()
		defer c.mu.Unlock()
		return c.conn
	default:
		return nil
	}
}

func (c *tfoConn) LocalAddr() stdnet.Addr {
	if conn := c.current(); conn != nil {
		return conn.LocalAddr()
	}
	return &stdnet.TCPAddr{}
}

func (c *tfoConn) RemoteAddr() stdnet.Addr {
	if conn := c.current(); conn != nil {
		return conn.RemoteAddr()
	}
	if ap, err := netip.ParseAddrPort(c.address); err == nil {
		return stdnet.TCPAddrFromAddrPort(ap)
	}
	return &stdnet.TCPAddr{}
}

func (c *tfoConn) setDeadline(i int, t time.Time) error {
	c.mu.Lock()
	defer c.mu.Unlock()
	if i < 0 {
		c.deadline = [2]time.Time{t, t}
	} else {
		c.deadline[i] = t
	}
	if c.conn == nil {
		return nil
	}
	switch {
	case i == 1:
		return c.conn.SetWriteDeadline(t)
	case c.waiting:
		// Read sets the read deadline itself while waiting for the first answer.
		if i < 0 {
			return c.conn.SetWriteDeadline(t)
		}
		return nil
	case i == 0:
		return c.conn.SetReadDeadline(t)
	default:
		return c.conn.SetDeadline(t)
	}
}

func (c *tfoConn) SetDeadline(t time.Time) error      { return c.setDeadline(-1, t) }
func (c *tfoConn) SetReadDeadline(t time.Time) error  { return c.setDeadline(0, t) }
func (c *tfoConn) SetWriteDeadline(t time.Time) error { return c.setDeadline(1, t) }
