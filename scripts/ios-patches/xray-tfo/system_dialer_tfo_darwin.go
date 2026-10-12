//go:build darwin

package internet

// Titan patch: real TCP Fast Open on iOS/macOS. Setting TCP_FASTOPEN on the socket is not
// enough there: the data has to go with connectx(). The connection is opened on the first
// Write, carrying that data in the SYN (tfo-go), like sing-box does.

import (
	"context"
	stdnet "net"
	"net/netip"
	"os"
	"sync"
	"time"

	"github.com/database64128/tfo-go/v2"
)

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
		conn, err := c.dialer.DialContext(c.ctx, c.network, c.address, b)
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
