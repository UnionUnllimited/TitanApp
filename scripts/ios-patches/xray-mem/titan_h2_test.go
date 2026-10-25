package splithttp

import (
	"bufio"
	"bytes"
	"context"
	"encoding/hex"
	"errors"
	"fmt"
	"io"
	"math/rand/v2"
	gonet "net"
	"net/http"
	"os"
	"os/exec"
	"runtime"
	"runtime/metrics"
	"slices"
	"strings"
	"sync"
	"sync/atomic"
	"testing"
	"time"
	"weak"

	"github.com/xtls/xray-core/common/net"
	"github.com/xtls/xray-core/common/protocol/tls/cert"
	"github.com/xtls/xray-core/testing/servers/tcp"
	"github.com/xtls/xray-core/transport/internet"
	"github.com/xtls/xray-core/transport/internet/stat"
	"github.com/xtls/xray-core/transport/internet/tls"
	"golang.org/x/net/http2"
	"golang.org/x/net/http2/hpack"
)

// The server's MAX_FRAME_SIZE is lowered to 16 KiB in its SETTINGS however its bytes are
// split into reads, and nothing else changes (xray-mem patch).
func TestTitanH2Settings(t *testing.T) {
	var in bytes.Buffer
	fr := http2.NewFramer(&in, nil)
	fr.WriteSettings(http2.Setting{ID: http2.SettingMaxFrameSize, Val: 1 << 20},
		http2.Setting{ID: http2.SettingInitialWindowSize, Val: 1 << 20}, http2.Setting{ID: http2.SettingMaxConcurrentStreams, Val: 100})
	fr.WriteData(1, false, bytes.Repeat([]byte{0, 5, 0, 0x10, 0, 0}, 50))
	fr.WriteSettingsAck()
	fr.WriteSettings(http2.Setting{ID: http2.SettingMaxFrameSize, Val: 1 << 16})
	fr.WriteWindowUpdate(0, 1<<20)
	src := in.Bytes()
	for chunk := 1; chunk <= len(src); chunk++ {
		c := &titanH2Conn{}
		out := append([]byte(nil), src...)
		for i := 0; i < len(out); i += chunk {
			c.scan(out[i:min(i+chunk, len(out))])
		}
		rd := http2.NewFramer(nil, bytes.NewReader(out))
		var got []string
		for {
			f, err := rd.ReadFrame()
			if err == io.EOF {
				break
			} else if err != nil {
				t.Fatalf("chunk %d: %v", chunk, err)
			}
			switch f := f.(type) {
			case *http2.SettingsFrame:
				f.ForeachSetting(func(s http2.Setting) error { got = append(got, s.String()); return nil })
				if f.IsAck() {
					got = append(got, "ack")
				}
			case *http2.DataFrame:
				if !bytes.Equal(f.Data(), bytes.Repeat([]byte{0, 5, 0, 0x10, 0, 0}, 50)) {
					t.Fatalf("chunk %d: data changed", chunk)
				}
				got = append(got, "data")
			case *http2.WindowUpdateFrame:
				got = append(got, "window")
			}
		}
		want := "[MAX_FRAME_SIZE = 16384] [INITIAL_WINDOW_SIZE = 1048576] [MAX_CONCURRENT_STREAMS = 100] data ack [MAX_FRAME_SIZE = 16384] window"
		if s := strings.Join(got, " "); s != want {
			t.Fatalf("chunk %d: %s", chunk, s)
		}
	}
}

// The client advertises the smaller windows and frames, and a stream-up body goes out in
// 16 KiB frames to a server that takes 1 MiB ones (its scratch buffer is that small).
func TestTitanH2Transport(t *testing.T) {
	ln, err := gonet.Listen("tcp", "127.0.0.1:0")
	if err != nil {
		t.Fatal(err)
	}
	defer ln.Close()
	type report struct {
		frame, window, connWindow uint32
		maxData                   int
		err                       error
	}
	reports := make(chan report, 1)
	go func() {
		var r report
		defer func() { reports <- r }()
		conn, err := ln.Accept()
		if err != nil {
			r.err = err
			return
		}
		defer conn.Close()
		fr := http2.NewFramer(conn, conn)
		// Ours first: the client reads it before it answers its first request.
		fr.WriteSettings(http2.Setting{ID: http2.SettingMaxFrameSize, Val: 1 << 20}, http2.Setting{ID: http2.SettingInitialWindowSize, Val: 1 << 24})
		fr.WriteWindowUpdate(0, 1<<24)
		if _, err := io.ReadFull(conn, make([]byte, len(http2.ClientPreface))); err != nil {
			r.err = err
			return
		}
		var hbuf bytes.Buffer
		enc := hpack.NewEncoder(&hbuf)
		got := 0
		for got < 300<<10 {
			f, err := fr.ReadFrame()
			if err != nil {
				r.err = err
				return
			}
			switch f := f.(type) {
			case *http2.SettingsFrame:
				if !f.IsAck() {
					v, _ := f.Value(http2.SettingMaxFrameSize)
					w, _ := f.Value(http2.SettingInitialWindowSize)
					r.frame, r.window = v, w
					fr.WriteSettingsAck()
				}
			case *http2.WindowUpdateFrame:
				if f.StreamID == 0 && r.connWindow == 0 {
					r.connWindow = f.Increment
				}
			case *http2.HeadersFrame:
				hbuf.Reset()
				enc.WriteField(hpack.HeaderField{Name: ":status", Value: "200"})
				// The first request (GET) ends at once; the upload's stays open.
				fr.WriteHeaders(http2.HeadersFrameParam{StreamID: f.StreamID, BlockFragment: hbuf.Bytes(), EndHeaders: true, EndStream: f.StreamEnded()})
			case *http2.DataFrame:
				got += len(f.Data())
				r.maxData = max(r.maxData, len(f.Data()))
			}
		}
	}()

	dial := func(ctx context.Context) (net.Conn, error) { return gonet.Dial("tcp", ln.Addr().String()) }
	tr := titanH2Transport(dial, &internet.MemoryStreamConfig{}, time.Minute, 0)
	client := &http.Client{Transport: tr, Timeout: 10 * time.Second}
	resp, err := client.Get("https://titan.test/warm")
	if err != nil {
		t.Fatal(err)
	}
	resp.Body.Close()
	pr, pw := io.Pipe()
	req, _ := http.NewRequest("POST", "https://titan.test/up", pr)
	// Its response stays open: closing it would end the upload too.
	go func() { tr.RoundTrip(req) }()
	go pw.Write(make([]byte, 300<<10))
	var r report
	select {
	case r = <-reports:
	case <-time.After(10 * time.Second):
		t.Fatal("no upload")
	}
	pw.Close()
	if r.err != nil {
		t.Fatal(r.err)
	}
	if r.frame != titanH2FrameSize || r.window != titanH2StreamWindow || r.connWindow != titanH2ConnWindow {
		t.Fatalf("client settings: frame %d, stream window %d, connection window %d", r.frame, r.window, r.connWindow)
	}
	if r.maxData > titanH2FrameSize {
		t.Fatalf("a %d-byte DATA frame", r.maxData)
	}
}

// titanCloseAll closes every tracked connection and empties the pool; the sweep later closes
// the ones the old stream settings got meanwhile, and only those.
func TestTitanH2Close(t *testing.T) {
	old, cur := &internet.MemoryStreamConfig{}, &internet.MemoryStreamConfig{}
	pipe := func(ss *internet.MemoryStreamConfig) gonet.Conn {
		a, b := gonet.Pipe()
		titanTrack(a, ss)
		return b
	}
	closed := func(c gonet.Conn) bool {
		c.SetReadDeadline(time.Now().Add(time.Second))
		_, err := c.Read(make([]byte, 1))
		return err == io.EOF || err == io.ErrClosedPipe
	}
	// Nothing left from the other tests (their connections close as they end).
	titanH2Conns.Lock()
	titanH2Conns.m = nil
	titanH2Conns.Unlock()
	p1, p2 := pipe(old), pipe(old)
	globalDialerAccess.Lock()
	globalDialerMap = map[dialerConf]*XmuxManager{{net.Destination{}, old}: NewXmuxManager(XmuxConfig{}, nil)}
	globalDialerAccess.Unlock()
	internet.TitanSweepAfter = 100 * time.Millisecond
	defer func() { internet.TitanSweepAfter = 90 * time.Second }()
	if n := internet.TitanCloseTransports(); n != 2 {
		t.Fatalf("closed %d", n)
	}
	if !closed(p1) || !closed(p2) {
		t.Fatal("a pooled connection is open")
	}
	globalDialerAccess.Lock()
	if len(globalDialerMap) != 0 {
		t.Fatal("the pool is not empty")
	}
	// What a dial under way still pools.
	globalDialerMap = map[dialerConf]*XmuxManager{{net.Destination{}, old}: NewXmuxManager(XmuxConfig{}, nil), {net.Destination{}, cur}: NewXmuxManager(XmuxConfig{}, nil)}
	globalDialerAccess.Unlock()
	late, live := pipe(old), pipe(cur)
	time.Sleep(300 * time.Millisecond)
	if !closed(late) {
		t.Fatal("the late connection of the old settings is open")
	}
	live.SetReadDeadline(time.Now().Add(100 * time.Millisecond))
	if _, err := live.Read(make([]byte, 1)); err == io.EOF || err == io.ErrClosedPipe {
		t.Fatal("the sweep closed a connection of the new settings")
	}
	globalDialerAccess.Lock()
	defer globalDialerAccess.Unlock()
	if _, ok := globalDialerMap[dialerConf{net.Destination{}, cur}]; !ok || len(globalDialerMap) != 1 {
		t.Fatalf("pool after the sweep: %d", len(globalDialerMap))
	}
}

// A retired XMUX client (Close: no requests left on it) closes its idle HTTP/2 connection at
// once, not after IdleConnTimeout (xray-mem patch).
func TestTitanH2Retired(t *testing.T) {
	ln, err := gonet.Listen("tcp", "127.0.0.1:0")
	if err != nil {
		t.Fatal(err)
	}
	defer ln.Close()
	ended := make(chan struct{}, 1)
	go func() {
		conn, err := ln.Accept()
		if err != nil {
			return
		}
		(&http2.Server{}).ServeConn(conn, &http2.ServeConnOpts{Handler: http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {})})
		ended <- struct{}{}
	}()
	dial := func(ctx context.Context) (net.Conn, error) { return gonet.Dial("tcp", ln.Addr().String()) }
	c := &DefaultDialerClient{client: &http.Client{Transport: titanH2Transport(dial, &internet.MemoryStreamConfig{}, time.Hour, 0)}, httpVersion: "2"}
	resp, err := c.client.Get("https://titan.test/")
	if err != nil {
		t.Fatal(err)
	}
	io.Copy(io.Discard, resp.Body)
	resp.Body.Close()
	c.Close()
	select {
	case <-ended:
	case <-time.After(5 * time.Second):
		t.Fatal("the idle connection is still open")
	}
}

// Proxied connections whose apps stopped reading (a paused or full-buffered player, a suspended
// app) on one XHTTP HTTP/2 connection, at 150 ms RTT: each holds its stream's 1 MiB at most,
// and another stream on it (the video being watched) keeps its full speed, a stream window per
// RTT. Go's client gives the connection's credit back only as a body is read: with a 4 MiB
// connection window 4 stalled streams left the others ~3 Mbit/s and 5 froze them.
func TestTitanH2Stalled(t *testing.T) {
	ln, err := gonet.Listen("tcp", "127.0.0.1:0")
	if err != nil {
		t.Fatal(err)
	}
	defer ln.Close()
	// Each response's body as fast as the client's windows let it.
	var sent [8]atomic.Int64
	go func() {
		for {
			conn, err := ln.Accept()
			if err != nil {
				return
			}
			go (&http2.Server{}).ServeConn(conn, &http2.ServeConnOpts{Handler: http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
				i := int(r.URL.Path[1] - '0')
				w.WriteHeader(http.StatusOK)
				buf := make([]byte, 16<<10)
				for {
					n, err := w.Write(buf)
					sent[i].Add(int64(n))
					if err != nil {
						return
					}
					w.(http.Flusher).Flush()
				}
			})})
		}
	}()
	line := titanDelayLine(t, ln.Addr().String(), 75*time.Millisecond)
	dial := func(ctx context.Context) (net.Conn, error) { return gonet.Dial("tcp", line) }
	tr := titanH2Transport(dial, &internet.MemoryStreamConfig{}, time.Minute, 0)
	defer tr.CloseIdleConnections()
	ctx, cancel := context.WithCancel(context.Background())
	defer cancel()
	get := func(i int) *http.Response {
		req, _ := http.NewRequestWithContext(ctx, "GET", "https://titan.test/"+string(rune('0'+i)), nil)
		resp, err := tr.RoundTrip(req)
		if err != nil {
			t.Fatal(err)
		}
		return resp
	}
	// The video, read all along.
	video := get(0)
	var got atomic.Int64
	go func() {
		buf := make([]byte, 32<<10)
		for {
			n, err := video.Body.Read(buf)
			got.Add(int64(n))
			if err != nil {
				return
			}
		}
	}()
	stalled := 0
	for _, n := range []int{4, 5} {
		// Bodies never read.
		for ; stalled < n; stalled++ {
			get(stalled + 1)
		}
		time.Sleep(time.Second)
		before := got.Load()
		time.Sleep(2 * time.Second)
		flowed := got.Load() - before
		var held int64
		for i := 1; i <= n; i++ {
			h := sent[i].Load()
			if h > titanH2StreamWindow+64<<10 {
				t.Fatalf("%d stalled: stream %d holds %d KiB", n, i, h>>10)
			}
			held += h
		}
		t.Logf("%d stalled streams hold %d KiB: the video read %d KiB in 2 s", n, held>>10, flowed>>10)
		// At full speed ~13 MiB (1 MiB per 150 ms).
		if flowed < 4<<20 {
			t.Fatalf("%d stalled streams: the video read %d KiB in 2 s", n, flowed>>10)
		}
	}
}

// titanDelayLine forwards TCP connections to addr, each way delay later (no bandwidth limit),
// and gives its own address.
func titanDelayLine(t *testing.T, addr string, delay time.Duration) string {
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
				ch <- chunk{b[:n], time.Now().Add(delay)}
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
			u, err := gonet.Dial("tcp", addr)
			if err != nil {
				c.Close()
				continue
			}
			go forward(u, c)
			go forward(c, u)
		}
	}()
	return ln.Addr().String()
}

// An XHTTP server over TLS (HTTP/2) on localhost that echoes each proxied connection, in a
// process of its own (TestTitanH2Server: Xray's server keeps a stream-up session and its padding
// goroutine 30-80 s after the connection ends, which in this process would count in the client's
// heap), and the stream settings of n subjects dialing it (each its own pool, as each outbound
// has): stream-up (a download and an upload stream per proxied connection), Xray's default XMUX
// (3 connections a subject at most: the JSON config's, transport_method.go) with a 1 s keep-alive
// (the HTTP/2 client's PING timer keeps a closed connection's client in the heap that long).
func titanXHTTP(t *testing.T, n int) (net.Destination, []*internet.MemoryStreamConfig) {
	cmd := exec.Command(os.Args[0], "-test.run=^TestTitanH2Server$", "-test.count=1")
	cmd.Env = append(os.Environ(), "TITAN_H2_SERVER=1")
	stdin, err := cmd.StdinPipe()
	if err != nil {
		t.Fatal(err)
	}
	stdout, err := cmd.StdoutPipe()
	if err != nil {
		t.Fatal(err)
	}
	if err := cmd.Start(); err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() {
		stdin.Close()
		done := make(chan struct{})
		go func() { cmd.Wait(); close(done) }()
		select {
		case <-done:
		case <-time.After(5 * time.Second):
			cmd.Process.Kill()
		}
	})
	ready := make(chan string, 1)
	go func() {
		sc := bufio.NewScanner(stdout)
		for sc.Scan() {
			if line, ok := strings.CutPrefix(sc.Text(), "titan-server "); ok {
				ready <- line
			}
		}
	}()
	var port net.Port
	var hash []byte
	select {
	case line := <-ready:
		var p int
		var h string
		if _, err := fmt.Sscanf(line, "%d %s", &p, &h); err != nil {
			t.Fatal(line, err)
		}
		port = net.Port(p)
		hash, _ = hex.DecodeString(h)
	case <-time.After(20 * time.Second):
		t.Fatal("the XHTTP server didn't start")
	}
	subjects := make([]*internet.MemoryStreamConfig, n)
	for i := range subjects {
		subjects[i] = &internet.MemoryStreamConfig{
			ProtocolName: "splithttp",
			ProtocolSettings: &Config{Path: "titan", Mode: "stream-up", Xmux: &XmuxConfig{
				MaxConnections:   &RangeConfig{From: 3, To: 3},
				HMaxRequestTimes: &RangeConfig{From: 600, To: 900},
				HMaxReusableSecs: &RangeConfig{From: 1800, To: 3000},
				HKeepAlivePeriod: 1,
			}},
			SecurityType:     "tls",
			SecuritySettings: &tls.Config{PinnedPeerCertSha256: [][]byte{hash}},
		}
	}
	return net.TCPDestination(net.LocalHostIP, port), subjects
}

// titanXHTTP's server, when the test binary runs as it: until its stdin closes.
func TestTitanH2Server(t *testing.T) {
	if os.Getenv("TITAN_H2_SERVER") == "" {
		t.Skip("the idle tests' XHTTP server, run in a process of its own")
	}
	ct, hash := cert.MustGenerate(nil, cert.CommonName("localhost"))
	// Loaded once: Xray's reload ticker rewrites the certificate as handshakes read it.
	c := tls.ParseCertificate(ct)
	c.OneTimeLoading = true
	port := tcp.PickPort()
	ln, err := ListenXH(context.Background(), net.LocalHostIP, port, &internet.MemoryStreamConfig{
		ProtocolName:     "splithttp",
		ProtocolSettings: &Config{Path: "titan"},
		SecurityType:     "tls",
		SecuritySettings: &tls.Config{Certificate: []*tls.Certificate{c}},
	}, func(conn stat.Connection) {
		go func() {
			defer conn.Close()
			io.Copy(conn, conn)
		}()
	})
	if err != nil {
		t.Fatal(err)
	}
	defer ln.Close()
	fmt.Printf("titan-server %d %x\n", port, hash[:])
	io.Copy(io.Discard, os.Stdin)
}

// titanFlow dials a proxied connection through ss and checks it echoes; open, it is left open
// (with its close) for the caller.
func titanFlow(dest net.Destination, ss *internet.MemoryStreamConfig, open bool) (stat.Connection, error) {
	type result struct {
		conn stat.Connection
		err  error
	}
	done := make(chan result, 1)
	go func() {
		conn, err := Dial(context.Background(), dest, ss)
		if err == nil {
			err = titanEcho(conn)
		}
		if err != nil || !open {
			if conn != nil {
				conn.Close()
			}
			conn = nil
		}
		done <- result{conn, err}
	}()
	select {
	case r := <-done:
		return r.conn, r.err
	case <-time.After(10 * time.Second):
		return nil, errors.New("no echo in 10 s")
	}
}

func titanEcho(conn gonet.Conn) error {
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

// The tracked HTTP/2 connections now, in all or of ss.
func titanH2Open(ss *internet.MemoryStreamConfig) int {
	titanH2Conns.Lock()
	defer titanH2Conns.Unlock()
	n := 0
	for c := range titanH2Conns.m {
		if ss == nil || c.streamSettings == ss {
			n++
		}
	}
	return n
}

// The live heap after a full GC (what stays, as the tunnel's GOGC 10 and FreeOSMemory see it).
func titanLiveHeap() int64 {
	runtime.GC()
	runtime.GC()
	s := []metrics.Sample{{Name: "/gc/heap/live:bytes"}}
	metrics.Read(s)
	return int64(s[0].Value.Uint64())
}

// Probe rounds, as the observatory runs them on subjects that carry no traffic (a proxied
// connection that echoes, then closes): with internet.TitanIdleAfter each subject's HTTP/2
// connection closes that long after its probe, so between rounds none is open and the live heap
// comes back to the same level round after round. Before (300 s idle) every subject kept its
// connections between probes, and XMUX opened a new one for each of the next probes up to its 3.
func TestTitanH2IdleProbes(t *testing.T) {
	defer func(d time.Duration) { internet.TitanIdleAfter = d }(internet.TitanIdleAfter)
	const n, rounds = 20, 12
	dest, subjects := titanXHTTP(t, 2*n)
	probe := func(subjects []*internet.MemoryStreamConfig) {
		var wg sync.WaitGroup
		slots := make(chan struct{}, 4) // the observatory's probes at once
		for _, ss := range subjects {
			wg.Add(1)
			go func() {
				defer wg.Done()
				slots <- struct{}{}
				defer func() { <-slots }()
				if _, err := titanFlow(dest, ss, false); err != nil {
					t.Error(err)
				}
			}()
		}
		wg.Wait()
	}
	// Keep-alive timers of what earlier tests closed (1 s here, 45 s by default).
	time.Sleep(1500 * time.Millisecond)
	base := titanLiveHeap()

	// What the pools held before: idle connections kept (an hour here, 300 s before), the next
	// probes opening more, up to XMUX's 3 a subject.
	internet.TitanIdleAfter = time.Hour
	kept := subjects[n:]
	for range 4 {
		probe(kept)
	}
	time.Sleep(1500 * time.Millisecond)
	keptOpen, keptHeap := titanH2Open(nil), titanLiveHeap()-base
	t.Logf("idle kept: %d HTTP/2 connections for %d subjects, %d KiB of live heap (%d KiB a subject)",
		keptOpen, n, keptHeap>>10, keptHeap>>10/n)
	if keptOpen < 2*n {
		t.Fatalf("idle kept: only %d connections for %d subjects", keptOpen, n)
	}
	titanCloseAll()
	time.Sleep(1500 * time.Millisecond)
	base = titanLiveHeap()

	internet.TitanIdleAfter = 400 * time.Millisecond
	var quiet []int64
	for r := range rounds {
		probe(subjects[:n])
		busy := titanH2Open(nil)
		// The idle close, then the keep-alive timer of the closed connections (1 s).
		time.Sleep(internet.TitanIdleAfter + 1500*time.Millisecond)
		if open := titanH2Open(nil); open != 0 {
			t.Fatalf("round %d: %d connections open %v after the probes", r, open, internet.TitanIdleAfter+1500*time.Millisecond)
		}
		h := titanLiveHeap() - base
		quiet = append(quiet, h)
		t.Logf("round %2d: %2d connections right after the probes, none open after; live heap %+5d KiB", r, busy, h>>10)
	}
	// Steady: the last rounds hold no more than the first ones (XMUX keeps up to 3 clients a
	// subject, ~1 KiB each, once all 3 have been made: by the third round).
	first, last := max(quiet[2], quiet[3], quiet[4]), max(quiet[rounds-3], quiet[rounds-2], quiet[rounds-1])
	step := titanSteadyStep(quiet, 2)
	t.Logf("live heap %+d KiB in rounds 2-4, %+d KiB in the last ones, %+d KiB a round from round 2 on (median)",
		first>>10, last>>10, step>>10)
	if last > first+256<<10 || last > keptHeap/4 {
		t.Fatalf("live heap +%d KiB in rounds 2-4, +%d KiB in the last ones (kept idle: +%d)", first>>10, last>>10, keptHeap>>10)
	}
	if step > 32<<10 {
		t.Fatalf("live heap +%d KiB a round from round 2 on (median)", step>>10)
	}
	globalDialerAccess.Lock()
	for key, m := range globalDialerMap {
		if len(m.xmuxClients) > 3 {
			t.Errorf("%d XMUX clients for %v", len(m.xmuxClients), key.Destination)
		}
	}
	globalDialerAccess.Unlock()
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

// A proxied connection that sends nothing for many idle periods keeps its HTTP/2 connection (its
// streams are open: the idle close never touches it) while probes come and go on the same subject
// and others, and still carries data both ways after; flows that start as connections close idle
// all get through.
func TestTitanH2IdleUser(t *testing.T) {
	defer func(d time.Duration) { internet.TitanIdleAfter = d }(internet.TitanIdleAfter)
	internet.TitanIdleAfter = 300 * time.Millisecond
	dest, subjects := titanXHTTP(t, 4)
	race := subjects[3]
	subjects = subjects[:3]
	user, err := titanFlow(dest, subjects[0], true)
	if err != nil {
		t.Fatal(err)
	}
	defer user.Close()
	titanH2Conns.Lock()
	var mine *titanH2Conn
	for c := range titanH2Conns.m {
		if c.streamSettings == subjects[0] {
			mine = c
		}
	}
	titanH2Conns.Unlock()
	if mine == nil {
		t.Fatal("the user's connection isn't tracked")
	}
	for i := range 20 {
		for _, ss := range subjects {
			if _, err := titanFlow(dest, ss, false); err != nil {
				t.Fatal(err)
			}
		}
		time.Sleep(internet.TitanIdleAfter / 2)
		if i%5 == 4 {
			time.Sleep(2 * internet.TitanIdleAfter)
			if titanH2Open(subjects[1]) != 0 || titanH2Open(subjects[2]) != 0 {
				t.Fatalf("a probe-only connection open %v after its probe", 2*internet.TitanIdleAfter)
			}
		}
	}
	titanH2Conns.Lock()
	_, open := titanH2Conns.m[mine]
	titanH2Conns.Unlock()
	if !open {
		t.Fatal("the user's connection was closed")
	}
	if err := titanEcho(user); err != nil {
		t.Fatalf("the user's connection after %v idle: %v", 20*internet.TitanIdleAfter, err)
	}

	// New flows around the moment a connection closes idle: each gets an open connection or a
	// new one, none fails (CloseIdleConnections leaves a connection a request has reserved).
	internet.TitanIdleAfter = 50 * time.Millisecond
	var wg sync.WaitGroup
	var failed atomic.Int32
	for range 4 {
		wg.Add(1)
		go func() {
			defer wg.Done()
			for range 40 {
				time.Sleep(time.Duration(30+rand.IntN(50)) * time.Millisecond)
				if _, err := titanFlow(dest, race, false); err != nil {
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

// A closed HTTP/2 connection lets go of the TLS / REALITY connection under it at once: Go's
// client keeps itself in the heap until its keep-alive timer fires (it never stops it when the
// connection ends), 45 s by default.
func TestTitanH2Released(t *testing.T) {
	ln, err := gonet.Listen("tcp", "127.0.0.1:0")
	if err != nil {
		t.Fatal(err)
	}
	defer ln.Close()
	go func() {
		for {
			conn, err := ln.Accept()
			if err != nil {
				return
			}
			go (&http2.Server{}).ServeConn(conn, &http2.ServeConnOpts{Handler: http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {})})
		}
	}()
	var inner weak.Pointer[gonet.TCPConn]
	var tracked weak.Pointer[titanH2Conn]
	dial := func(ctx context.Context) (net.Conn, error) {
		conn, err := gonet.Dial("tcp", ln.Addr().String())
		if err == nil {
			inner = weak.Make(conn.(*gonet.TCPConn))
		}
		return conn, err
	}
	tr := titanH2Transport(dial, &internet.MemoryStreamConfig{}, 200*time.Millisecond, 30*time.Second)
	resp, err := (&http.Client{Transport: tr}).Get("https://titan.test/")
	if err != nil {
		t.Fatal(err)
	}
	io.Copy(io.Discard, resp.Body)
	resp.Body.Close()
	titanH2Conns.Lock()
	for c := range titanH2Conns.m {
		tracked = weak.Make(c)
	}
	titanH2Conns.Unlock()
	deadline := time.Now().Add(5 * time.Second)
	for titanH2Open(nil) != 0 && time.Now().Before(deadline) {
		time.Sleep(50 * time.Millisecond)
	}
	if titanH2Open(nil) != 0 {
		t.Fatal("the connection didn't close idle")
	}
	titanLiveHeap()
	if inner.Value() != nil {
		t.Fatal("the closed connection's TCP connection is still in the heap")
	}
	t.Logf("closed idle: the TCP connection collected; the HTTP/2 client still holds its connection: %v (its PING timer, 30 s here)",
		tracked.Value() != nil)
}

// A retired XMUX client whose last proxied connection is closing (XMUX calls Close before the
// connection's bodies are closed, so its streams are still open then) closes its HTTP/2
// connection once they have ended, not IdleConnTimeout later.
func TestTitanH2RetiredOpen(t *testing.T) {
	defer func(d time.Duration) { titanRetireAgain = d }(titanRetireAgain)
	titanRetireAgain = 300 * time.Millisecond
	ln, err := gonet.Listen("tcp", "127.0.0.1:0")
	if err != nil {
		t.Fatal(err)
	}
	defer ln.Close()
	ended := make(chan struct{}, 1)
	release := make(chan struct{})
	go func() {
		conn, err := ln.Accept()
		if err != nil {
			return
		}
		(&http2.Server{}).ServeConn(conn, &http2.ServeConnOpts{Handler: http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
			w.WriteHeader(http.StatusOK)
			w.(http.Flusher).Flush()
			<-release
		})})
		ended <- struct{}{}
	}()
	dial := func(ctx context.Context) (net.Conn, error) { return gonet.Dial("tcp", ln.Addr().String()) }
	c := &DefaultDialerClient{client: &http.Client{Transport: titanH2Transport(dial, &internet.MemoryStreamConfig{}, time.Hour, 0)}, httpVersion: "2"}
	resp, err := c.client.Get("https://titan.test/")
	if err != nil {
		t.Fatal(err)
	}
	c.Close() // the stream is open: nothing to close yet
	close(release)
	io.Copy(io.Discard, resp.Body)
	resp.Body.Close()
	select {
	case <-ended:
	case <-time.After(titanRetireAgain + 2*time.Second):
		t.Fatal("the retired client's connection is still open after its stream ended")
	}
}

// One dial at a time on a transport (MaxConnsPerHost 1): requests that come together on a cold
// transport, at first and after its connection closed idle, share one dial (net/http dialed one
// for each and closed all but the first up: 12 handshakes for 12); a request a full connection
// can't take (the server's MAX_CONCURRENT_STREAMS) gets a second connection at once; the
// requests that waited behind a dial that failed fail with it, not one dial after another.
func TestTitanH2OneDial(t *testing.T) {
	defer func(d time.Duration) { titanDialFailFor = d }(titanDialFailFor)
	titanDialFailFor = 500 * time.Millisecond
	release := make(chan struct{})
	serve := func(streams uint32) string {
		ln, err := gonet.Listen("tcp", "127.0.0.1:0")
		if err != nil {
			t.Fatal(err)
		}
		t.Cleanup(func() { ln.Close() })
		go func() {
			for {
				conn, err := ln.Accept()
				if err != nil {
					return
				}
				go (&http2.Server{MaxConcurrentStreams: streams}).ServeConn(conn, &http2.ServeConnOpts{Handler: http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
					w.WriteHeader(http.StatusOK)
					w.(http.Flusher).Flush()
					if r.URL.Path == "/long" {
						<-release
					}
				})})
			}
		}()
		return ln.Addr().String()
	}
	var dials atomic.Int32
	var fail atomic.Bool
	client := func(addr string, ss *internet.MemoryStreamConfig) *http.Client {
		dial := func(ctx context.Context) (net.Conn, error) {
			dials.Add(1)
			time.Sleep(100 * time.Millisecond) // the handshake: the others wait meanwhile
			if fail.Load() {
				return nil, errors.New("titan: no answer")
			}
			return gonet.Dial("tcp", addr)
		}
		return &http.Client{Transport: titanH2Transport(dial, ss, 200*time.Millisecond, 0)}
	}
	ss := &internet.MemoryStreamConfig{}
	c := client(serve(0), ss)
	defer c.CloseIdleConnections()
	burst := func(n int) (int32, int32, time.Duration) {
		dials.Store(0)
		var failed atomic.Int32
		var wg sync.WaitGroup
		start := time.Now()
		for range n {
			wg.Add(1)
			go func() {
				defer wg.Done()
				resp, err := c.Get("https://titan.test/")
				if err != nil {
					failed.Add(1)
					return
				}
				io.Copy(io.Discard, resp.Body)
				resp.Body.Close()
			}()
		}
		wg.Wait()
		return dials.Load(), failed.Load(), time.Since(start)
	}
	idle := func() {
		deadline := time.Now().Add(3 * time.Second)
		for titanH2Open(ss) != 0 && time.Now().Before(deadline) {
			time.Sleep(20 * time.Millisecond)
		}
		if n := titanH2Open(ss); n != 0 {
			t.Fatalf("%d connections open 3 s after the last request", n)
		}
	}
	for _, when := range []string{"cold", "after the idle close", "after the idle close again"} {
		d, failed, took := burst(12)
		t.Logf("%s: 12 requests at once, %d dials, %d failed, %v", when, d, failed, took.Round(time.Millisecond))
		if d != 1 || failed != 0 {
			t.Fatalf("%s: 12 requests at once: %d dials, %d failed", when, d, failed)
		}
		idle()
	}

	fail.Store(true)
	d, failed, took := burst(8)
	t.Logf("dial failing after 100 ms: 8 requests at once, %d dials, %d failed, %v", d, failed, took.Round(time.Millisecond))
	if d != 1 || failed != 8 {
		t.Fatalf("dial failing: 8 requests at once: %d dials, %d failed (in %v)", d, failed, took)
	}
	fail.Store(false)
	time.Sleep(titanDialFailFor)
	if d, failed, _ := burst(4); d != 1 || failed != 0 {
		t.Fatalf("%v after the failed dial: %d dials, %d failed", titanDialFailFor, d, failed)
	}
	idle()

	// A server that takes 2 streams a connection: 2 long ones open, a third request dials anew.
	full := client(serve(2), ss)
	defer full.CloseIdleConnections()
	dials.Store(0)
	for range 2 {
		resp, err := full.Get("https://titan.test/long")
		if err != nil {
			t.Fatal(err)
		}
		defer resp.Body.Close()
	}
	done := make(chan error, 1)
	go func() {
		resp, err := full.Get("https://titan.test/")
		if err == nil {
			resp.Body.Close()
		}
		done <- err
	}()
	select {
	case err := <-done:
		if err != nil || dials.Load() != 2 {
			t.Fatalf("a third request on a full connection: %v, %d dials", err, dials.Load())
		}
	case <-time.After(3 * time.Second):
		t.Fatal("a third request on a full connection waits")
	}
	close(release)
}

// The balancer's pick after a pause: a page's flows at once on a subject whose connections closed
// idle make one dial (a TCP + TLS / REALITY handshake) for each of XMUX's clients, 3, not one a
// flow (12, 8, 12, 12 before); with its connections still pooled, none.
func TestTitanH2BurstAfterIdle(t *testing.T) {
	defer func(d time.Duration) { internet.TitanIdleAfter = d }(internet.TitanIdleAfter)
	dest, subjects := titanXHTTP(t, 2)
	clients := func(ss *internet.MemoryStreamConfig) int {
		globalDialerAccess.Lock()
		defer globalDialerAccess.Unlock()
		for key, m := range globalDialerMap {
			if key.MemoryStreamConfig == ss {
				return len(m.xmuxClients)
			}
		}
		return 0
	}
	for i, idle := range []time.Duration{time.Hour, 400 * time.Millisecond} {
		internet.TitanIdleAfter = idle // the XMUX clients' transports take it as they are made
		ss := subjects[i]
		for range 6 { // the pick in use: its 3 XMUX clients made
			if _, err := titanFlow(dest, ss, false); err != nil {
				t.Fatal(err)
			}
		}
		pause := 1500 * time.Millisecond // the closed connections' keep-alive timers (1 s)
		if idle < time.Hour {
			pause += idle // past the idle close
		}
		for r := range 3 {
			time.Sleep(pause)
			pooled := titanH2Open(ss)
			before := titanH2Dials.Load()
			var wg sync.WaitGroup
			for range 12 {
				wg.Add(1)
				go func() {
					defer wg.Done()
					if _, err := titanFlow(dest, ss, false); err != nil {
						t.Error(err)
					}
				}()
			}
			wg.Wait()
			d, n := titanH2Dials.Load()-before, clients(ss)
			t.Logf("idle close after %v, burst %d: %d connections pooled before, 12 flows at once: %d dials, %d XMUX clients",
				idle, r, pooled, d, n)
			if idle == time.Hour && (pooled != n || d != 0) {
				t.Fatalf("pooled: %d connections for %d XMUX clients, %d dials", pooled, n, d)
			}
			if idle < time.Hour && (pooled != 0 || d < 1 || d > int64(n)) {
				t.Fatalf("after the idle close: %d connections pooled, %d dials for %d XMUX clients", pooled, d, n)
			}
		}
	}
}
