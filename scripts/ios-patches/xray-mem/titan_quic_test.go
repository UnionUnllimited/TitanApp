package hysteria

import (
	"context"
	"crypto/ecdsa"
	"crypto/elliptic"
	"crypto/rand"
	gotls "crypto/tls"
	"crypto/x509"
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
// of them after a restart) holds up to Chrome's 15 MiB: what the handshake gave.
func TestTitanQUICWindows(t *testing.T) {
	ctx, cancel := context.WithTimeout(context.Background(), 30*time.Second)
	defer cancel()
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
	defer ln.Close()
	// Each stream: its number first, then the server writes to it as fast as it may.
	var sent [10]atomic.Int64
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
							if err != nil {
								return
							}
						}
					}()
				}
			}()
		}
	}()
	dial := func(cfg *quic.Config) (*quic.Conn, *quic.Transport) {
		udp, err := gonet.ListenUDP("udp", &gonet.UDPAddr{IP: gonet.IPv4(127, 0, 0, 1)})
		if err != nil {
			t.Fatal(err)
		}
		tr := &quic.Transport{Conn: udp, ConnectionIDGenerator: quic.ZeroLengthConnectionIDGenerator{}}
		conn, err := tr.Dial(ctx, ln.Addr(), &gotls.Config{InsecureSkipVerify: true, NextProtos: []string{"titan"}}, cfg)
		if err != nil {
			t.Fatal(err)
		}
		return conn, tr
	}

	// A fresh connection, 3 streams never read: with Chrome parroting what the handshake gave
	// (6 MiB a stream, 15 the connection) at most, without it the caps from the start.
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
			stream, conns = 6<<20, 15<<20
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
