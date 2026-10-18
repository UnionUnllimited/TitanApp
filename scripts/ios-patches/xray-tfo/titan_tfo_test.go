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

// titanServe answers every connection that sends n bytes; others (the TFO warm-up's
// one byte) are just closed.
func titanServe(ln stdnet.Listener, n int, answer func(stdnet.Conn, []byte)) {
	go func() {
		for {
			c, err := ln.Accept()
			if err != nil {
				return
			}
			go func() {
				defer c.Close()
				b := make([]byte, n)
				if _, err := io.ReadFull(c, b); err == nil {
					answer(c, b)
				}
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

	// A first write larger than a SYN takes: split, all of it arrives in order.
	big, _ := lc.Listen(context.Background(), "tcp", "127.0.0.1:0")
	defer big.Close()
	payload := make([]byte, 1500)
	for i := range payload {
		payload[i] = byte(i)
	}
	titanServe(big, len(payload), func(c stdnet.Conn, b []byte) { c.Write(b) })
	bc, err := dialTFO(context.Background(), &stdnet.Dialer{Timeout: 5 * time.Second}, "tcp", big.Addr().String())
	if err != nil {
		t.Fatal(err)
	}
	bc.Write(payload)
	bc.SetReadDeadline(time.Now().Add(5 * time.Second))
	echo, _ := io.ReadAll(bc)
	bc.Close()
	if string(echo) != string(payload) {
		t.Fatalf("big write: got %d B back", len(echo))
	}

	plain, _ := stdnet.Listen("tcp", "127.0.0.1:0")
	defer plain.Close()
	titanEcho(t, plain)
	titanRoundTrip(t, plain.Addr().String())

	// A server that stays silent for a while (an XHTTP upload): the connection is kept.
	slow, _ := lc.Listen(context.Background(), "tcp", "127.0.0.1:0")
	defer slow.Close()
	titanServe(slow, 5, func(c stdnet.Conn, b []byte) {
		time.Sleep(2500 * time.Millisecond)
		c.Write(append([]byte("re:"), b...))
	})
	titanRoundTrip(t, slow.Addr().String())
	t.Log(TitanTFOLog())

	c, _ := dialTFO(context.Background(), &stdnet.Dialer{}, "tcp", ln.Addr().String())
	c.SetReadDeadline(time.Now().Add(50 * time.Millisecond))
	if _, err := c.Read(make([]byte, 1)); err == nil {
		t.Fatal("read deadline ignored")
	}
	c.Close()
}
