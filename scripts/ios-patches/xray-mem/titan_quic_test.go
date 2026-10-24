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
// quic-go patched by quic_go.py).
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
	var sent atomic.Int64
	go func() {
		conn, err := ln.Accept(ctx)
		if err != nil {
			return
		}
		str, err := conn.AcceptStream(ctx)
		if err != nil {
			return
		}
		str.Read(make([]byte, 1))
		buf := make([]byte, 16<<10)
		for {
			n, err := str.Write(buf)
			sent.Add(int64(n))
			if err != nil {
				return
			}
		}
	}()

	// Xray's Hysteria defaults (8 / 20 MiB), then the caps.
	cfg := &quic.Config{ChromeParrot: true, InitialStreamReceiveWindow: 8 << 20, MaxStreamReceiveWindow: 8 << 20,
		InitialConnectionReceiveWindow: 20 << 20, MaxConnectionReceiveWindow: 20 << 20}
	internet.TitanQUICWindows(cfg)
	if cfg.MaxStreamReceiveWindow != internet.TitanQUICStreamWindow || cfg.MaxConnectionReceiveWindow != internet.TitanQUICConnWindow {
		t.Fatalf("windows %d / %d", cfg.MaxStreamReceiveWindow, cfg.MaxConnectionReceiveWindow)
	}
	udp, err := gonet.ListenUDP("udp", &gonet.UDPAddr{IP: gonet.IPv4(127, 0, 0, 1)})
	if err != nil {
		t.Fatal(err)
	}
	tr := &quic.Transport{Conn: udp, ConnectionIDGenerator: quic.ZeroLengthConnectionIDGenerator{}}
	defer tr.Close()
	conn, err := tr.Dial(ctx, ln.Addr(), &gotls.Config{InsecureSkipVerify: true, NextProtos: []string{"titan"}}, cfg)
	if err != nil {
		t.Fatal(err)
	}
	defer conn.CloseWithError(0, "")
	str, err := conn.OpenStreamSync(ctx)
	if err != nil {
		t.Fatal(err)
	}
	str.Write([]byte{1})
	// Past Chrome's opening windows (15 MiB for the connection); then the reader stalls.
	const read = 16 << 20
	if _, err := io.ReadFull(str, make([]byte, read)); err != nil {
		t.Fatal(err)
	}
	time.Sleep(time.Second)
	if unread := sent.Load() - read; unread > 2<<20 {
		t.Fatalf("%d KiB sent and not read", unread>>10)
	} else {
		t.Logf("%d KiB sent and not read", unread>>10)
	}

	// Pooled, it is closed by titanCloseAll; the sweep later closes what the old stream
	// settings pooled meanwhile, and only that.
	internet.TitanSweepAfter = 100 * time.Millisecond
	defer func() { internet.TitanSweepAfter = 90 * time.Second }()
	internet.TitanCloseTransports() // the manager, as Dial makes it
	old, cur := &internet.MemoryStreamConfig{}, &internet.MemoryStreamConfig{}
	manager.Lock()
	manager.m[dialerConf{MemoryStreamConfig: old}] = &client{conn: conn, tr: tr, pktConn: udp}
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
