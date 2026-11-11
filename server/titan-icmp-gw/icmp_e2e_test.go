//go:build e2e

// End-to-end loopback test over a real raw ICMP socket. It needs CAP_NET_RAW and the
// ability to set net.ipv4.icmp_echo_ignore_all, so it is kept behind the "e2e" build
// tag and never runs in a normal `go test` or in CI. Run it by hand on a Linux host as
// root:
//
//	go test -tags e2e -run TestE2E -v
//
// It starts the real daemon on 127.0.0.1 with a UDP echo server as the inner inbound,
// then acts as the client: it sends an ICMP echo request carrying the shim header and a
// payload, and checks the daemon forwards it to the inner inbound and returns the echo
// as an ICMP echo reply.
package main

import (
	"bytes"
	"net"
	"os"
	"testing"
	"time"

	"golang.org/x/net/icmp"
	"golang.org/x/net/ipv4"
)

func TestE2ELoopback(t *testing.T) {
	// The daemon needs to silence the kernel's own echo answers; if we cannot, the
	// kernel would answer the client first and the test would be meaningless.
	ign, err := enableEchoIgnore()
	if err != nil {
		t.Skipf("cannot set %s (need root): %v", ignorePath, err)
	}
	defer ign.restore()

	// Inner inbound: a UDP echo server on loopback.
	ua, err := net.ResolveUDPAddr("udp", "127.0.0.1:0")
	if err != nil {
		t.Fatal(err)
	}
	inner, err := net.ListenUDP("udp", ua)
	if err != nil {
		t.Fatal(err)
	}
	defer inner.Close()
	go func() {
		buf := make([]byte, 2048)
		for {
			n, from, err := inner.ReadFromUDP(buf)
			if err != nil {
				return
			}
			inner.WriteToUDP(buf[:n], from) // echo
		}
	}()

	// The daemon on loopback.
	tr, err := listenICMP("127.0.0.1", 1500)
	if err != nil {
		t.Skipf("cannot open raw ICMP socket (need CAP_NET_RAW): %v", err)
	}
	srv := New(tr, Config{Inner: inner.LocalAddr().String(), SlotTTL: 500 * time.Millisecond}, nil)
	go srv.Serve()
	defer srv.Close()

	// Client. Prefer the unprivileged datagram socket ("udp4"), which is what the Darwin
	// client uses (the kernel owns the identifier there); fall back to a raw socket.
	var allowEveryGroup bool
	if data, err := os.ReadFile("/proc/sys/net/ipv4/ping_group_range"); err == nil {
		if string(bytes.TrimSpace(data)) != "0 2147483647" {
			if os.WriteFile("/proc/sys/net/ipv4/ping_group_range", []byte("0 2147483647"), 0o644) == nil {
				allowEveryGroup = true
				defer os.WriteFile("/proc/sys/net/ipv4/ping_group_range", data, 0o644)
			}
		}
	}
	network, typ := "udp4", "datagram"
	cl, err := icmp.ListenPacket("udp4", "127.0.0.1")
	if err != nil {
		network, typ = "ip4:icmp", "raw"
		cl, err = icmp.ListenPacket("ip4:icmp", "127.0.0.1")
		if err != nil {
			t.Fatalf("client listen: %v", err)
		}
	}
	defer cl.Close()
	t.Logf("client socket: %s (%s), every-group ping %v", network, typ, allowEveryGroup)

	payload := []byte("e2e over icmp, inner payload")
	req := icmp.Message{
		Type: ipv4.ICMPTypeEcho, Code: 0,
		Body: &icmp.Echo{ID: 0xBEEF, Seq: 1, Data: Encode(Header{Session: 0x1234, Seq: 0}, payload)},
	}
	wire, err := req.Marshal(nil)
	if err != nil {
		t.Fatal(err)
	}
	// "udp4" wants a *net.UDPAddr; "ip4:icmp" wants a *net.IPAddr.
	var dst net.Addr = &net.IPAddr{IP: net.IPv4(127, 0, 0, 1)}
	if network == "udp4" {
		dst = &net.UDPAddr{IP: net.IPv4(127, 0, 0, 1)}
	}
	if _, err := cl.WriteTo(wire, dst); err != nil {
		t.Fatal(err)
	}

	cl.SetReadDeadline(time.Now().Add(3 * time.Second))
	buf := make([]byte, 2048)
	for {
		n, _, err := cl.ReadFrom(buf)
		if err != nil {
			t.Fatalf("no shim echo reply: %v", err)
		}
		msg, err := icmp.ParseMessage(protocolICMP, buf[:n])
		if err != nil || msg.Type != ipv4.ICMPTypeEchoReply {
			continue
		}
		echo, ok := msg.Body.(*icmp.Echo)
		if !ok || !IsOurs(echo.Data) {
			continue
		}
		h, got, err := Decode(echo.Data)
		if err != nil {
			t.Fatal(err)
		}
		if h.Session != 0x1234 {
			t.Fatalf("reply session %#x, want 0x1234", h.Session)
		}
		if !bytes.Equal(got, payload) {
			t.Fatalf("inner round trip: got %q, want %q", got, payload)
		}
		t.Logf("ok: %d bytes echoed through the inner inbound over ICMP", len(got))
		return
	}
}
