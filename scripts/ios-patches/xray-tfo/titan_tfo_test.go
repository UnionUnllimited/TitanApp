//go:build darwin

package internet

// Titan patch check, run on the macOS build machine: a tcpFastOpen dial carries data both
// ways, also when the host refuses TFO (plain fallback), and honours read deadlines; data
// not safe to repeat stays out of the SYN; the kernel's record of a TFO dial is readable.

import (
	"context"
	"encoding/json"
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

// titanServe answers every connection that sends n bytes; others (a stray probe
// connection) are just closed.
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

// The test payloads are raw bytes: marked safe to repeat, they go in the SYN.
var titanSafe = TitanTFOReplaySafe(context.Background())

func titanRoundTrip(t *testing.T, ctx context.Context, addr string) {
	start := time.Now()
	c, err := dialTFO(ctx, &stdnet.Dialer{Timeout: 5 * time.Second}, "tcp", addr)
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
		titanRoundTrip(t, titanSafe, ln.Addr().String())
	}
	_, broken := tfoBroken.Load(ln.Addr().String())
	t.Logf("TFO listener: %v, host marked broken: %v", lc.TFO(), broken)
	t.Log(TitanTFOLog())

	// The kernel's record of a TFO dial (TCP_CONNECTION_INFO) is there to read.
	kc, err := (&tfo.Dialer{Fallback: true}).DialContext(context.Background(), "tcp", ln.Addr().String(), []byte("hello"))
	if err != nil {
		t.Fatal(err)
	}
	k, ok := tfoKernel(kc)
	kc.Close()
	if !ok {
		t.Fatal("no kernel record of a TFO connection")
	}
	t.Logf("kernel: %+v", k)

	// Data not safe to repeat: dialed without TFO, still delivered.
	notSafe := tfoStat.notSafe.Load()
	titanRoundTrip(t, context.Background(), ln.Addr().String())
	if tfoStat.notSafe.Load() != notSafe+1 {
		t.Fatal("raw first write went the TFO way")
	}

	// A first write larger than a SYN takes: split, all of it arrives in order.
	big, _ := lc.Listen(context.Background(), "tcp", "127.0.0.1:0")
	defer big.Close()
	payload := make([]byte, 1500)
	for i := range payload {
		payload[i] = byte(i)
	}
	titanServe(big, len(payload), func(c stdnet.Conn, b []byte) { c.Write(b) })
	bc, err := dialTFO(titanSafe, &stdnet.Dialer{Timeout: 5 * time.Second}, "tcp", big.Addr().String())
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
	titanRoundTrip(t, titanSafe, plain.Addr().String())

	// A server that stays silent for a while (an XHTTP upload): the connection is kept.
	slow, _ := lc.Listen(context.Background(), "tcp", "127.0.0.1:0")
	defer slow.Close()
	titanServe(slow, 5, func(c stdnet.Conn, b []byte) {
		time.Sleep(2500 * time.Millisecond)
		c.Write(append([]byte("re:"), b...))
	})
	titanRoundTrip(t, titanSafe, slow.Addr().String())
	t.Log(TitanTFOLog())

	var stats map[string]any
	err = json.Unmarshal([]byte(TitanTFOStats()), &stats)
	if dials, _ := stats["dials"].(float64); err != nil || dials < 1 {
		t.Fatalf("stats: %v %v", stats, err)
	}
	t.Logf("stats: %v", TitanTFOStats())

	c, _ := dialTFO(titanSafe, &stdnet.Dialer{}, "tcp", ln.Addr().String())
	c.SetReadDeadline(time.Now().Add(50 * time.Millisecond))
	if _, err := c.Read(make([]byte, 1)); err == nil {
		t.Fatal("read deadline ignored")
	}
	c.Close()
}
