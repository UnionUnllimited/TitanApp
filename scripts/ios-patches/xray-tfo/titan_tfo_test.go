//go:build darwin

package internet

// Titan patch check, run on the macOS build machine: a tcpFastOpen dial carries data both
// ways, also when the host refuses TFO (plain fallback), and honours read deadlines.

import (
	"context"
	"io"
	stdnet "net"
	"testing"
	"time"

	"github.com/database64128/tfo-go/v2"
)

func titanEcho(t *testing.T, ln stdnet.Listener) {
	go func() {
		for {
			c, err := ln.Accept()
			if err != nil {
				return
			}
			go func() {
				b := make([]byte, 5)
				io.ReadFull(c, b)
				c.Write(append([]byte("re:"), b...))
				c.Close()
			}()
		}
	}()
}

func titanRoundTrip(t *testing.T, addr string) {
	start := time.Now()
	c, err := dialTFO(context.Background(), &stdnet.Dialer{Timeout: 5 * time.Second}, "tcp", addr)
	if err != nil {
		t.Fatal(err)
	}
	defer c.Close()
	if _, err := c.Write([]byte("hello")); err != nil {
		t.Fatal("write: ", err)
	}
	c.SetReadDeadline(time.Now().Add(5 * time.Second))
	b, err := io.ReadAll(c)
	if string(b) != "re:hello" {
		t.Fatalf("got %q, %v", b, err)
	}
	t.Logf("%s: %v", addr, time.Since(start))
}

func TestTitanTFO(t *testing.T) {
	lc := tfo.ListenConfig{Fallback: true}
	ln, err := lc.Listen(context.Background(), "tcp", "127.0.0.1:0")
	if err != nil {
		t.Fatal(err)
	}
	defer ln.Close()
	titanEcho(t, ln)
	for i := 0; i < 3; i++ {
		titanRoundTrip(t, ln.Addr().String())
	}
	_, broken := tfoBroken.Load(ln.Addr().String())
	t.Logf("TFO listener: %v, host marked broken: %v", lc.TFO(), broken)

	plain, _ := stdnet.Listen("tcp", "127.0.0.1:0")
	defer plain.Close()
	titanEcho(t, plain)
	titanRoundTrip(t, plain.Addr().String())

	// A server that never answers the first connection: the data goes again, plainly.
	silent, _ := lc.Listen(context.Background(), "tcp", "127.0.0.1:0")
	defer silent.Close()
	go func() {
		first, _ := silent.Accept()
		defer first.Close()
		c, _ := silent.Accept()
		b := make([]byte, 5)
		io.ReadFull(c, b)
		c.Write(append([]byte("re:"), b...))
		c.Close()
	}()
	titanRoundTrip(t, silent.Addr().String())
	if _, broken := tfoBroken.Load(silent.Addr().String()); !broken {
		t.Fatal("silent host not marked")
	}
	t.Log(TitanTFOLog())

	c, _ := dialTFO(context.Background(), &stdnet.Dialer{}, "tcp", ln.Addr().String())
	c.SetReadDeadline(time.Now().Add(50 * time.Millisecond))
	if _, err := c.Read(make([]byte, 1)); err == nil {
		t.Fatal("read deadline ignored")
	}
	c.Close()
}
