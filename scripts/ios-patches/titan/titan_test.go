package titan

import (
	"encoding/json"
	"fmt"
	"io"
	"math"
	"net"
	"runtime/debug"
	"strings"
	"testing"
	"time"

	"github.com/enfein/mieru/v3/apis/constant"
	"github.com/enfein/mieru/v3/apis/model"
	"github.com/enfein/mieru/v3/apis/server"
	"github.com/enfein/mieru/v3/pkg/appctl/appctlpb"
	"golang.org/x/net/proxy"
	"google.golang.org/protobuf/proto"
)

func echoServer(t *testing.T) string {
	ln, _ := net.Listen("tcp", "127.0.0.1:0")
	go func() {
		for {
			c, err := ln.Accept()
			if err != nil {
				return
			}
			go func() { io.Copy(c, c); c.Close() }()
		}
	}()
	t.Cleanup(func() { ln.Close() })
	return ln.Addr().String()
}

func call(t *testing.T, req map[string]any) map[string]any {
	b, _ := json.Marshal(req)
	var out map[string]any
	json.Unmarshal([]byte(Invoke(string(b))), &out)
	if e, ok := out["error"]; ok {
		t.Fatalf("%v: %v", req["method"], e)
	}
	return out
}

func freePort(t *testing.T) int {
	return int(call(t, map[string]any{"method": "freePort"})["port"].(float64))
}

func roundTrip(t *testing.T, socksPort int, target string) {
	d, _ := proxy.SOCKS5("tcp", fmt.Sprintf("127.0.0.1:%d", socksPort), nil, proxy.Direct)
	var c net.Conn
	var err error
	for i := 0; i < 20; i++ {
		if c, err = d.Dial("tcp", target); err == nil {
			break
		}
		time.Sleep(100 * time.Millisecond)
	}
	if err != nil {
		t.Fatal(err)
	}
	defer c.Close()
	c.SetDeadline(time.Now().Add(5 * time.Second))
	c.Write([]byte("hello titan"))
	b := make([]byte, 11)
	if _, err := io.ReadFull(c, b); err != nil || string(b) != "hello titan" {
		t.Fatalf("got %q, %v", b, err)
	}
}

func TestSingBox(t *testing.T) {
	target := echoServer(t)
	port := freePort(t)
	cfg := fmt.Sprintf(`{"log":{"level":"warn"},"inbounds":[{"type":"mixed","listen":"127.0.0.1","listen_port":%d}],
"outbounds":[{"type":"direct","tag":"proxy"}],"route":{"final":"proxy"}}`, port)
	h := call(t, map[string]any{"method": "startSingBox", "config": cfg})["handle"]
	defer call(t, map[string]any{"method": "stop", "handle": h})
	roundTrip(t, port, target)
}

func TestMieru(t *testing.T) {
	target := echoServer(t)
	mport := freePort(t)
	s := server.NewServer()
	if err := s.Store(&server.ServerConfig{Config: &appctlpb.ServerConfig{
		PortBindings: []*appctlpb.PortBinding{{Port: proto.Int32(int32(mport)), Protocol: appctlpb.TransportProtocol_TCP.Enum()}},
		Users:        []*appctlpb.User{{Name: proto.String("u1"), Password: proto.String("p1")}},
	}}); err != nil {
		t.Fatal(err)
	}
	if err := s.Start(); err != nil {
		t.Fatal(err)
	}
	defer s.Stop()
	go func() {
		for {
			pc, req, err := s.Accept()
			if err != nil {
				return
			}
			go func() {
				defer pc.Close()
				if req.Command != constant.Socks5ConnectCmd {
					return
				}
				tc, err := net.Dial("tcp", req.DstAddr.String())
				if err != nil {
					return
				}
				defer tc.Close()
				(&model.Response{Reply: constant.Socks5ReplySuccess, BindAddr: model.AddrSpec{IP: net.IPv4zero}}).WriteToSocks5(pc)
				go io.Copy(tc, pc)
				io.Copy(pc, tc)
			}()
		}
	}()
	sport := freePort(t)
	profile := fmt.Sprintf(`{"profileName":"titan","user":{"name":"u1","password":"p1"},
"servers":[{"ipAddress":"127.0.0.1","portBindings":[{"port":%d,"protocol":"TCP"}]}],"mtu":1400}`, mport)
	h := call(t, map[string]any{"method": "startMieru", "profile": profile, "port": sport})["handle"]
	defer call(t, map[string]any{"method": "stop", "handle": h})
	roundTrip(t, sport, target)
}

func TestMemStats(t *testing.T) {
	call(t, map[string]any{"method": "freeMemory"})
	m := call(t, map[string]any{"method": "memStats"})
	for _, k := range []string{"total", "released", "liveHeap", "stacks", "goroutines"} {
		if n, ok := m[k].(float64); !ok || n <= 0 {
			t.Fatalf("%s: %v", k, m[k])
		}
	}
}

// The soft limit: the extension's limit less the rest (at least 12 MiB) and 8 MiB, at least
// 24 MiB, while Go needs no more (this test's heap is small); memStats reports it.
func TestMemoryLimit(t *testing.T) {
	defer debug.SetMemoryLimit(math.MaxInt64)
	call(t, map[string]any{"method": "freeMemory"})
	m := call(t, map[string]any{"method": "memStats"})
	goRetained := int64(m["total"].(float64) - m["released"].(float64))
	for _, c := range []struct {
		limit, rest, want int64
		exact             bool
	}{
		{50 << 20, 10 << 20, 30 << 20, true}, // the rest under 12 MiB counts as 12
		{50 << 20, 16 << 20, 26 << 20, false},
		{30 << 20, 10 << 20, 24 << 20, true}, // never under 24 MiB
	} {
		r := call(t, map[string]any{"method": "setMemoryLimit", "limit": c.limit, "footprint": goRetained + c.rest})
		got := int64(r["goLimit"].(float64))
		if c.exact && got != c.want || !c.exact && math.Abs(float64(got-c.want)) > 1<<20 {
			t.Fatalf("limit %d, rest %d: %d, want %d", c.limit>>20, c.rest>>20, got, c.want)
		}
		if l := int64(call(t, map[string]any{"method": "memStats"})["limit"].(float64)); l != got {
			t.Fatalf("memStats limit %d, set %d", l, got)
		}
	}
	b, _ := json.Marshal(map[string]any{"method": "setMemoryLimit"})
	if out := Invoke(string(b)); !strings.Contains(out, "error") {
		t.Fatalf("no limit: %s", out)
	}
}

// What Go needs raises the limit up to the watchdog's threshold (limit − the rest − 5 MiB),
// a busy GC to it; never under 24 MiB.
func TestGoLimitFor(t *testing.T) {
	const mib = 1 << 20
	// Go with this live heap and 7 MiB besides objects and free pages, GOGC 10.
	go_ := func(live uint64) map[string]uint64 {
		return map[string]uint64{"liveHeap": live * mib, "objects": (live + 2) * mib, "free": 1 * mib,
			"total": (live + 12) * mib, "released": 2 * mib, "gogc": 10}
	}
	for _, c := range []struct {
		limit, live, rest uint64
		busy              bool
		want, need        int64
		why               string
	}{
		{50, 18, 10, false, 30, 28, "fit"},  // needs 18 + 7 + 3
		{50, 22, 12, false, 32, 32, "need"}, // over 30: raised to it
		{50, 26, 12, false, 33, 36, "need"}, // up to 50 − 12 − 5 at most
		{50, 18, 12, true, 33, 28, "gc"},    // the GC busy: to the threshold
		{30, 18, 10, false, 24, 28, "min"},
		{30, 18, 10, true, 24, 28, "min"},
	} {
		m := go_(c.live)
		retained := m["total"] - m["released"]
		got, nonGo, need, why := goLimitFor(int64(c.limit*mib), int64(retained+c.rest*mib), m, c.busy)
		if got != c.want*mib || need != c.need*mib || why != c.why || nonGo != int64(max(c.rest, 12)*mib) {
			t.Fatalf("limit %d, live %d, rest %d, busy %v: %d MiB (%s, need %d, rest %d), want %d (%s, need %d)",
				c.limit, c.live, c.rest, c.busy, got/mib, why, need/mib, nonGo/mib, c.want, c.why, c.need)
		}
	}
}

// Nothing pooled outside a running Xray: nothing to close, and no error.
func TestCloseTransports(t *testing.T) {
	if n, ok := call(t, map[string]any{"method": "closeTransports"})["closed"].(float64); !ok || n != 0 {
		t.Fatalf("closed %v", n)
	}
}
