package hysteria

import (
	"context"
	"crypto/ecdsa"
	"crypto/elliptic"
	"crypto/rand"
	gotls "crypto/tls"
	"crypto/x509"
	"errors"
	"io"
	"math/big"
	gonet "net"
	"sync/atomic"
	"testing"
	"time"

	"github.com/apernet/quic-go"
	"github.com/xtls/xray-core/transport/internet"
)

// With Chrome parroting (as Hysteria dials) a stalled stream holds at most about the capped
// window unread once Chrome's opening windows are used up, not 6 MiB (xray-mem patch, with
// quic-go patched by quic_go.py). A new stream that stalls still holds Chrome's 6 MiB: the
// connection's other streams go on with what is left of its window. A fresh connection (all
// of them after a restart) holds up to its 16 MiB window: Chrome's 15 from the handshake, then
// the window (until stalled streams holding over 12 MiB are cancelled, TestTitanQUICStalled).
func TestTitanQUICWindows(t *testing.T) {
	ctx, cancel := context.WithTimeout(context.Background(), 30*time.Second)
	defer cancel()
	var sent [12]atomic.Int64
	addr := titanQUICServer(t, ctx, sent[:], nil)
	dial := func(cfg *quic.Config) (*quic.Conn, *quic.Transport) { return titanQUICDial(t, ctx, addr, cfg) }

	// A fresh connection, 3 streams never read: with Chrome parroting what the handshake gave a
	// stream (6 MiB) and the connection's window (16 MiB) at most, without it the caps from the
	// start. Looked at within the 2 s after which the largest would be cancelled.
	for i, parrot := range []bool{true, false} {
		cfg := &quic.Config{ChromeParrot: parrot, InitialStreamReceiveWindow: 8 << 20, MaxStreamReceiveWindow: 8 << 20,
			InitialConnectionReceiveWindow: 20 << 20, MaxConnectionReceiveWindow: 20 << 20}
		internet.TitanQUICWindows(cfg)
		conn, tr := dial(cfg)
		first := 4 + 3*i
		for n := first; n < first+3; n++ {
			str, err := conn.OpenStreamSync(ctx)
			if err != nil {
				t.Fatal(err)
			}
			str.Write([]byte{byte(n)})
		}
		time.Sleep(time.Second)
		stream, conns := int64(internet.TitanQUICStreamWindow), int64(3*internet.TitanQUICStreamWindow)
		if parrot {
			stream, conns = 6<<20, internet.TitanQUICConnWindow
		}
		var total int64
		for n := first; n < first+3; n++ {
			if got := sent[n].Load(); got > stream+256<<10 {
				t.Fatalf("fresh connection (parrot %v): a stream holds %d KiB", parrot, got>>10)
			}
			total += sent[n].Load()
		}
		t.Logf("fresh connection (parrot %v): 3 streams never read hold %d / %d / %d KiB, %d KiB in all",
			parrot, sent[first].Load()>>10, sent[first+1].Load()>>10, sent[first+2].Load()>>10, total>>10)
		if total > conns+3*256<<10 {
			t.Fatalf("fresh connection (parrot %v): %d KiB unread", parrot, total>>10)
		}
		conn.CloseWithError(0, "")
		tr.Close()
	}

	// Xray's Hysteria defaults (8 / 20 MiB), then the caps.
	cfg := &quic.Config{ChromeParrot: true, InitialStreamReceiveWindow: 8 << 20, MaxStreamReceiveWindow: 8 << 20,
		InitialConnectionReceiveWindow: 20 << 20, MaxConnectionReceiveWindow: 20 << 20}
	internet.TitanQUICWindows(cfg)
	if cfg.MaxStreamReceiveWindow != internet.TitanQUICStreamWindow || cfg.MaxConnectionReceiveWindow != internet.TitanQUICConnWindow {
		t.Fatalf("windows %d / %d", cfg.MaxStreamReceiveWindow, cfg.MaxConnectionReceiveWindow)
	}
	conn, tr := dial(cfg)
	defer tr.Close()
	defer conn.CloseWithError(0, "")
	open := func(n byte) *quic.Stream {
		str, err := conn.OpenStreamSync(ctx)
		if err != nil {
			t.Fatal(err)
		}
		str.Write([]byte{n})
		return str
	}
	str := open(1)
	// Past Chrome's opening windows (15 MiB for the connection); then the reader stalls.
	const read = 16 << 20
	if _, err := io.ReadFull(str, make([]byte, read)); err != nil {
		t.Fatal(err)
	}
	time.Sleep(time.Second)
	if unread := sent[1].Load() - read; unread > 2<<20 {
		t.Fatalf("%d KiB sent and not read", unread>>10)
	} else {
		t.Logf("%d KiB sent and not read", unread>>10)
	}

	// A new stream whose reader stalls from the start (an app in the background): it takes
	// Chrome's 6 MiB, the stream above (the video playing) and a third one still get data.
	open(2)
	time.Sleep(500 * time.Millisecond)
	var got [4]atomic.Int64
	readOn := func(n byte, s *quic.Stream, done chan<- struct{}) {
		defer close(done)
		buf := make([]byte, 32<<10)
		for {
			k, err := s.Read(buf)
			got[n].Add(int64(k))
			if err != nil {
				return
			}
		}
	}
	third := open(3)
	done1, done3 := make(chan struct{}), make(chan struct{})
	go readOn(1, str, done1)
	go readOn(3, third, done3)
	time.Sleep(2 * time.Second)
	str.SetReadDeadline(time.Now())
	third.SetReadDeadline(time.Now())
	<-done1
	<-done3
	t.Logf("with a new stream stalled: %d KiB and %d KiB read in 2 s", got[1].Load()>>10, got[3].Load()>>10)
	if got[1].Load() < 8<<20 || got[3].Load() < 8<<20 {
		t.Fatalf("a new stalled stream held up the others: %d KiB and %d KiB in 2 s", got[1].Load()>>10, got[3].Load()>>10)
	}
	// All stalled now: the new one holds Chrome's opening at most, the connection its cap.
	time.Sleep(500 * time.Millisecond)
	unread := sent[1].Load() - read - got[1].Load() + sent[2].Load() + sent[3].Load() - got[3].Load()
	t.Logf("the new stalled stream holds %d KiB, the connection %d KiB unread", sent[2].Load()>>10, unread>>10)
	if sent[2].Load() > 6<<20+256<<10 || unread > internet.TitanQUICConnWindow+256<<10 {
		t.Fatalf("unread: %d KiB on the new stream, %d KiB on the connection", sent[2].Load()>>10, unread>>10)
	}
	// Two more new ones that stall fill the connection's window: past Chrome's opening it is the
	// 16 MiB cap (it kept Chrome's 15: auto-tuning hardly ever ran). Looked at within the 2 s
	// after which the largest stalled stream would be cancelled.
	open(10)
	open(11)
	time.Sleep(time.Second)
	unread += sent[10].Load() + sent[11].Load()
	t.Logf("with two more new streams stalled the connection holds %d KiB unread", unread>>10)
	if unread < internet.TitanQUICConnWindow-256<<10 || unread > internet.TitanQUICConnWindow+256<<10 {
		t.Fatalf("with two more new streams stalled the connection holds %d KiB unread", unread>>10)
	}

	// Pooled, it is closed by titanCloseAll; the sweep later closes what the old stream
	// settings pooled meanwhile, and only that.
	internet.TitanSweepAfter = 100 * time.Millisecond
	defer func() { internet.TitanSweepAfter = 90 * time.Second }()
	internet.TitanCloseTransports() // the manager, as Dial makes it
	old, cur := &internet.MemoryStreamConfig{}, &internet.MemoryStreamConfig{}
	manager.Lock()
	manager.m[dialerConf{MemoryStreamConfig: old}] = &client{conn: conn, tr: tr, pktConn: tr.Conn}
	manager.Unlock()
	if n := internet.TitanCloseTransports(); n != 1 {
		t.Fatalf("closed %d", n)
	}
	select {
	case <-conn.Context().Done():
	case <-time.After(5 * time.Second):
		t.Fatal("the pooled connection is open")
	}
	manager.Lock()
	if len(manager.m) != 0 {
		t.Fatal("the pool is not empty")
	}
	manager.m[dialerConf{MemoryStreamConfig: old}] = &client{}
	manager.m[dialerConf{MemoryStreamConfig: cur}] = &client{}
	manager.Unlock()
	time.Sleep(300 * time.Millisecond)
	manager.Lock()
	defer manager.Unlock()
	if _, ok := manager.m[dialerConf{MemoryStreamConfig: cur}]; !ok || len(manager.m) != 1 {
		t.Fatalf("pool after the sweep: %d", len(manager.m))
	}
}

// Streams whose readers stall (a paused or full-buffered player, a suspended app) next to one
// that is read (the video being watched), on a connection past Chrome's opening windows: an old
// one (read past its opening) holds its 1 MiB window at most, a new one Chrome's 6 MiB. With an
// 8 MiB connection window one old and one new (7 MiB) or two new ones froze the connection, and
// with it every stream of it (all TCP through that server); at 16 MiB two old and two new (14)
// froze it until quic-go updated the window before a quarter of it was read, and three new ones,
// or two new and four old, still filled all of it (quic_go.py). Now once the stalled streams hold
// more than three quarters of the window for 2 s, the one holding the most is cancelled (its
// server sees STOP_SENDING), so the others keep a quarter of it: the video reads at full speed
// next to every mix, and two new stalled streams (12 MiB) or one new and one old are left alone.
func TestTitanQUICStalled(t *testing.T) {
	ctx, cancel := context.WithTimeout(context.Background(), 5*time.Minute)
	defer cancel()
	var sent [16]atomic.Int64
	var stopped [16]atomic.Bool
	srv := titanQUICServer(t, ctx, sent[:], stopped[:])
	const oldRead = 8 << 20
	share := int64(internet.TitanQUICConnWindow - internet.TitanQUICConnWindow/4)
	for _, c := range []struct {
		name      string
		rtt       time.Duration
		old, new  int
		newRead   int // what each new one reads before it stalls
		cancelled int
	}{
		{"one old and one new", 0, 1, 1, 0, 0},
		{"two new", 0, 0, 2, 0, 0},
		{"two old and two new", 0, 2, 2, 0, 1},
		{"three new", 0, 0, 3, 0, 1},
		{"three new that read 1 MiB", 0, 0, 3, 1 << 20, 1},
		{"four old and two new", 0, 4, 2, 0, 1},
		{"ten old and one new", 0, 10, 1, 0, 1},
		{"two new at 150 ms", 150 * time.Millisecond, 0, 2, 0, 0},
		{"three old and two new at 150 ms", 150 * time.Millisecond, 3, 2, 0, 1},
		{"three new that read 1 MiB at 150 ms", 150 * time.Millisecond, 0, 3, 1 << 20, 1},
	} {
		// Xray's Hysteria defaults, then the caps.
		cfg := &quic.Config{ChromeParrot: true, InitialStreamReceiveWindow: 8 << 20, MaxStreamReceiveWindow: 8 << 20,
			InitialConnectionReceiveWindow: 20 << 20, MaxConnectionReceiveWindow: 20 << 20}
		internet.TitanQUICWindows(cfg)
		addr := srv
		if c.rtt > 0 {
			addr = titanUDPDelay(t, srv, c.rtt/2)
		}
		conn, tr := titanQUICDial(t, ctx, addr, cfg)
		for i := range sent {
			sent[i].Store(0)
			stopped[i].Store(false)
		}
		n := byte(0)
		open := func() *quic.Stream {
			str, err := conn.OpenStreamSync(ctx)
			if err != nil {
				t.Fatal(err)
			}
			str.Write([]byte{n})
			n++
			return str
		}
		// The video, read all along.
		video := open()
		var got atomic.Int64
		done := make(chan struct{})
		go func() {
			defer close(done)
			buf := make([]byte, 32<<10)
			for {
				k, err := video.Read(buf)
				got.Add(int64(k))
				if err != nil {
					return
				}
			}
		}()
		// Past Chrome's opening windows (15 MiB for the connection).
		for got.Load() < 20<<20 && ctx.Err() == nil {
			time.Sleep(10 * time.Millisecond)
		}
		// An old stream: read past its 6 MiB opening, then it stalls; a new one stalls at once,
		// or after newRead.
		for range c.old {
			if _, err := io.ReadFull(open(), make([]byte, oldRead)); err != nil {
				t.Fatal(err)
			}
		}
		for range c.new {
			if _, err := io.ReadFull(open(), make([]byte, c.newRead)); err != nil {
				t.Fatal(err)
			}
		}
		// They fill what they may, 2 s later the ones holding the most are cancelled.
		time.Sleep(3500 * time.Millisecond)
		before := got.Load()
		time.Sleep(2 * time.Second)
		flowed := got.Load() - before
		var held, most int64
		cancelled := 0
		for i := 1; i <= c.old+c.new; i++ {
			if stopped[i].Load() {
				cancelled++
				continue
			}
			h := sent[i].Load()
			if i <= c.old {
				h -= oldRead
			} else {
				h -= int64(c.newRead)
			}
			held += h
			most = max(most, h)
			limit := int64(6<<20 + 256<<10) // a new one: Chrome's opening
			if i <= c.old {
				limit = internet.TitanQUICStreamWindow + 256<<10
			}
			if h > limit {
				t.Fatalf("%s: stalled stream %d holds %d KiB", c.name, i, h>>10)
			}
		}
		video.SetReadDeadline(time.Now())
		<-done
		conn.CloseWithError(0, "")
		tr.Close()
		t.Logf("%s stalled: %d cancelled, the others hold %d KiB (%d at most), the video read %d KiB in 2 s",
			c.name, cancelled, held>>10, most>>10, flowed>>10)
		if cancelled != c.cancelled {
			t.Fatalf("%s: %d stalled streams cancelled, want %d", c.name, cancelled, c.cancelled)
		}
		if held > share+256<<10 {
			t.Fatalf("%s: stalled streams hold %d KiB", c.name, held>>10)
		}
		// At full speed: hundreds of MiB without delay, a stream window per RTT at 150 ms (13 MiB).
		want := int64(8 << 20)
		if c.rtt > 0 {
			want = 4 << 20
		}
		if flowed < want {
			t.Fatalf("%s stalled: the video read %d KiB in 2 s", c.name, flowed>>10)
		}
	}
}

// closeTransports doesn't wait for a client that dials (locked meanwhile, up to a handshake
// timeout): the cleaner, which waited for it under the pool's lock, cleans outside it.
func TestTitanQUICCloseDialing(t *testing.T) {
	internet.TitanCloseTransports() // the manager, as Dial makes it: its cleaner runs every second
	dialing := &client{}
	dialing.Lock()
	defer dialing.Unlock()
	manager.Lock()
	manager.m[dialerConf{MemoryStreamConfig: &internet.MemoryStreamConfig{}}] = dialing
	manager.Unlock()
	time.Sleep(idleCleanupInterval + 300*time.Millisecond) // the cleaner waits for it now
	closed := make(chan int, 1)
	go func() { closed <- internet.TitanCloseTransports() }()
	select {
	case n := <-closed:
		if n != 1 {
			t.Fatalf("closed %d", n)
		}
	case <-time.After(time.Second):
		t.Fatal("closeTransports waits for a client that dials")
	}
}

// titanQUICServer serves QUIC on localhost: each stream gets its number from the client first,
// then as much data as the client's windows let through, counted in sent; stopped (if given)
// says the client cancelled reading it (STOP_SENDING).
func titanQUICServer(t *testing.T, ctx context.Context, sent []atomic.Int64, stopped []atomic.Bool) *gonet.UDPAddr {
	key, _ := ecdsa.GenerateKey(elliptic.P256(), rand.Reader)
	der, err := x509.CreateCertificate(rand.Reader, &x509.Certificate{SerialNumber: big.NewInt(1), NotAfter: time.Now().Add(time.Hour)},
		&x509.Certificate{SerialNumber: big.NewInt(1)}, &key.PublicKey, key)
	if err != nil {
		t.Fatal(err)
	}
	ln, err := quic.ListenAddr("127.0.0.1:0", &gotls.Config{
		Certificates: []gotls.Certificate{{Certificate: [][]byte{der}, PrivateKey: key}}, NextProtos: []string{"titan"},
	}, &quic.Config{})
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() { ln.Close() })
	go func() {
		for {
			conn, err := ln.Accept(ctx)
			if err != nil {
				return
			}
			go func() {
				for {
					str, err := conn.AcceptStream(ctx)
					if err != nil {
						return
					}
					go func() {
						b := make([]byte, 1)
						if _, err := io.ReadFull(str, b); err != nil || int(b[0]) >= len(sent) {
							return
						}
						buf := make([]byte, 16<<10)
						for {
							n, err := str.Write(buf)
							sent[b[0]].Add(int64(n))
							var se *quic.StreamError
							if errors.As(err, &se) && se.Remote && stopped != nil {
								stopped[b[0]].Store(true)
							}
							if err != nil {
								return
							}
						}
					}()
				}
			}()
		}
	}()
	return ln.Addr().(*gonet.UDPAddr)
}

func titanQUICDial(t *testing.T, ctx context.Context, addr gonet.Addr, cfg *quic.Config) (*quic.Conn, *quic.Transport) {
	udp, err := gonet.ListenUDP("udp", &gonet.UDPAddr{IP: gonet.IPv4(127, 0, 0, 1)})
	if err != nil {
		t.Fatal(err)
	}
	udp.SetReadBuffer(16 << 20)
	tr := &quic.Transport{Conn: udp, ConnectionIDGenerator: quic.ZeroLengthConnectionIDGenerator{}}
	conn, err := tr.Dial(ctx, addr, &gotls.Config{InsecureSkipVerify: true, NextProtos: []string{"titan"}}, cfg)
	if err != nil {
		t.Fatal(err)
	}
	return conn, tr
}

// titanUDPDelay forwards one client's UDP datagrams to server and back, each way delay later (no
// bandwidth limit), and gives its own address.
func titanUDPDelay(t *testing.T, server *gonet.UDPAddr, delay time.Duration) *gonet.UDPAddr {
	lo := &gonet.UDPAddr{IP: gonet.IPv4(127, 0, 0, 1)}
	front, err := gonet.ListenUDP("udp", lo)
	if err != nil {
		t.Fatal(err)
	}
	back, err := gonet.ListenUDP("udp", lo)
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() { front.Close(); back.Close() })
	for _, c := range []*gonet.UDPConn{front, back} {
		c.SetReadBuffer(16 << 20)
		c.SetWriteBuffer(16 << 20)
	}
	type datagram struct {
		b  []byte
		at time.Time
	}
	var client atomic.Pointer[gonet.UDPAddr]
	forward := func(from *gonet.UDPConn, write func([]byte)) {
		ch := make(chan datagram, 1<<16)
		go func() {
			for d := range ch {
				time.Sleep(time.Until(d.at))
				write(d.b)
			}
		}()
		defer close(ch)
		for {
			b := make([]byte, 2048)
			n, addr, err := from.ReadFromUDP(b)
			if err != nil {
				return
			}
			if from == front {
				client.Store(addr)
			}
			ch <- datagram{b[:n], time.Now().Add(delay)}
		}
	}
	go forward(front, func(b []byte) { back.WriteToUDP(b, server) })
	go forward(back, func(b []byte) {
		if c := client.Load(); c != nil {
			front.WriteToUDP(b, c)
		}
	})
	return front.LocalAddr().(*gonet.UDPAddr)
}
