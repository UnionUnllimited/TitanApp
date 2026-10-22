package titan

import (
	"encoding/json"
	"fmt"
	"io"
	"net"
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
