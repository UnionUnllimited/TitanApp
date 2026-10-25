package internet_test

// Titan patch, iOS memory (scripts/ios-patches/xray-mem): copied into transport/internet.

import (
	"bytes"
	"context"
	"fmt"
	"io"
	"math/rand/v2"
	gonet "net"
	"slices"
	"sync"
	"sync/atomic"
	"testing"
	"time"

	"github.com/xtls/xray-core/app/observatory/burst"
	"github.com/xtls/xray-core/common/net"
	"github.com/xtls/xray-core/common/protocol/tls/cert"
	"github.com/xtls/xray-core/testing/servers/tcp"
	"github.com/xtls/xray-core/testing/servers/udp"
	"github.com/xtls/xray-core/transport/internet"
	xgrpc "github.com/xtls/xray-core/transport/internet/grpc"
	"github.com/xtls/xray-core/transport/internet/hysteria"
	"github.com/xtls/xray-core/transport/internet/splithttp"
	"github.com/xtls/xray-core/transport/internet/stat"
	"github.com/xtls/xray-core/transport/internet/tls"
)

// What leastLoad ranks by when the burst observatory probes subjects of the pooled transports
// (XHTTP, gRPC, Hysteria): the deviation of each subject's last probes.
// The observatory's schedule (HealthPing.doCheck: sampling probes at random offsets in each
// window of interval × sampling), each probe timed from its dial to its echo (MeasureDelay times
// http.Client.Do through the outbound, its transport's dial with it), the deviation that of the
// last sampling samples as the observatory keeps them (burst.HealthPingRTTS). Time at 1:100:
// interval 600 ms (1 min, × 5), internet.TitanIdleAfter 600 ms (60 s). Through a delay line, 5 ms
// each way and 60 ms more for each new connection (a cold dial's handshake), four groups of 8
// subjects at once:
//   - probes: the observatory's flows (internet.TitanProbeContext), as the tunnel has them now;
//   - probes next to a user: the same, on subjects that each carry a user's flow all along (the
//     balancer's pick: its pooled connection up);
//   - kept a minute: the probes' flows counted as a user's, each keeping its subject's pooled
//     connection internet.TitanIdleAfter after it: what the probes did before (60 s next to probes
//     about a minute apart: a random mix of warm and cold ones);
//   - always cold: the probes, each on a subject of its own (a pool entry with nothing to reuse).
//
// The probes must be cold every time, next to a user's flow too (a pick's probes warm and the
// others' cold made the pick's samples switch from warm to cold as it stopped being the pick, and
// back: in the review's model leastLoad then changed its pick 1.7-1.8 times as often), and their
// deviation the always cold one's; the mix's (where there is one) must be well over it (what
// the test is there to catch).
func TestTitanProbeDeviation(t *testing.T) {
	defer func(d time.Duration) { internet.TitanIdleAfter = d }(internet.TitanIdleAfter)
	const (
		oneWay    = 5 * time.Millisecond
		handshake = 60 * time.Millisecond
		interval  = 600 * time.Millisecond
		sampling  = 5
		windows   = 5
		subjects  = 8
	)
	internet.TitanIdleAfter = interval
	for _, tr := range []struct {
		name  string
		setup func(t *testing.T, oneWay, handshake time.Duration) (net.Destination, func() *internet.MemoryStreamConfig)
		dial  func(ctx context.Context, dest net.Destination, ss *internet.MemoryStreamConfig) (stat.Connection, error)
	}{
		{"XHTTP", titanXHTTPServer, splithttp.Dial},
		{"gRPC", titanGRPCServer, xgrpc.Dial},
		{"Hysteria", titanHyServer, hysteria.Dial},
	} {
		t.Run(tr.name, func(t *testing.T) {
			internet.TitanCloseTransports()
			defer internet.TitanCloseTransports()
			dest, subject := tr.setup(t, oneWay, handshake)
			probeCtx := internet.TitanProbeContext(context.Background())
			groups := []*titanGroup{
				{name: "probes", ctx: probeCtx},
				{name: "next to a user", ctx: probeCtx, user: true},
				{name: "kept a minute", ctx: context.Background()},
				{name: "always cold", ctx: probeCtx, fresh: true},
			}
			start := time.Now().Add(100 * time.Millisecond)
			window := interval * sampling
			var wg sync.WaitGroup
			for _, g := range groups {
				for range subjects {
					ss := subject()
					if g.user {
						flow, err := tr.dial(context.Background(), dest, ss)
						if err == nil {
							_, err = titanEcho(flow)
						}
						if err != nil {
							t.Fatalf("a user's flow: %v", err)
						}
						defer flow.Close()
					}
					rtts := burst.NewHealthPingResult(sampling, 2*window)
					wg.Add(1)
					go func() {
						defer wg.Done()
						for w := range windows {
							offsets := make([]time.Duration, sampling)
							for i := range offsets {
								offsets[i] = time.Duration(rand.Int64N(int64(window)))
							}
							slices.Sort(offsets)
							for _, off := range offsets {
								time.Sleep(time.Until(start.Add(time.Duration(w)*window + off)))
								probe := ss
								if g.fresh {
									probe = subject()
								}
								took, err := titanTimedEcho(g.ctx, tr.dial, dest, probe)
								if err != nil {
									t.Errorf("%s: %v", g.name, err)
									return
								}
								rtts.Put(took)
								g.add(took, handshake)
							}
							if w > 0 { // a full window of samples (sampling) from the second on
								g.addDeviation(rtts.Get().Deviation)
							}
						}
					}()
				}
			}
			wg.Wait()
			if t.Failed() {
				return
			}
			for _, g := range groups {
				t.Logf("%-14s: %3d probes, %3.0f%% cold (warm %5.1f ms, cold %5.1f ms), deviation of the last %d: %5.1f ms on average",
					g.name, g.n(), 100*g.coldShare(), ms(g.warmMean()), ms(g.coldMean()), sampling, ms(g.deviation()))
			}
			mix, cold := groups[2], groups[3]
			delta := cold.coldMean() - mix.warmMean() // what a handshake adds
			if mix.warmMean() == 0 {
				t.Fatal("kept a minute: no warm probe")
			}
			for _, probes := range groups[:2] {
				if share := probes.coldShare(); share < 0.97 {
					t.Errorf("%s: %.0f%% cold, not all", probes.name, 100*share)
				}
				if probes.deviation() > cold.deviation()+delta/10 {
					t.Errorf("%s: deviation %.1f ms, always cold %.1f ms (a handshake +%.1f)", probes.name, ms(probes.deviation()), ms(cold.deviation()), ms(delta))
				}
			}
			// That the test sees a mix (where there is one: grpc-go's idle timer fires 1-2 times its
			// timeout after the last RPC, and gRPC's probes kept a minute were nearly all warm).
			probes := groups[0]
			if share := mix.coldShare(); share > 0.15 && share < 0.85 && mix.deviation() < probes.deviation()+delta/5 {
				t.Errorf("kept a minute: deviation %.1f ms, the probes' %.1f: the mix doesn't show (a handshake +%.1f)", ms(mix.deviation()), ms(probes.deviation()), ms(delta))
			}
		})
	}
}

func ms(d time.Duration) float64 { return float64(d) / float64(time.Millisecond) }

// titanGroup: one group's samples (cold: one took a handshake's time at least) and deviations.
type titanGroup struct {
	name  string
	ctx   context.Context
	fresh bool // each probe on a subject of its own
	user  bool // each subject with a user's flow open all along

	mu         sync.Mutex
	warm, cold []time.Duration
	deviations []time.Duration
}

func (g *titanGroup) add(took, handshake time.Duration) {
	g.mu.Lock()
	defer g.mu.Unlock()
	if took >= handshake {
		g.cold = append(g.cold, took)
	} else {
		g.warm = append(g.warm, took)
	}
}

func (g *titanGroup) addDeviation(d time.Duration) {
	g.mu.Lock()
	defer g.mu.Unlock()
	g.deviations = append(g.deviations, d)
}

func titanMean(ds []time.Duration) time.Duration {
	if len(ds) == 0 {
		return 0
	}
	var sum time.Duration
	for _, d := range ds {
		sum += d
	}
	return sum / time.Duration(len(ds))
}

func (g *titanGroup) n() int {
	g.mu.Lock()
	defer g.mu.Unlock()
	return len(g.warm) + len(g.cold)
}

func (g *titanGroup) coldShare() float64 {
	g.mu.Lock()
	defer g.mu.Unlock()
	return float64(len(g.cold)) / float64(len(g.warm)+len(g.cold))
}

func (g *titanGroup) warmMean() time.Duration {
	g.mu.Lock()
	defer g.mu.Unlock()
	return titanMean(g.warm)
}

func (g *titanGroup) coldMean() time.Duration {
	g.mu.Lock()
	defer g.mu.Unlock()
	return titanMean(g.cold)
}

func (g *titanGroup) deviation() time.Duration {
	g.mu.Lock()
	defer g.mu.Unlock()
	return titanMean(g.deviations)
}

// titanTimedEcho is one probe: a flow through ss on ctx, from its dial to its echo, then closed.
func titanTimedEcho(ctx context.Context, dial func(context.Context, net.Destination, *internet.MemoryStreamConfig) (stat.Connection, error),
	dest net.Destination, ss *internet.MemoryStreamConfig) (time.Duration, error) {
	type result struct {
		took time.Duration
		err  error
	}
	done := make(chan result, 1)
	go func() {
		start := time.Now()
		conn, err := dial(ctx, dest, ss)
		if err != nil {
			done <- result{0, err}
			return
		}
		defer conn.Close()
		if _, err := titanEcho(conn); err != nil {
			done <- result{0, err}
			return
		}
		done <- result{time.Since(start), nil}
	}()
	select {
	case r := <-done:
		return r.took, r.err
	case <-time.After(10 * time.Second):
		return 0, fmt.Errorf("no echo in 10 s")
	}
}

// titanEcho sends a message on conn and reads it back.
func titanEcho(conn gonet.Conn) (int, error) {
	msg := fmt.Appendf(nil, "titan %d", rand.Int())
	if _, err := conn.Write(msg); err != nil {
		return 0, err
	}
	got := make([]byte, len(msg))
	if _, err := io.ReadFull(conn, got); err != nil {
		return 0, err
	}
	if !bytes.Equal(got, msg) {
		return 0, fmt.Errorf("echo %q, sent %q", got, msg)
	}
	return len(got), nil
}

func titanEchoHandler(conn stat.Connection) {
	go func() {
		defer conn.Close()
		io.Copy(conn, conn)
	}()
}

// titanCert: a server certificate (loaded once: Xray's reload ticker rewrites it as handshakes
// read it) and its hash for the subjects to pin.
func titanCert() (*tls.Certificate, []byte) {
	ct, hash := cert.MustGenerate(nil, cert.CommonName("localhost"))
	c := tls.ParseCertificate(ct)
	c.OneTimeLoading = true
	return c, hash[:]
}

// An XHTTP server over TLS (HTTP/2) behind a TCP delay line, and new subjects for it: stream-up,
// Xray's default XMUX (3 connections a subject at most).
func titanXHTTPServer(t *testing.T, oneWay, handshake time.Duration) (net.Destination, func() *internet.MemoryStreamConfig) {
	c, hash := titanCert()
	port := tcp.PickPort()
	ln, err := splithttp.ListenXH(context.Background(), net.LocalHostIP, port, &internet.MemoryStreamConfig{
		ProtocolName:     "splithttp",
		ProtocolSettings: &splithttp.Config{Path: "titan"},
		SecurityType:     "tls",
		SecuritySettings: &tls.Config{Certificate: []*tls.Certificate{c}},
	}, titanEchoHandler)
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() { ln.Close() })
	dest := titanTCPDelay(t, fmt.Sprintf("127.0.0.1:%d", port), oneWay, handshake)
	return dest, func() *internet.MemoryStreamConfig {
		return &internet.MemoryStreamConfig{
			ProtocolName: "splithttp",
			ProtocolSettings: &splithttp.Config{Path: "titan", Mode: "stream-up", Xmux: &splithttp.XmuxConfig{
				MaxConnections:   &splithttp.RangeConfig{From: 3, To: 3},
				HMaxRequestTimes: &splithttp.RangeConfig{From: 600, To: 900},
				HMaxReusableSecs: &splithttp.RangeConfig{From: 1800, To: 3000},
			}},
			SecurityType:     "tls",
			SecuritySettings: &tls.Config{PinnedPeerCertSha256: [][]byte{hash}},
		}
	}
}

// A gRPC (multiMode) server behind a TCP delay line, and new subjects for it.
func titanGRPCServer(t *testing.T, oneWay, handshake time.Duration) (net.Destination, func() *internet.MemoryStreamConfig) {
	port := tcp.PickPort()
	ln, err := xgrpc.Listen(context.Background(), net.LocalHostIP, port, &internet.MemoryStreamConfig{
		ProtocolName: "grpc", ProtocolSettings: &xgrpc.Config{ServiceName: "titan", MultiMode: true},
	}, titanEchoHandler)
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() { ln.Close() })
	dest := titanTCPDelay(t, fmt.Sprintf("127.0.0.1:%d", port), oneWay, handshake)
	return dest, func() *internet.MemoryStreamConfig {
		return &internet.MemoryStreamConfig{ProtocolName: "grpc", ProtocolSettings: &xgrpc.Config{ServiceName: "titan", MultiMode: true}}
	}
}

// A Hysteria server behind a UDP delay line, and new subjects for it (the panel's keep-alive: 1 s
// here, 5 s on the panel).
func titanHyServer(t *testing.T, oneWay, handshake time.Duration) (net.Destination, func() *internet.MemoryStreamConfig) {
	c, hash := titanCert()
	port := udp.PickPort()
	ln, err := hysteria.Listen(context.Background(), net.LocalHostIP, port, &internet.MemoryStreamConfig{
		ProtocolName:     "hysteria",
		ProtocolSettings: &hysteria.Config{Auth: "titan"},
		SecurityType:     "tls",
		SecuritySettings: &tls.Config{Certificate: []*tls.Certificate{c}},
	}, titanEchoHandler)
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() { ln.Close() })
	dest := titanUDPDelay(t, &gonet.UDPAddr{IP: gonet.IPv4(127, 0, 0, 1), Port: int(port)}, oneWay, handshake)
	return dest, func() *internet.MemoryStreamConfig {
		return &internet.MemoryStreamConfig{
			ProtocolName:     "hysteria",
			ProtocolSettings: &hysteria.Config{Auth: "titan"},
			SecurityType:     "tls",
			SecuritySettings: &tls.Config{PinnedPeerCertSha256: [][]byte{hash}, NextProtocol: []string{"h3"}},
			QuicParams:       &internet.QuicParams{KeepAlivePeriod: 1, Congestion: "bbr"},
		}
	}
}

// titanTCPDelay forwards TCP connections to addr, each way oneWay later, a new connection
// handshake later still (what a cold dial's TCP + TLS / REALITY handshake costs over a real path),
// and gives its own address.
func titanTCPDelay(t *testing.T, addr string, oneWay, handshake time.Duration) net.Destination {
	ln, err := gonet.Listen("tcp", "127.0.0.1:0")
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() { ln.Close() })
	forward := func(dst, src gonet.Conn) {
		type chunk struct {
			b  []byte
			at time.Time
		}
		ch := make(chan chunk, 4096)
		go func() {
			defer dst.Close()
			for c := range ch {
				time.Sleep(time.Until(c.at))
				if _, err := dst.Write(c.b); err != nil {
					return
				}
			}
		}()
		defer close(ch)
		for {
			b := make([]byte, 32<<10)
			n, err := src.Read(b)
			if n > 0 {
				ch <- chunk{b[:n], time.Now().Add(oneWay)}
			}
			if err != nil {
				return
			}
		}
	}
	go func() {
		for {
			c, err := ln.Accept()
			if err != nil {
				return
			}
			go func() {
				time.Sleep(handshake)
				u, err := gonet.Dial("tcp", addr)
				if err != nil {
					c.Close()
					return
				}
				go forward(u, c)
				go forward(c, u)
			}()
		}
	}()
	return net.TCPDestination(net.LocalHostIP, net.Port(ln.Addr().(*gonet.TCPAddr).Port))
}

// titanUDPDelay forwards each client's UDP datagrams to server and back (a socket of its own
// towards the server for each client address), each way oneWay later, a new QUIC connection's
// first datagrams handshake later still, keeping their order, and gives its own address. A new
// connection: a long-header packet from the client to an original destination connection ID it
// didn't use yet on that address (the random one of 8 bytes at least a client's first Initial
// has, RFC 9000 7.2; its later flights go to the server's; the system gives a client's port out
// again).
func titanUDPDelay(t *testing.T, server *gonet.UDPAddr, oneWay, handshake time.Duration) net.Destination {
	front, err := gonet.ListenUDP("udp", &gonet.UDPAddr{IP: gonet.IPv4(127, 0, 0, 1)})
	if err != nil {
		t.Fatal(err)
	}
	var closed atomic.Bool
	var mu sync.Mutex
	backs := map[string]*gonet.UDPConn{}
	t.Cleanup(func() {
		closed.Store(true)
		front.Close()
		mu.Lock()
		for _, b := range backs {
			b.Close()
		}
		mu.Unlock()
	})
	type datagram struct {
		b  []byte
		at time.Time
	}
	queue := func(write func([]byte)) chan<- datagram {
		ch := make(chan datagram, 1<<12)
		go func() {
			for d := range ch {
				time.Sleep(time.Until(d.at))
				write(d.b)
			}
		}()
		return ch
	}
	type peer struct {
		up    chan<- datagram
		until time.Time // the handshake's datagrams wait till then

		ids map[string]bool // original destination connection IDs seen
	}
	// A long header's destination connection ID (RFC 9000 17.2).
	longDCID := func(b []byte) (string, bool) {
		if len(b) < 6 || b[0]&0x80 == 0 || len(b) < 6+int(b[5]) {
			return "", false
		}
		return string(b[6 : 6+int(b[5])]), true
	}
	peers := map[string]*peer{}
	go func() {
		for {
			b := make([]byte, 2048)
			n, from, err := front.ReadFromUDP(b)
			if err != nil {
				return
			}
			key := from.String()
			p := peers[key]
			if p == nil {
				back, err := gonet.DialUDP("udp", nil, server)
				if err != nil {
					continue
				}
				mu.Lock()
				if closed.Load() {
					mu.Unlock()
					back.Close()
					return
				}
				backs[key] = back
				mu.Unlock()
				p = &peer{up: queue(func(b []byte) { back.Write(b) }), ids: map[string]bool{}}
				peers[key] = p
				down := queue(func(b []byte) { front.WriteToUDP(b, from) })
				go func() {
					defer close(down)
					for {
						b := make([]byte, 2048)
						n, err := back.Read(b)
						if err != nil {
							return
						}
						down <- datagram{b[:n], time.Now().Add(oneWay)}
					}
				}()
			}
			now := time.Now()
			if dcid, ok := longDCID(b[:n]); ok && len(dcid) >= 8 && !p.ids[dcid] {
				p.ids[dcid] = true
				p.until = now.Add(oneWay + handshake)
			}
			at := now.Add(oneWay)
			if at.Before(p.until) {
				at = p.until
			}
			p.up <- datagram{b[:n], at}
		}
	}()
	return net.UDPDestination(net.LocalHostIP, net.Port(front.LocalAddr().(*gonet.UDPAddr).Port))
}
