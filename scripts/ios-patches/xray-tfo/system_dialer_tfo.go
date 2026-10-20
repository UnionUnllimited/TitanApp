//go:build darwin || windows

package internet

// Titan patch: real TCP Fast Open on iOS/macOS and Windows. Setting TCP_FASTOPEN on the
// socket is not enough there: the data has to go with connectx() (Apple) or ConnectEx()
// (Windows). The connection is opened on the first Write, carrying that data in the SYN
// (tfo-go), like sing-box does.
//
// Apple's TCP puts at most the default MSS minus options in a SYN (tcp_tfo_check): with
// more queued it sends no data in the SYN at all and the connection goes like a plain one.
// So the SYN gets the first part of the first write (tfoSynData, per system) and the rest
// follows. A connection is never redialed for staying silent: some protocols (an XHTTP
// upload) get no answer.

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
	// How long a TFO dial may take before the host is tried without TFO (a lost SYN-ACK
	// is sent again after ~2.5 s on mobile networks; that must not count as a failure).
	tfoDialTimeout = 4 * time.Second
	// How long a host where a TFO dial failed is dialed without it.
	tfoRetryAfter = 2 * time.Minute
)

// Hosts where a TFO dial failed, and when.
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

	// Traffic, for the notes.
	start     time.Time
	sent, got int
	firstByte time.Duration
}

func (c *tfoConn) traffic() string {
	c.mu.Lock()
	defer c.mu.Unlock()
	first := "never"
	if c.got > 0 {
		first = fmt.Sprintf("%d ms", c.firstByte.Milliseconds())
	}
	return fmt.Sprintf("%d ms, sent %d B, got %d B, first byte %s after dial start", time.Since(c.start).Milliseconds(), c.sent, c.got, first)
}

func (c *tfoConn) Write(b []byte) (int, error) {
	c.mu.Lock()
	if !c.started {
		c.started = true
		c.start = time.Now()
		c.mu.Unlock()
		conn, viaTFO, err := c.open(b)
		c.mu.Lock()
		c.conn, c.err = conn, err
		if conn != nil {
			c.sent += len(b)
			_ = conn.SetReadDeadline(c.deadline[0])
			_ = conn.SetWriteDeadline(c.deadline[1])
			if viaTFO {
				tfoLive.Store(c, struct{}{})
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
	n, err := c.conn.Write(b)
	c.mu.Lock()
	c.sent += n
	c.mu.Unlock()
	return n, err
}

// open dials with TFO (the first part of b in the SYN), or the usual way for a host where
// a TFO dial failed lately.
func (c *tfoConn) open(b []byte) (stdnet.Conn, bool, error) {
	if since, broken := tfoBroken.Load(c.address); !broken || time.Since(since.(time.Time)) > tfoRetryAfter {
		limit := tfoSynData4
		if ap, err := netip.ParseAddrPort(c.address); err == nil && ap.Addr().Is6() && !ap.Addr().Is4In6() {
			limit = tfoSynData6
		}
		first := b
		if len(first) > limit {
			first = first[:limit]
		}
		ctx, cancel := context.WithTimeout(c.ctx, tfoDialTimeout)
		conn, err := c.dialer.DialContext(ctx, c.network, c.address, first)
		cancel()
		if err == nil {
			if rest := b[len(first):]; len(rest) > 0 {
				if _, err = conn.Write(rest); err != nil {
					conn.Close()
					return nil, false, err
				}
			}
			tfoNote("tfo %d+%d B in %d ms", len(first), len(b)-len(first), time.Since(c.start).Milliseconds())
			return conn, true, nil
		}
		tfoBroken.Store(c.address, time.Now())
		tfoNote("tfo dial: %v", err)
		errors.LogWarning(c.ctx, "TCP Fast Open to ", c.address, " failed, dialing without it: ", err)
	}
	conn, err := c.dialer.Dialer.DialContext(c.ctx, c.network, c.address)
	if err != nil {
		tfoNote("plain dial: %v", err)
		return nil, false, err
	}
	if _, err := conn.Write(b); err != nil {
		conn.Close()
		return nil, false, err
	}
	tfoNote("plain")
	return conn, false, nil
}

// Read waits for the first Write: the protocols this is used for speak first.
func (c *tfoConn) Read(b []byte) (int, error) {
	c.mu.Lock()
	deadline := c.deadline[0]
	c.mu.Unlock()
	if err := c.wait(deadline); err != nil {
		return 0, err
	}
	n, err := c.conn.Read(b)
	if n > 0 {
		c.mu.Lock()
		if c.got == 0 {
			c.firstByte = time.Since(c.start)
		}
		c.got += n
		c.mu.Unlock()
	}
	return n, err
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
	if c.conn != nil {
		return c.conn.Close()
	}
	return nil
}

func (c *tfoConn) dialed() stdnet.Conn {
	select {
	case <-c.ready:
		return c.conn
	default:
		return nil
	}
}

func (c *tfoConn) LocalAddr() stdnet.Addr {
	if conn := c.dialed(); conn != nil {
		return conn.LocalAddr()
	}
	return &stdnet.TCPAddr{}
}

func (c *tfoConn) RemoteAddr() stdnet.Addr {
	if conn := c.dialed(); conn != nil {
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
	switch i {
	case 0:
		return c.conn.SetReadDeadline(t)
	case 1:
		return c.conn.SetWriteDeadline(t)
	default:
		return c.conn.SetDeadline(t)
	}
}

func (c *tfoConn) SetDeadline(t time.Time) error      { return c.setDeadline(-1, t) }
func (c *tfoConn) SetReadDeadline(t time.Time) error  { return c.setDeadline(0, t) }
func (c *tfoConn) SetWriteDeadline(t time.Time) error { return c.setDeadline(1, t) }
