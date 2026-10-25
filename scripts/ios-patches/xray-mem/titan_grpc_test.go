package grpc

import (
	"bytes"
	"context"
	"errors"
	"fmt"
	"io"
	"math/rand/v2"
	gonet "net"
	"runtime"
	"runtime/metrics"
	"slices"
	"sync"
	"sync/atomic"
	"testing"
	"time"

	"github.com/xtls/xray-core/common/net"
	"github.com/xtls/xray-core/testing/servers/tcp"
	"github.com/xtls/xray-core/transport/internet"
	"github.com/xtls/xray-core/transport/internet/stat"
	"golang.org/x/net/http2"
	"google.golang.org/grpc/connectivity"
)

// A pooled ClientConn has the fixed windows, and titanCloseAll shuts it and empties the pool
// (xray-mem patch).
func TestTitanGRPC(t *testing.T) {
	ln, err := gonet.Listen("tcp", "127.0.0.1:0")
	if err != nil {
		t.Fatal(err)
	}
	defer ln.Close()
	type report struct {
		window, connWindow uint32
		closed             bool
		err                error
	}
	reports := make(chan report, 2)
	go func() {
		var r report
		conn, err := ln.Accept()
		if err != nil {
			reports <- report{err: err}
			return
		}
		defer conn.Close()
		if _, err := io.ReadFull(conn, make([]byte, len(http2.ClientPreface))); err != nil {
			reports <- report{err: err}
			return
		}
		fr := http2.NewFramer(conn, conn)
		fr.WriteSettings()
		for r.window == 0 || r.connWindow == 0 {
			f, err := fr.ReadFrame()
			if err != nil {
				reports <- report{err: err}
				return
			}
			switch f := f.(type) {
			case *http2.SettingsFrame:
				if !f.IsAck() {
					r.window, _ = f.Value(http2.SettingInitialWindowSize)
					fr.WriteSettingsAck()
				}
			case *http2.WindowUpdateFrame:
				if f.StreamID == 0 {
					r.connWindow = f.Increment
				}
			}
		}
		reports <- r
		conn.SetReadDeadline(time.Now().Add(5 * time.Second))
		for {
			if _, err := fr.ReadFrame(); err != nil {
				// EOF, or a reset if the client had unread bytes; not the deadline.
				var ne gonet.Error
				reports <- report{closed: !errors.As(err, &ne) || !ne.Timeout()}
				return
			}
		}
	}()

	addr := ln.Addr().(*gonet.TCPAddr)
	dest := net.TCPDestination(net.LocalHostIP, net.Port(addr.Port))
	ss := &internet.MemoryStreamConfig{ProtocolName: "grpc", ProtocolSettings: &Config{ServiceName: "titan", MultiMode: true}}
	conn, err := getGrpcClient(context.Background(), dest, ss)
	if err != nil {
		t.Fatal(err)
	}
	var r report
	select {
	case r = <-reports:
	case <-time.After(10 * time.Second):
		t.Fatal("no connection")
	}
	if r.err != nil {
		t.Fatal(r.err)
	}
	// The connection's window: the initial 64 KiB and this.
	if r.window != titanStreamWindow || r.connWindow != titanConnWindow-65535 {
		t.Fatalf("stream window %d, connection window +%d", r.window, r.connWindow)
	}
	if n := internet.TitanCloseTransports(); n != 1 {
		t.Fatalf("closed %d", n)
	}
	globalDialerAccess.Lock()
	left := len(globalDialerMap)
	globalDialerAccess.Unlock()
	if left != 0 {
		t.Fatalf("%d left in the pool", left)
	}
	if r = <-reports; !r.closed {
		t.Fatal("the connection is still open")
	}
	if s := conn.GetState(); s != connectivity.Shutdown {
		t.Fatalf("ClientConn %v", s)
	}
}

// A gRPC (multiMode) server on localhost that echoes each proxied connection, and the stream
// settings of n subjects dialing it (each its own ClientConn, as each outbound has). In this
// process: grpc-go's server ends a connection's state with it.
func titanGRPCServer(t *testing.T, n int) (net.Destination, []*internet.MemoryStreamConfig) {
	port := tcp.PickPort()
	ln, err := Listen(context.Background(), net.LocalHostIP, port, &internet.MemoryStreamConfig{
		ProtocolName: "grpc", ProtocolSettings: &Config{ServiceName: "titan", MultiMode: true},
	}, func(conn stat.Connection) {
		go func() {
			defer conn.Close()
			io.Copy(conn, conn)
		}()
	})
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() { ln.Close() })
	for i := 0; ; i++ {
		c, err := gonet.Dial("tcp", fmt.Sprintf("127.0.0.1:%d", port))
		if err == nil {
			c.Close()
			break
		}
		if i == 100 {
			t.Fatal(err)
		}
		time.Sleep(20 * time.Millisecond)
	}
	subjects := make([]*internet.MemoryStreamConfig, n)
	for i := range subjects {
		subjects[i] = &internet.MemoryStreamConfig{ProtocolName: "grpc", ProtocolSettings: &Config{ServiceName: "titan", MultiMode: true}}
	}
	return net.TCPDestination(net.LocalHostIP, port), subjects
}

// titanGRPCFlow dials a proxied connection through ss and checks it echoes. Closed, it ends as an
// outbound's does: its context too (the flow's, or the server's end read by its downlink).
func titanGRPCFlow(dest net.Destination, ss *internet.MemoryStreamConfig, open bool) (gonet.Conn, func(), error) {
	ctx, cancel := context.WithCancel(context.Background())
	type result struct {
		conn gonet.Conn
		err  error
	}
	done := make(chan result, 1)
	go func() {
		conn, err := Dial(ctx, dest, ss)
		if err == nil {
			err = titanGRPCEcho(conn)
		}
		if err != nil || !open {
			if conn != nil {
				conn.Close()
			}
			cancel()
			conn = nil
		}
		done <- result{conn, err}
	}()
	select {
	case r := <-done:
		return r.conn, func() { r.conn.Close(); cancel() }, r.err
	case <-time.After(10 * time.Second):
		cancel()
		return nil, nil, errors.New("no echo in 10 s")
	}
}

func titanGRPCEcho(conn gonet.Conn) error {
	msg := fmt.Appendf(nil, "titan %d", rand.Int())
	if _, err := conn.Write(msg); err != nil {
		return err
	}
	got := make([]byte, len(msg))
	if _, err := io.ReadFull(conn, got); err != nil {
		return err
	}
	if !bytes.Equal(got, msg) {
		return fmt.Errorf("echo %q, sent %q", got, msg)
	}
	return nil
}

// The pooled ClientConns in each state.
func titanGRPCStates() map[connectivity.State]int {
	globalDialerAccess.Lock()
	defer globalDialerAccess.Unlock()
	out := map[connectivity.State]int{}
	for _, c := range globalDialerMap {
		if c != nil {
			out[c.GetState()]++
		}
	}
	return out
}

func titanGRPCHeap() int64 {
	runtime.GC()
	runtime.GC()
	s := []metrics.Sample{{Name: "/gc/heap/live:bytes"}}
	metrics.Read(s)
	return int64(s[0].Value.Uint64())
}

// Probe rounds on subjects that carry no traffic: with internet.TitanIdleAfter each ClientConn
// goes IDLE that long after its probe and drops its connection, so between rounds none is
// connected and the live heap comes back to the same level; the next probe connects again.
// Before (grpc-go's 30 min) every ClientConn stayed READY between probes (an hour here).
func TestTitanGRPCIdleProbes(t *testing.T) {
	defer func(d time.Duration) { internet.TitanIdleAfter = d }(internet.TitanIdleAfter)
	internet.TitanCloseTransports()
	const n, rounds = 10, 10
	dest, subjects := titanGRPCServer(t, 2*n)
	probe := func(subjects []*internet.MemoryStreamConfig) {
		for _, ss := range subjects {
			if _, _, err := titanGRPCFlow(dest, ss, false); err != nil {
				t.Fatal(err)
			}
		}
	}
	base := titanGRPCHeap()
	internet.TitanIdleAfter = time.Hour
	probe(subjects[n:])
	time.Sleep(500 * time.Millisecond)
	keptStates, keptHeap := titanGRPCStates(), titanGRPCHeap()-base
	t.Logf("idle kept: %v, %d KiB of live heap (%d KiB a subject, both ends)", keptStates, keptHeap>>10, keptHeap>>10/n)
	if keptStates[connectivity.Ready] != n {
		t.Fatalf("idle kept: %v", keptStates)
	}
	internet.TitanCloseTransports()
	time.Sleep(500 * time.Millisecond)
	base = titanGRPCHeap()

	internet.TitanIdleAfter = 300 * time.Millisecond
	var quiet []int64
	for r := range rounds {
		probe(subjects[:n])
		busy := titanGRPCStates()
		// grpc-go's idle timer: up to twice the timeout after the last RPC.
		time.Sleep(2*internet.TitanIdleAfter + 300*time.Millisecond)
		states := titanGRPCStates()
		if states[connectivity.Idle] != n {
			t.Fatalf("round %d: %v %v after the probes", r, states, 2*internet.TitanIdleAfter+300*time.Millisecond)
		}
		h := titanGRPCHeap() - base
		quiet = append(quiet, h)
		t.Logf("round %2d: %v right after the probes, all idle after; live heap %+5d KiB", r, busy, h>>10)
	}
	first, last := max(quiet[1], quiet[2], quiet[3]), max(quiet[rounds-3], quiet[rounds-2], quiet[rounds-1])
	step := titanSteadyStep(quiet, 1)
	t.Logf("live heap %+d KiB in rounds 1-3, %+d KiB in the last ones, %+d KiB a round from round 1 on (median)",
		first>>10, last>>10, step>>10)
	if last > first+128<<10 || last > keptHeap/2 {
		t.Fatalf("live heap +%d KiB in rounds 1-3, +%d KiB in the last ones (kept: +%d)", first>>10, last>>10, keptHeap>>10)
	}
	if step > 24<<10 {
		t.Fatalf("live heap +%d KiB a round from round 1 on (median)", step>>10)
	}
}

// titanSteadyStep is the median change of the live heap from one probe round to the next, from
// round from on: what each round keeps. Unlike the last rounds against the first ones, a level
// that falls once meanwhile (what an earlier test left, freed late) moves it one step at most,
// so it can't hide growth.
func titanSteadyStep(quiet []int64, from int) int64 {
	steps := make([]int64, 0, len(quiet))
	for i := from + 1; i < len(quiet); i++ {
		steps = append(steps, quiet[i]-quiet[i-1])
	}
	slices.Sort(steps)
	return (steps[(len(steps)-1)/2] + steps[len(steps)/2]) / 2
}

// A proxied connection that sends nothing for many idle periods keeps its ClientConn READY (its
// stream is an RPC: grpc-go never goes idle under one) while probes come and go on it and on other
// subjects, and still carries data after; flows that start as a ClientConn goes idle all connect.
func TestTitanGRPCIdleUser(t *testing.T) {
	defer func(d time.Duration) { internet.TitanIdleAfter = d }(internet.TitanIdleAfter)
	internet.TitanCloseTransports()
	internet.TitanIdleAfter = 300 * time.Millisecond
	dest, subjects := titanGRPCServer(t, 3)
	user, end, err := titanGRPCFlow(dest, subjects[0], true)
	if err != nil {
		t.Fatal(err)
	}
	defer end()
	globalDialerAccess.Lock()
	mine := globalDialerMap[dialerConf{dest, subjects[0]}]
	globalDialerAccess.Unlock()
	for i := range 20 {
		for _, ss := range subjects {
			if _, _, err := titanGRPCFlow(dest, ss, false); err != nil {
				t.Fatal(err)
			}
		}
		time.Sleep(internet.TitanIdleAfter / 2)
		if s := mine.GetState(); s != connectivity.Ready {
			t.Fatalf("the user's ClientConn %v", s)
		}
		if i%5 == 4 {
			time.Sleep(3 * internet.TitanIdleAfter)
			if s := titanGRPCStates(); s[connectivity.Idle] != 2 {
				t.Fatalf("probe-only ClientConns %v after their probes", s)
			}
		}
	}
	if err := titanGRPCEcho(user); err != nil {
		t.Fatalf("the user's connection after %v idle: %v", 20*internet.TitanIdleAfter, err)
	}

	internet.TitanIdleAfter = 50 * time.Millisecond
	rs := &internet.MemoryStreamConfig{ProtocolName: "grpc", ProtocolSettings: &Config{ServiceName: "titan", MultiMode: true}}
	var wg sync.WaitGroup
	var failed atomic.Int32
	for range 4 {
		wg.Add(1)
		go func() {
			defer wg.Done()
			for range 40 {
				time.Sleep(time.Duration(30+rand.IntN(70)) * time.Millisecond)
				if _, _, err := titanGRPCFlow(dest, rs, false); err != nil {
					failed.Add(1)
					t.Error(err)
				}
			}
		}()
	}
	wg.Wait()
	if failed.Load() > 0 {
		t.Fatalf("%d of 160 flows failed", failed.Load())
	}
}
