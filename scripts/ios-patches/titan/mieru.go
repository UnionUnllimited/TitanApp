package titan

import (
	"context"
	"io"
	"net"
	"strconv"
	"sync"
	"time"

	"github.com/enfein/mieru/v3/apis/client"
	"github.com/enfein/mieru/v3/apis/model"
	"github.com/enfein/mieru/v3/pkg/appctl/appctlpb"
	"google.golang.org/protobuf/encoding/protojson"
)

// startMieru runs a mieru client behind a SOCKS5 (CONNECT) listener on 127.0.0.1:port.
func startMieru(profileJSON string, port int) (io.Closer, error) {
	profile := &appctlpb.ClientProfile{}
	if err := protojson.Unmarshal([]byte(profileJSON), profile); err != nil {
		return nil, err
	}
	mc := client.NewClient()
	if err := mc.Store(&client.ClientConfig{Profile: profile}); err != nil {
		return nil, err
	}
	if err := mc.Start(); err != nil {
		return nil, err
	}
	ln, err := net.Listen("tcp", "127.0.0.1:"+strconv.Itoa(port))
	if err != nil {
		mc.Stop()
		return nil, err
	}
	m := &mieruSocks{client: mc, ln: ln}
	go m.serve()
	return m, nil
}

type mieruSocks struct {
	client client.Client
	ln     net.Listener
	once   sync.Once
}

func (m *mieruSocks) Close() error {
	m.once.Do(func() {
		m.ln.Close()
		m.client.Stop()
	})
	return nil
}

func (m *mieruSocks) serve() {
	for {
		c, err := m.ln.Accept()
		if err != nil {
			return
		}
		go m.handle(c)
	}
}

func (m *mieruSocks) handle(c net.Conn) {
	defer c.Close()
	c.SetDeadline(time.Now().Add(30 * time.Second))
	// Greeting: version 5, methods; we take "no authentication".
	head := make([]byte, 2)
	if _, err := io.ReadFull(c, head); err != nil || head[0] != 5 {
		return
	}
	if _, err := io.ReadFull(c, make([]byte, head[1])); err != nil {
		return
	}
	if _, err := c.Write([]byte{5, 0}); err != nil {
		return
	}
	req, err := model.ReadSocks5Request(c)
	if err != nil {
		return
	}
	if req.Command != 1 { // only CONNECT
		model.WriteSocks5Response(c, 7, model.AddrSpec{IP: net.IPv4zero})
		return
	}
	dst, err := req.ToNetAddrSpec()
	if err != nil {
		model.WriteSocks5Response(c, 8, model.AddrSpec{IP: net.IPv4zero})
		return
	}
	ctx, cancel := context.WithTimeout(context.Background(), 20*time.Second)
	remote, err := m.client.DialContext(ctx, dst)
	cancel()
	if err != nil {
		model.WriteSocks5Response(c, 5, model.AddrSpec{IP: net.IPv4zero})
		return
	}
	defer remote.Close()
	if err := model.WriteSocks5Response(c, 0, model.AddrSpec{IP: net.IPv4zero}); err != nil {
		return
	}
	c.SetDeadline(time.Time{})
	done := make(chan struct{}, 2)
	go func() { io.Copy(remote, c); closeWrite(remote); done <- struct{}{} }()
	go func() { io.Copy(c, remote); closeWrite(c); done <- struct{}{} }()
	<-done
	<-done
}

func closeWrite(c net.Conn) {
	if w, ok := c.(interface{ CloseWrite() error }); ok {
		w.CloseWrite()
	} else {
		c.Close()
	}
}
