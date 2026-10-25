package hysteria

// Titan patch, iOS memory (scripts/ios-patches/xray-mem): copied into
// transport/internet/hysteria.

import (
	"sync/atomic"
	"time"

	"github.com/xtls/xray-core/transport/internet"
)

func init() {
	internet.TitanRegisterCloser(titanCloseAll)
}

// titanCloseAll closes every pooled QUIC connection (a keep-alive kept them up for good: the
// cleaner only drops dead ones) and empties the pool. A dial of the stopped instance still
// under way may connect one of these clients again, or pool a new one under its stream
// settings: closed later too.
func titanCloseAll() int {
	// The manager as Dial makes it (it may not have run yet).
	initmanager.Do(func() {
		manager = &clientManager{
			m: make(map[dialerConf]*client),
		}
		go manager.clean()
	})
	manager.Lock()
	old := manager.m
	manager.m = make(map[dialerConf]*client)
	manager.Unlock()
	stale := map[*internet.MemoryStreamConfig]bool{}
	clients := make([]*client, 0, len(old))
	for key, c := range old {
		stale[key.MemoryStreamConfig] = true
		clients = append(clients, c)
	}
	// In a goroutine: a client is locked while it dials.
	go titanClose(clients)
	if len(stale) > 0 {
		time.AfterFunc(internet.TitanSweepAfter, func() {
			manager.Lock()
			for key, c := range manager.m {
				if stale[key.MemoryStreamConfig] {
					delete(manager.m, key)
					clients = append(clients, c)
				}
			}
			manager.Unlock()
			titanClose(clients)
		})
	}
	return len(clients)
}

// titanClients is the pool's clients now, for the cleaner to clean after RUnlock (dialer.go):
// a client is locked while it dials (up to a handshake timeout), and the cleaner waiting for
// it under RLock held up titanCloseAll's Lock, and with it the tunnel's queue
// (closeTransports), until a dial of the stopped instance was done.
func (m *clientManager) titanClients() []*client {
	m.RLock()
	defer m.RUnlock()
	clients := make([]*client, 0, len(m.m))
	for _, c := range m.m {
		clients = append(clients, c)
	}
	return clients
}

// titanClose closes the clients' connections (a QUIC close only sends a packet).
func titanClose(clients []*client) {
	for _, c := range clients {
		c.Lock()
		if c.conn != nil {
			c.close()
		}
		c.Unlock()
	}
}

// A client's use (dialer.go: its titan field): the TCP streams it gave out and not closed yet,
// when it last had a stream or a UDP session (UnixNano), and internet.TitanIdleAfter as it was
// then (under the client's lock: the cleaner reads it, not the variable the tests set).
type titanUse struct {
	streams atomic.Int32
	last    atomic.Int64
	idle    time.Duration
}

func (u *titanUse) touch() { u.last.Store(time.Now().UnixNano()) }

// titanUsed: c is giving out a stream or a session (tcp, udp; under c's lock).
func (c *client) titanUsed() {
	c.titan.idle = internet.TitanIdleAfter
	c.titan.touch()
}

// titanStream counts s, the TCP stream tcp gives out, until it is closed (the outbound closes
// its connection when the proxied one ends). Under c's lock, like the stream's opening.
func (c *client) titanStream(s *interConn) *titanStreamConn {
	c.titan.streams.Add(1)
	c.titan.touch()
	return &titanStreamConn{interConn: s, c: c}
}

type titanStreamConn struct {
	*interConn
	c    *client
	done atomic.Bool
}

func (s *titanStreamConn) Close() error {
	if s.done.CompareAndSwap(false, true) {
		s.c.titan.touch()
		s.c.titan.streams.Add(-1)
	}
	return s.interConn.Close()
}

// titanIdle says c's live connection has had no TCP stream and no UDP session for
// internet.TitanIdleAfter (60 s): the cleaner (every second, under c's lock) closes it then, and the
// next stream dials again. The panel's keep-alive (5 s, under QUIC's 30 s idle timeout) kept
// every subject's connection up for good (~100 KiB of heap and 7 goroutines each, and keep-alives
// on the phone's network), though only the balancers' picks carry traffic; it still keeps a
// connection with streams up (an idle proxied connection: without it QUIC would end it at 30 s).
// A stream or session opens under c's lock (tcp, udp) and touches c first, so none opens on a
// connection this closes. Under c's lock.
func (c *client) titanIdle() bool {
	if c.conn == nil || c.titan.idle <= 0 {
		return false
	}
	now := time.Now().UnixNano()
	if c.titan.streams.Load() > 0 || c.titanSessions() {
		c.titan.last.Store(now)
		return false
	}
	return now-c.titan.last.Load() >= int64(c.titan.idle)
}

// titanSessions says c has a UDP session open (udpSessionManager: until its flow closes it).
func (c *client) titanSessions() bool {
	m := c.udpSM
	if m == nil {
		return false
	}
	m.RLock()
	defer m.RUnlock()
	return len(m.m) > 0
}
