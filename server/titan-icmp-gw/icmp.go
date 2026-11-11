// The real Transport: a raw ICMPv4 socket. It receives echo requests and sends echo
// replies, mirroring each request's identifier and ICMP sequence (correction 2). This
// needs CAP_NET_RAW (run as root, or grant the capability); see the README.
//
// This file also silences the kernel's own echo answers (correction 4). A raw ICMP
// socket sees every echo request, but by default the kernel still answers them
// itself, so without this the daemon and the kernel would both reply. We set
// net.ipv4.icmp_echo_ignore_all=1 and this daemon answers ordinary pings from
// dispatch, so monitoring keeps working. The sysctl is restored on shutdown.
package main

import (
	"fmt"
	"net"
	"net/netip"
	"os"
	"strings"

	"golang.org/x/net/icmp"
	"golang.org/x/net/ipv4"
)

// protocolICMP is the IANA protocol number for ICMPv4, used by icmp.ParseMessage.
const protocolICMP = 1

// icmpTransport is a Transport backed by a raw ICMPv4 socket.
type icmpTransport struct {
	conn *icmp.PacketConn
	buf  []byte
}

// listenICMP opens the raw socket bound to listenIP ("0.0.0.0" for all interfaces).
// maxDatagram sizes the read buffer (shim header + inner, plus slack).
func listenICMP(listenIP string, maxDatagram int) (*icmpTransport, error) {
	conn, err := icmp.ListenPacket("ip4:icmp", listenIP)
	if err != nil {
		return nil, fmt.Errorf("listen ip4:icmp on %s: %w", listenIP, err)
	}
	return &icmpTransport{conn: conn, buf: make([]byte, maxDatagram+HeaderLen+64)}, nil
}

// Recv returns the next ICMP echo request, skipping any other ICMP traffic the raw
// socket delivers (including echo replies, which are not ours to handle).
func (t *icmpTransport) Recv() (Datagram, error) {
	for {
		n, peer, err := t.conn.ReadFrom(t.buf)
		if err != nil {
			return Datagram{}, err
		}
		msg, err := icmp.ParseMessage(protocolICMP, t.buf[:n])
		if err != nil {
			continue
		}
		if msg.Type != ipv4.ICMPTypeEcho {
			continue // only echo requests concern us
		}
		echo, ok := msg.Body.(*icmp.Echo)
		if !ok {
			continue
		}
		ipAddr, ok := peer.(*net.IPAddr)
		if !ok {
			continue
		}
		addr, ok := netip.AddrFromSlice(ipAddr.IP)
		if !ok {
			continue
		}
		return Datagram{
			Peer:    addr.Unmap(),
			ID:      uint16(echo.ID),
			ICMPSeq: uint16(echo.Seq),
			// echo.Data aliases t.buf, which Recv reuses next call; dispatch copies
			// what it keeps.
			Body: echo.Data,
		}, nil
	}
}

// SendReply sends an ICMP echo reply to d.Peer, reusing the request's identifier and
// ICMP sequence so the client's kernel and any NAT on the path deliver it to the
// originating socket.
func (t *icmpTransport) SendReply(d Datagram) error {
	msg := icmp.Message{
		Type: ipv4.ICMPTypeEchoReply,
		Code: 0,
		Body: &icmp.Echo{
			ID:   int(d.ID),
			Seq:  int(d.ICMPSeq),
			Data: d.Body,
		},
	}
	b, err := msg.Marshal(nil)
	if err != nil {
		return err
	}
	_, err = t.conn.WriteTo(b, &net.IPAddr{IP: d.Peer.AsSlice()})
	return err
}

func (t *icmpTransport) Close() error { return t.conn.Close() }

// icmpIgnore manages net.ipv4.icmp_echo_ignore_all so the kernel stops answering echo
// requests while the daemon runs.
const ignorePath = "/proc/sys/net/ipv4/icmp_echo_ignore_all"

type icmpIgnore struct {
	prev    string
	managed bool
}

// enableEchoIgnore sets icmp_echo_ignore_all=1, remembering the previous value. On any
// error (not Linux, no permission) it returns a non-managed handle and a message the
// caller logs: the owner can set the sysctl or add an nft rule by hand.
func enableEchoIgnore() (*icmpIgnore, error) {
	cur, err := os.ReadFile(ignorePath)
	if err != nil {
		return &icmpIgnore{}, fmt.Errorf("read %s: %w", ignorePath, err)
	}
	if err := os.WriteFile(ignorePath, []byte("1\n"), 0o644); err != nil {
		return &icmpIgnore{}, fmt.Errorf("write %s: %w", ignorePath, err)
	}
	return &icmpIgnore{prev: strings.TrimSpace(string(cur)), managed: true}, nil
}

// restore puts the sysctl back to what it was before enableEchoIgnore.
func (i *icmpIgnore) restore() {
	if !i.managed {
		return
	}
	_ = os.WriteFile(ignorePath, []byte(i.prev+"\n"), 0o644)
}
