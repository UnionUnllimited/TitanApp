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

	"github.com/xtls/xray-core/common"
	"github.com/xtls/xray-core/common/buf"
	"github.com/xtls/xray-core/common/net"
	"github.com/xtls/xray-core/features/outbound"
	"github.com/xtls/xray-core/testing/servers/tcp"
	"github.com/xtls/xray-core/transport"
	"github.com/xtls/xray-core/transport/internet"
	"github.com/xtls/xray-core/transport/internet/stat"
	"golang.org/x/net/http2"
	"google.golang.org/grpc"
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

// titanGRPCFlow dials a user's proxied connection through ss and checks it echoes. Closed, it
// ends as an outbound's does: its context too (the flow's, or the server's end read by its
// downlink).
func titanGRPCFlow(dest net.Destination, ss *internet.MemoryStreamConfig, open bool) (gonet.Conn, func(), error) {
	return titanGRPCFlowCtx(context.Background(), dest, ss, open)
}

// titanGRPCProbe is an observatory probe's flow through ss (its context marked as the probes' are),
// closed once it has echoed.
func titanGRPCProbe(dest net.Destination, ss *internet.MemoryStreamConfig) error {
	_, _, err := titanGRPCFlowCtx(internet.TitanProbeContext(context.Background()), dest, ss, false)
	return err
}

func titanGRPCFlowCtx(parent context.Context, dest net.Destination, ss *internet.MemoryStreamConfig, open bool) (gonet.Conn, func(), error) {
	ctx, cancel := context.WithCancel(parent)
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

// The pool's ClientConn of ss (nil: none).
func titanGRPCPooled(dest net.Destination, ss *internet.MemoryStreamConfig) *grpc.ClientConn {
	globalDialerAccess.Lock()
	defer globalDialerAccess.Unlock()
	return globalDialerMap[dialerConf{dest, ss}]
}

func titanGRPCHeap() int64 {
	runtime.GC()
	runtime.GC()
	s := []metrics.Sample{{Name: "/gc/heap/live:bytes"}}
	metrics.Read(s)
	return int64(s[0].Value.Uint64())
}

// Probe rounds on subjects that carry no traffic: each probe gets a ClientConn of its own, not
// the pool's, and closes it (with its transport) as it ends, however long internet.TitanIdleAfter
// is (an hour here: the pool's rule, which the probes don't meet), so between rounds nothing is
// pooled or open and the live heap comes back to the same level; the next probe connects anew.
// Before, every ClientConn stayed READY in the pool between probes (grpc-go's 30 min, then a
// minute: the same flows as a user's here, with an hour).
func TestTitanGRPCIdleProbes(t *testing.T) {
	defer func(d time.Duration) { internet.TitanIdleAfter = d }(internet.TitanIdleAfter)
	internet.TitanCloseTransports()
	internet.TitanIdleAfter = time.Hour
	const n, rounds = 10, 10
	dest, subjects := titanGRPCServer(t, 2*n)
	base := titanGRPCHeap()
	for _, ss := range subjects[n:] {
		if _, _, err := titanGRPCFlow(dest, ss, false); err != nil {
			t.Fatal(err)
		}
	}
	time.Sleep(500 * time.Millisecond)
	keptStates, keptHeap := titanGRPCStates(), titanGRPCHeap()-base
	t.Logf("kept as a user's: %v, %d KiB of live heap (%d KiB a subject, both ends)", keptStates, keptHeap>>10, keptHeap>>10/n)
	if keptStates[connectivity.Ready] != n {
		t.Fatalf("kept as a user's: %v", keptStates)
	}
	internet.TitanCloseTransports()
	time.Sleep(500 * time.Millisecond)
	base = titanGRPCHeap()

	var quiet []int64
	for r := range rounds {
		for _, ss := range subjects[:n] {
			if err := titanGRPCProbe(dest, ss); err != nil {
				t.Fatal(err)
			}
		}
		globalDialerAccess.Lock()
		pooled, probes := len(globalDialerMap), len(titanProbeConns)
		globalDialerAccess.Unlock()
		if pooled != 0 || probes != 0 {
			t.Fatalf("round %d: %d ClientConns pooled, %d of the probes' open after them", r, pooled, probes)
		}
		time.Sleep(300 * time.Millisecond) // the closes (in goroutines), the server's ends
		h := titanGRPCHeap() - base
		quiet = append(quiet, h)
		t.Logf("round %2d: none pooled or open after the probes; live heap %+5d KiB", r, h>>10)
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
// stream is an RPC: grpc-go never goes idle under one) while probes come and go on its subject
// and others, and still carries data after. The probes don't touch the pool (each a ClientConn
// of its own, closed as it ends), on the user's subject too. Once the user's flow ends its
// ClientConn goes IDLE internet.TitanIdleAfter later (grpc-go's timer: one timeout after its last
// RPC ended; checked at twice that), the probes meanwhile notwithstanding. Flows that start as a
// ClientConn goes idle (user flows and probes on one subject) all connect.
func TestTitanGRPCIdleUser(t *testing.T) {
	defer func(d time.Duration) { internet.TitanIdleAfter = d }(internet.TitanIdleAfter)
	internet.TitanCloseTransports()
	internet.TitanIdleAfter = 600 * time.Millisecond
	dest, subjects := titanGRPCServer(t, 3)
	user, end, err := titanGRPCFlow(dest, subjects[0], true)
	if err != nil {
		t.Fatal(err)
	}
	defer end()
	mine := titanGRPCPooled(dest, subjects[0])
	probes := func() {
		for _, ss := range subjects {
			if err := titanGRPCProbe(dest, ss); err != nil {
				t.Fatal(err)
			}
		}
		globalDialerAccess.Lock()
		pooled, open := len(globalDialerMap), len(titanProbeConns)
		globalDialerAccess.Unlock()
		if pooled != 1 || open != 0 {
			t.Fatalf("%d ClientConns pooled (the user's only), %d of the probes' open after them", pooled, open)
		}
		if c := titanGRPCPooled(dest, subjects[0]); c != mine {
			t.Fatalf("the user's subject's ClientConn %p, was %p", c, mine)
		}
	}
	for range 20 {
		probes()
		time.Sleep(internet.TitanIdleAfter / 2)
		if s := mine.GetState(); s != connectivity.Ready {
			t.Fatalf("the user's ClientConn %v", s)
		}
	}
	if err := titanGRPCEcho(user); err != nil {
		t.Fatalf("the user's connection after %v idle: %v", 10*internet.TitanIdleAfter, err)
	}

	// The user's flow ends: its ClientConn goes IDLE TitanIdleAfter later, the probes don't touch it.
	end()
	ended := time.Now()
	for _, at := range []time.Duration{internet.TitanIdleAfter / 4, internet.TitanIdleAfter * 3 / 4} {
		time.Sleep(time.Until(ended.Add(at)))
		probes()
	}
	time.Sleep(time.Until(ended.Add(2*internet.TitanIdleAfter + 300*time.Millisecond)))
	if s := mine.GetState(); s != connectivity.Idle {
		t.Fatalf("the user's ClientConn %v %v after its last flow", s, time.Since(ended))
	}

	internet.TitanIdleAfter = 50 * time.Millisecond
	rs := &internet.MemoryStreamConfig{ProtocolName: "grpc", ProtocolSettings: &Config{ServiceName: "titan", MultiMode: true}}
	var wg sync.WaitGroup
	var failed atomic.Int32
	for g := range 4 {
		wg.Add(1)
		go func() {
			defer wg.Done()
			for k := range 40 {
				time.Sleep(time.Duration(30+rand.IntN(70)) * time.Millisecond)
				var err error
				if (g+k)%2 == 0 {
					err = titanGRPCProbe(dest, rs)
				} else {
					_, _, err = titanGRPCFlow(dest, rs, false)
				}
				if err != nil {
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

// A subject chained through a dialerProxy hop (sockopt.dialerProxy: DialSystem hands the
// ClientConn's TCP to the hop's outbound): a probe's ClientConn dials the hop with the probe's
// mark (grpc-go's dial context gets it with the flow's other values), so the hop's transport
// gives the probe a connection of its own too, not one of its pool's; a user's flow reaches the
// hop unmarked. Without the mark the hop's XHTTP pooled every probe's connection (XMUX: 3 a hop),
// warm for the next probe.
func TestTitanGRPCProbeHop(t *testing.T) {
	internet.TitanCloseTransports()
	defer internet.TitanCloseTransports()
	dest, _ := titanGRPCServer(t, 0)
	hop := &titanHop{addr: fmt.Sprintf("127.0.0.1:%d", dest.Port), marks: make(chan bool, 16)}
	m := titanHopManager{hop: hop}
	ss := &internet.MemoryStreamConfig{
		ProtocolName:     "grpc",
		ProtocolSettings: &Config{ServiceName: "titan", MultiMode: true},
		SocketSettings:   &internet.SocketConfig{DialerProxy: "hop"},
	}
	flow := func(name string, ctx context.Context, probe bool) {
		if _, _, err := titanGRPCFlowCtx(internet.TitanWithOutboundManager(ctx, m), dest, ss, false); err != nil {
			t.Fatalf("%s: %v", name, err)
		}
		select {
		case marked := <-hop.marks:
			if marked != probe {
				t.Fatalf("%s: the hop saw the probe's mark: %v", name, marked)
			}
		default:
			t.Fatalf("%s: not through the hop", name)
		}
	}
	probeCtx := internet.TitanProbeContext(context.Background())
	flow("a probe", probeCtx, true)
	flow("a user's flow", context.Background(), false)
	flow("a probe next to the user's ClientConn", probeCtx, true)
	select {
	case marked := <-hop.marks:
		t.Fatalf("one more dial through the hop (marked %v)", marked)
	default:
	}
}

// titanHopManager is the dialing instance's outbound manager (TitanWithOutboundManager) with
// the hop; DialSystem calls only GetHandler.
type titanHopManager struct {
	outbound.Manager
	hop outbound.Handler
}

func (m titanHopManager) GetHandler(tag string) outbound.Handler {
	if tag == "hop" {
		return m.hop
	}
	return nil
}

// titanHop is a dialerProxy hop's outbound: it says whether each flow it gets is a probe's and
// forwards it over TCP to addr. redirect calls only Dispatch.
type titanHop struct {
	outbound.Handler
	addr  string
	marks chan bool
}

func (h *titanHop) Dispatch(ctx context.Context, link *transport.Link) {
	h.marks <- internet.TitanProbe(ctx)
	conn, err := gonet.Dial("tcp", h.addr)
	if err != nil {
		common.Interrupt(link.Reader)
		common.Interrupt(link.Writer)
		return
	}
	defer conn.Close()
	go func() {
		buf.Copy(link.Reader, buf.NewWriter(conn))
		conn.(*gonet.TCPConn).CloseWrite()
	}()
	buf.Copy(buf.NewReader(conn), link.Writer)
	common.Interrupt(link.Writer)
}
