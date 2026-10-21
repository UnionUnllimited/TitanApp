package core_test

import (
	"context"
	"fmt"
	"net"
	"strings"
	"testing"
	"time"

	"github.com/xtls/xray-core/core"
	xnet "github.com/xtls/xray-core/common/net"
	"github.com/xtls/xray-core/infra/conf/serial"
	_ "github.com/xtls/xray-core/main/distro/all"
)

// Two instances with the same tags, as parallel pingBatch calls make them: a dial through A
// must reach A's dialerProxy hop, not the one of B (created last).
func newInstance(t *testing.T, port int, mux bool) *core.Instance {
	muxJSON := ""
	if mux {
		muxJSON = `"mux": {"enabled": true, "concurrency": 4},`
	}
	cfg := fmt.Sprintf(`{"outbounds": [
		{"tag": "p", "protocol": "vmess", %s
		 "settings": {"vnext": [{"address": "127.0.0.1", "port": 9, "users": [{"id": "27848739-7e62-4138-9fd3-098a63964b6b"}]}]},
		 "streamSettings": {"sockopt": {"dialerProxy": "hop"}}},
		{"tag": "hop", "protocol": "freedom", "settings": {"redirect": "127.0.0.1:%d"}}
	]}`, muxJSON, port)
	c, err := serial.LoadJSONConfig(strings.NewReader(cfg))
	if err != nil {
		t.Fatal(err)
	}
	v, err := core.New(c)
	if err != nil {
		t.Fatal(err)
	}
	if err := v.Start(); err != nil {
		t.Fatal(err)
	}
	return v
}

func listen(t *testing.T) (net.Listener, chan struct{}) {
	l, err := net.Listen("tcp", "127.0.0.1:0")
	if err != nil {
		t.Fatal(err)
	}
	got := make(chan struct{}, 8)
	go func() {
		for {
			c, err := l.Accept()
			if err != nil {
				return
			}
			got <- struct{}{}
			c.Close()
		}
	}()
	return l, got
}

func check(t *testing.T, mux bool) {
	la, gotA := listen(t)
	defer la.Close()
	lb, gotB := listen(t)
	defer lb.Close()
	a := newInstance(t, la.Addr().(*net.TCPAddr).Port, mux)
	defer a.Close()
	b := newInstance(t, lb.Addr().(*net.TCPAddr).Port, mux) // the global manager is B's now
	defer b.Close()
	ctx, cancel := context.WithTimeout(context.Background(), 3*time.Second)
	defer cancel()
	conn, err := core.Dial(ctx, a, xnet.TCPDestination(xnet.DomainAddress("example.com"), 80))
	if err == nil {
		conn.Write([]byte("GET / HTTP/1.1\r\nHost: example.com\r\n\r\n"))
		defer conn.Close()
	}
	select {
	case <-gotA:
		t.Logf("mux=%v: A's hop (right)", mux)
	case <-gotB:
		t.Fatalf("mux=%v: went through B's hop", mux)
	case <-time.After(3 * time.Second):
		t.Fatalf("mux=%v: no hop reached (dial err %v)", mux, err)
	}
}

func TestTitanOwnHop(t *testing.T)    { check(t, false) }
func TestTitanOwnHopMux(t *testing.T) { check(t, true) }
