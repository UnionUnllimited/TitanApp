package tun

import (
	"io"
	"testing"
	"time"

	"github.com/xtls/xray-core/common/net"
)

// The stack's Close ends its UDP flows (xray-mem patch): each flow's reader gets EOF, and
// the flow's own Close afterwards is harmless.
func TestTitanUDPClose(t *testing.T) {
	ended := make(chan net.Conn, 2)
	u := newUdpConnectionHandler(func(conn net.Conn, _ net.Destination) {
		p := make([]byte, 2048)
		for {
			if _, err := conn.Read(p); err == io.EOF {
				break
			}
		}
		conn.Close()
		ended <- conn
	}, func([]byte, net.Destination, net.Destination) error { return nil })
	dst := net.UDPDestination(net.ParseAddress("1.1.1.1"), 53)
	u.HandlePacket(net.UDPDestination(net.ParseAddress("172.19.0.1"), 50001), dst, []byte("a"))
	u.HandlePacket(net.UDPDestination(net.ParseAddress("172.19.0.1"), 50002), dst, []byte("b"))
	u.titanCloseAll()
	for i := 0; i < 2; i++ {
		select {
		case <-ended:
		case <-time.After(5 * time.Second):
			t.Fatal("a UDP flow did not end")
		}
	}
	u.RLock()
	defer u.RUnlock()
	if len(u.udpConns) != 0 {
		t.Fatalf("%d flows left", len(u.udpConns))
	}
}
