package splithttp

import (
	"bytes"
	"context"
	"io"
	gonet "net"
	"net/http"
	"strings"
	"sync/atomic"
	"testing"
	"time"

	"github.com/xtls/xray-core/common/net"
	"github.com/xtls/xray-core/transport/internet"
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
