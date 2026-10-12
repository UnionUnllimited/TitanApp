//go:build darwin

package internet

// Titan patch: real TCP Fast Open on iOS/macOS. Setting TCP_FASTOPEN on the socket is not
// enough there: the data has to go with connectx(). The connection is opened on the first
// Write, carrying that data in the SYN (tfo-go), like sing-box does. When TFO can't be
// used (the system refuses the socket options, or the network drops such SYNs), the host is
// dialed the usual way from then on.

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
	"golang.org/x/sys/unix"
)

// How long a TFO dial may take before the host is tried without TFO.
const tfoDialTimeout = 3 * time.Second

// connectx() with data returns before the handshake; if the server hasn't answered the
// SYN by then, the network is taken to drop TFO SYNs and the host is dialed without TFO.
const tfoHandshakeTimeout = 1500 * time.Millisecond

// Hosts where TFO failed: dialed without it from then on.
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
	if len(tfoLog) > 4 {
		tfoLog = tfoLog[len(tfoLog)-4:]
	}
}

// TitanTFOLog returns (and clears) the notes about the last TFO connections.
func TitanTFOLog() string {
	tfoLogMu.Lock()
	defer tfoLogMu.Unlock()
	s := strings.Join(tfoLog, "; ")
	tfoLog = nil
	return s
}

func tcpInfo(conn stdnet.Conn) *unix.TCPConnectionInfo {
	tc, ok := conn.(*stdnet.TCPConn)
	if !ok {
		return nil
	}
	raw, err := tc.SyscallConn()
	if err != nil {
		return nil
	}
	var info *unix.TCPConnectionInfo
	_ = raw.Control(func(fd uintptr) {
		info, _ = unix.GetsockoptTCPConnectionInfo(int(fd), unix.IPPROTO_TCP, unix.TCP_CONNECTION_INFO)
	})
	return info
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
}

func (c *tfoConn) Write(b []byte) (int, error) {
	c.mu.Lock()
	if !c.started {
		c.started = true
		c.mu.Unlock()
		conn, err := c.open(b)
		c.mu.Lock()
		c.conn, c.err = conn, err
		if conn != nil {
			_ = conn.SetReadDeadline(c.deadline[0])
			_ = conn.SetWriteDeadline(c.deadline[1])
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
	return c.conn.Write(b)
}

func (c *tfoConn) open(b []byte) (stdnet.Conn, error) {
	if _, broken := tfoBroken.Load(c.address); !broken {
		start := time.Now()
		ctx, cancel := context.WithTimeout(c.ctx, tfoDialTimeout)
		conn, err := c.dialer.DialContext(ctx, c.network, c.address, b)
		cancel()
		if err == nil {
			if err = waitEstablished(conn, tfoHandshakeTimeout); err == nil {
				tfoNote("tfo %d B, up in %d ms", len(b), time.Since(start).Milliseconds())
				return conn, nil
			}
			if info := tcpInfo(conn); info != nil {
				tfoNote("tfo no handshake: state %d tx %d rexmt %d", info.State, info.Txbytes, info.Txretransmitpackets)
			}
			conn.Close()
		} else {
			tfoNote("tfo dial: %v", err)
		}
		tfoBroken.Store(c.address, struct{}{})
		errors.LogWarning(c.ctx, "TCP Fast Open to ", c.address, " failed, dialing without it: ", err)
	}
	conn, err := c.dialer.Dialer.DialContext(c.ctx, c.network, c.address)
	if err != nil {
		tfoNote("plain dial: %v", err)
		return nil, err
	}
	tfoNote("plain")
	if _, err := conn.Write(b); err != nil {
		conn.Close()
		return nil, err
	}
	return conn, nil
}

// waitEstablished waits for the TCP handshake of conn to complete.
func waitEstablished(conn stdnet.Conn, timeout time.Duration) error {
	tc, ok := conn.(*stdnet.TCPConn)
	if !ok {
		return nil
	}
	raw, err := tc.SyscallConn()
	if err != nil {
		return nil
	}
	const established = 4 // TCPS_ESTABLISHED
	deadline := time.Now().Add(timeout)
	for {
		var state uint8
		var gerr error
		if err := raw.Control(func(fd uintptr) {
			var info *unix.TCPConnectionInfo
			if info, gerr = unix.GetsockoptTCPConnectionInfo(int(fd), unix.IPPROTO_TCP, unix.TCP_CONNECTION_INFO); gerr == nil {
				state = info.State
			}
		}); err != nil || gerr != nil {
			return nil // can't tell: keep the connection
		}
		if state >= established {
			return nil
		}
		if state == 0 || time.Now().After(deadline) {
			return os.ErrDeadlineExceeded
		}
		time.Sleep(10 * time.Millisecond)
	}
}

// Read waits for the first Write: the protocols this is used for speak first.
func (c *tfoConn) Read(b []byte) (int, error) {
	c.mu.Lock()
	deadline := c.deadline[0]
	c.mu.Unlock()
	if err := c.wait(deadline); err != nil {
		return 0, err
	}
	return c.conn.Read(b)
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
	if c.conn != nil {
		if info := tcpInfo(c.conn); info != nil {
			tfoNote("closed: state %d tx %d rx %d rexmt %d", info.State, info.Txbytes, info.Rxbytes, info.Txretransmitpackets)
		}
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
	if c.conn != nil {
		switch i {
		case 0:
			return c.conn.SetReadDeadline(t)
		case 1:
			return c.conn.SetWriteDeadline(t)
		default:
			return c.conn.SetDeadline(t)
		}
	}
	return nil
}

func (c *tfoConn) SetDeadline(t time.Time) error      { return c.setDeadline(-1, t) }
func (c *tfoConn) SetReadDeadline(t time.Time) error  { return c.setDeadline(0, t) }
func (c *tfoConn) SetWriteDeadline(t time.Time) error { return c.setDeadline(1, t) }
