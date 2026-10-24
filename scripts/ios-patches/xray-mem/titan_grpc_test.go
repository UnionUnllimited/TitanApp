package grpc

import (
	"context"
	"errors"
	"io"
	gonet "net"
	"testing"
	"time"

	"github.com/xtls/xray-core/common/net"
	"github.com/xtls/xray-core/transport/internet"
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
