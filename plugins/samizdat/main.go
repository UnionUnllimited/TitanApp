// samizdat-client: a local SOCKS5 proxy that tunnels every connection through a
// Samizdat server (github.com/getlantern/samizdat). The apps run it like naive/mieru
// and point Xray's proxy outbound at it (see desktop Plugins.kt / app core/Plugins.kt).
//
//	samizdat-client -listen 127.0.0.1:1080 -server host:port -sni ok.ru -pubkey <hex> -sid <hex>
//	  [-fp chrome|firefox|safari] [-frag=false] [-recfrag=false] [-jitter=false] [-v]
//
// Only TCP (SOCKS5 CONNECT); Samizdat itself carries no UDP.
package main

import (
	"context"
	"encoding/binary"
	"encoding/hex"
	"errors"
	"flag"
	"fmt"
	"io"
	"log"
	"net"
	"strconv"
	"time"

	"github.com/getlantern/samizdat"
)

func main() {
	listen := flag.String("listen", "127.0.0.1:1080", "local SOCKS5 address")
	server := flag.String("server", "", "Samizdat server host:port")
	sni := flag.String("sni", "ok.ru", "cover site (server's masquerade domain)")
	pubkey := flag.String("pubkey", "", "server X25519 public key, hex")
	sid := flag.String("sid", "", "short id, 16 hex chars")
	fp := flag.String("fp", "chrome", "TLS fingerprint: chrome, firefox, safari")
	frag := flag.Bool("frag", true, "split the ClientHello across TCP segments")
	recfrag := flag.Bool("recfrag", true, "split inner TLS records across H2 frames")
	jitter := flag.Bool("jitter", true, "timing jitter")
	verbose := flag.Bool("v", false, "log every connection")
	flag.Parse()

	pk, err := hex.DecodeString(*pubkey)
	if err != nil || len(pk) != 32 {
		log.Fatal("bad -pubkey")
	}
	var shortID [8]byte
	if b, err := hex.DecodeString(*sid); err != nil || len(b) != 8 {
		log.Fatal("bad -sid")
	} else {
		copy(shortID[:], b)
	}
	client, err := samizdat.NewClient(samizdat.ClientConfig{
		ServerAddr:          *server,
		ServerName:          *sni,
		PublicKey:           pk,
		ShortID:             shortID,
		Fingerprint:         *fp,
		Jitter:              *jitter,
		TCPFragmentation:    *frag,
		RecordFragmentation: *recfrag,
	})
	if err != nil {
		log.Fatal(err)
	}
	ln, err := net.Listen("tcp", *listen)
	if err != nil {
		log.Fatal(err)
	}
	log.Printf("socks5 on %s -> %s (fp=%s frag=%v recfrag=%v jitter=%v)", ln.Addr(), *server, *fp, *frag, *recfrag, *jitter)
	logEach = *verbose
	for {
		c, err := ln.Accept()
		if err != nil {
			log.Fatal(err)
		}
		go serve(client, c)
	}
}

var logEach bool

func serve(client *samizdat.Client, c net.Conn) {
	defer c.Close()
	_ = c.SetDeadline(time.Now().Add(30 * time.Second))
	dest, err := handshake(c)
	if err != nil {
		return
	}
	ctx, cancel := context.WithTimeout(context.Background(), 20*time.Second)
	start := time.Now()
	up, err := client.DialContext(ctx, "tcp", dest)
	cancel()
	if logEach && err == nil {
		log.Printf("%s: ok in %v", dest, time.Since(start).Round(time.Millisecond))
	}
	if err != nil {
		log.Printf("%s: %v", dest, err)
		_, _ = c.Write([]byte{5, 5, 0, 1, 0, 0, 0, 0, 0, 0}) // connection refused
		return
	}
	defer up.Close()
	if _, err := c.Write([]byte{5, 0, 0, 1, 0, 0, 0, 0, 0, 0}); err != nil {
		return
	}
	_ = c.SetDeadline(time.Time{})
	done := make(chan struct{}, 2)
	go func() { _, _ = io.Copy(up, c); closeWrite(up); done <- struct{}{} }()
	go func() { _, _ = io.Copy(c, up); closeWrite(c); done <- struct{}{} }()
	<-done
	<-done
}

func closeWrite(c net.Conn) {
	if cw, ok := c.(interface{ CloseWrite() error }); ok {
		_ = cw.CloseWrite()
	} else {
		_ = c.Close()
	}
}

// handshake reads a SOCKS5 greeting and CONNECT request (no auth) and returns host:port.
func handshake(c net.Conn) (string, error) {
	buf := make([]byte, 262)
	if _, err := io.ReadFull(c, buf[:2]); err != nil || buf[0] != 5 {
		return "", errors.New("not socks5")
	}
	if _, err := io.ReadFull(c, buf[:buf[1]]); err != nil {
		return "", err
	}
	if _, err := c.Write([]byte{5, 0}); err != nil {
		return "", err
	}
	if _, err := io.ReadFull(c, buf[:4]); err != nil {
		return "", err
	}
	if buf[1] != 1 { // only CONNECT
		_, _ = c.Write([]byte{5, 7, 0, 1, 0, 0, 0, 0, 0, 0})
		return "", errors.New("unsupported command")
	}
	var host string
	switch buf[3] {
	case 1:
		if _, err := io.ReadFull(c, buf[:4]); err != nil {
			return "", err
		}
		host = net.IP(buf[:4]).String()
	case 4:
		if _, err := io.ReadFull(c, buf[:16]); err != nil {
			return "", err
		}
		host = net.IP(buf[:16]).String()
	case 3:
		if _, err := io.ReadFull(c, buf[:1]); err != nil {
			return "", err
		}
		n := int(buf[0])
		if _, err := io.ReadFull(c, buf[:n]); err != nil {
			return "", err
		}
		host = string(buf[:n])
	default:
		return "", fmt.Errorf("bad address type %d", buf[3])
	}
	if _, err := io.ReadFull(c, buf[:2]); err != nil {
		return "", err
	}
	return net.JoinHostPort(host, strconv.Itoa(int(binary.BigEndian.Uint16(buf[:2])))), nil
}
