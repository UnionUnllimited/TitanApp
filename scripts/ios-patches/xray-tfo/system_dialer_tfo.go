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
//
// Only data that is safe to repeat goes in a SYN (tfo_safe.go); other first writes dial
// the usual way. On Apple systems each dial is checked with the kernel's own record of it
// (TCP_CONNECTION_INFO): whether the data really went in the SYN and the server took it.
// That is what the notes and TitanTFOStats report, not just that TFO was asked for.

import (
	"context"
	"encoding/json"
	"fmt"
	stdnet "net"
	"net/netip"
	"os"
	"runtime"
	"strings"
	"sync"
	"sync/atomic"
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
	note := time.Now().Format("15:04:05 ") + fmt.Sprintf(format, args...)
	tfoLogMu.Lock()
	defer tfoLogMu.Unlock()
	tfoLog = append(tfoLog, note)
	if len(tfoLog) > 6 {
		tfoLog = tfoLog[len(tfoLog)-6:]
	}
	tfoRecent = append(tfoRecent, note)
	if len(tfoRecent) > tfoRecentMax {
		tfoRecent = tfoRecent[len(tfoRecent)-tfoRecentMax:]
	}
}

// The last notes, kept for TitanTFOStats (TitanTFOLog takes its own away): enough to see a
// pattern across a balancer's nodes.
var tfoRecent []string

const tfoRecentMax = 60

// What the dials did since start, by the kernel's account where there is one.
var tfoStat struct {
	dials        atomic.Int64 // TFO dials that connected
	synDataAcked atomic.Int64 // the data went in the SYN and the server took it: real TFO
	synDataLost  atomic.Int64 // the data went in the SYN, the server didn't take it (sent again after the handshake)
	cookieOnly   atomic.Int64 // no cookie for this host and network yet: the SYN asked for one, the data followed the handshake
	kernelPlain  atomic.Int64 // the kernel sent a plain SYN
	unchecked    atomic.Int64 // no kernel record (Windows)
	plain        atomic.Int64 // dialed without TFO: a TFO dial to the host failed lately
	notSafe      atomic.Int64 // dialed without TFO: the first write isn't safe to repeat
	failed       atomic.Int64 // TFO dials that failed
	noAnswer     atomic.Int64 // TFO connections closed with nothing received after a TLS or HTTP first write
}

// What the kernel did with a dialed connection (tfoKernel, per system).
type tfoKernelInfo struct {
	synDataSent, synDataAcked bool
	cookieReq, cookieRcv      bool
	forced                    bool // TCP_FASTOPEN_FORCE_ENABLE in place (Apple's TFO backoff off)
	srttMs                    uint32
	retransmit                uint64 // bytes sent again
	extra                     []string
}

// verdict counts the dial and says what really went on the wire.
func (k tfoKernelInfo) verdict() string {
	var v string
	switch {
	case k.synDataSent && k.synDataAcked:
		tfoStat.synDataAcked.Add(1)
		v = "data in SYN, acked"
	case k.synDataSent:
		tfoStat.synDataLost.Add(1)
		v = "data in SYN, NOT acked (resent after the handshake)"
	case k.cookieReq:
		tfoStat.cookieOnly.Add(1)
		v = "no cookie yet, data after the handshake"
		if k.cookieRcv {
			v += " (cookie received)"
		}
	default:
		tfoStat.kernelPlain.Add(1)
		v = "plain SYN by the kernel"
	}
	if !k.forced {
		v += ", heuristics not forced off"
	}
	if len(k.extra) > 0 {
		v += ", " + strings.Join(k.extra, ", ")
	}
	return v + fmt.Sprintf(", rtt %d ms", k.srttMs)
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

// TitanTFOStats returns, as JSON, what the TFO dials did since start (kernel-checked where
// the system keeps a record), the SYN data limits and the last notes.
func TitanTFOStats() string {
	tfoLogMu.Lock()
	recent := append([]string(nil), tfoRecent...)
	tfoLogMu.Unlock()
	tfoLive.Range(func(k, _ any) bool {
		recent = append(recent, "open: "+k.(*tfoConn).traffic())
		return true
	})
	b, _ := json.Marshal(map[string]any{
		"system":       runtime.GOOS,
		"synLimitIPv4": tfoSynData4,
		"synLimitIPv6": tfoSynData6,
		"dials":        tfoStat.dials.Load(),
		"synDataAcked": tfoStat.synDataAcked.Load(),
		"synDataLost":  tfoStat.synDataLost.Load(),
		"cookieOnly":   tfoStat.cookieOnly.Load(),
		"kernelPlain":  tfoStat.kernelPlain.Load(),
		"unchecked":    tfoStat.unchecked.Load(),
		"plain":        tfoStat.plain.Load(),
		"notSafe":      tfoStat.notSafe.Load(),
		"failed":       tfoStat.failed.Load(),
		"noAnswer":     tfoStat.noAnswer.Load(),
		"recent":       recent,
	})
	return string(b)
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
	kind      string // the first write's, on a TFO connection (tfoSynPart)
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
		conn, kind, err := c.open(b)
		c.mu.Lock()
		c.conn, c.err = conn, err
		if conn != nil {
			c.sent += len(b)
			_ = conn.SetReadDeadline(c.deadline[0])
			_ = conn.SetWriteDeadline(c.deadline[1])
			if kind != "" {
				c.kind = kind
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

// open dials with TFO (the first part of b in the SYN, as much of it as is safe to repeat),
// or the usual way for a host where a TFO dial failed lately. It says what the first write
// was if the connection is a TFO one ("" if not).
func (c *tfoConn) open(b []byte) (stdnet.Conn, string, error) {
	safe, kind := tfoSynPart(c.ctx, b)
	if safe == 0 {
		tfoStat.notSafe.Add(1)
		return c.openPlain(b, "plain: "+kind+" first write kept out of the SYN (not safe to repeat)")
	}
	if since, broken := tfoBroken.Load(c.address); !broken || time.Since(since.(time.Time)) > tfoRetryAfter {
		limit := tfoSynData4
		if ap, err := netip.ParseAddrPort(c.address); err == nil && ap.Addr().Is6() && !ap.Addr().Is4In6() {
			limit = tfoSynData6
		}
		first := b[:min(safe, limit)]
		ctx, cancel := context.WithTimeout(c.ctx, tfoDialTimeout)
		conn, err := c.dialer.DialContext(ctx, c.network, c.address, first)
		cancel()
		if err == nil {
			tfoStat.dials.Add(1)
			// The kernel's record right after the handshake: what went in the SYN.
			verdict := "no kernel record"
			if k, ok := tfoKernel(conn); ok {
				verdict = k.verdict()
			} else {
				tfoStat.unchecked.Add(1)
			}
			if rest := b[len(first):]; len(rest) > 0 {
				if _, err = conn.Write(rest); err != nil {
					conn.Close()
					return nil, "", err
				}
			}
			tfoNote("tfo %s %d+%d B in %d ms: %s", kind, len(first), len(b)-len(first), time.Since(c.start).Milliseconds(), verdict)
			return conn, kind, nil
		}
		tfoStat.failed.Add(1)
		tfoBroken.Store(c.address, time.Now())
		tfoNote("tfo dial: %v", err)
		errors.LogWarning(c.ctx, "TCP Fast Open to ", c.address, " failed, dialing without it: ", err)
	}
	tfoStat.plain.Add(1)
	return c.openPlain(b, "plain (TFO to this host failed lately)")
}

// openPlain dials without TFO and sends b after the handshake.
func (c *tfoConn) openPlain(b []byte, note string) (stdnet.Conn, string, error) {
	conn, err := c.dialer.Dialer.DialContext(c.ctx, c.network, c.address)
	if err != nil {
		tfoNote("plain dial: %v", err)
		return nil, "", err
	}
	if _, err := conn.Write(b); err != nil {
		conn.Close()
		return nil, "", err
	}
	tfoNote("%s", note)
	return conn, "", nil
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
		resent := ""
		if k, ok := tfoKernel(c.conn); ok && k.retransmit > 0 {
			resent = fmt.Sprintf(", resent %d B", k.retransmit)
		}
		// A TLS or HTTP request gets an answer: none at all, its data (or the rest of it,
		// after the handshake) never reached the server, the way a DPI box drops it.
		c.mu.Lock()
		silent := c.got == 0 && (c.kind == "tls" || strings.HasPrefix(c.kind, "http"))
		c.mu.Unlock()
		if silent {
			tfoStat.noAnswer.Add(1)
			resent += ", no answer"
		}
		tfoNote("closed: %s%s", c.traffic(), resent)
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
