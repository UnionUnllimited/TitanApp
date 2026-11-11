// titan-icmp-gw: the node side of the emergency ICMP transport. It wraps the tunnel's
// UDP datagrams in ICMP echo for a node whose IP answers ICMP while TCP and UDP to it
// are blocked. The Darwin client (the Go "titan" package inside libXray) points a
// UDP-based Xray outbound — WireGuard, Hysteria2 or VLESS/VMess over mKCP — at a local
// port, wraps each datagram in an ICMP Echo Request with our 8-byte header (see
// proto.go) and sends it to the node; this daemon unwraps it and forwards the inner
// datagram over UDP to the node's existing inbound on 127.0.0.1. Replies travel back
// as Echo Replies.
//
//	titan-icmp-gw -inner 127.0.0.1:51820
//
// The shim is a dumb datagram pipe: no reliability, ordering or framing beyond the
// header. The inner protocol handles loss. See the README for how the node selects a
// server for this transport ("icmp" in the name or the outbound's "titan" field), how
// to grant the socket permission, and how to keep monitoring working.
package main

import (
	"flag"
	"log"
	"os"
	"os/signal"
	"syscall"
	"time"
)

// TITAN_DEBUG=1: log inner addresses and per-datagram errors. Off by default so user
// addresses do not land in the log beyond the session open/close lines.
var debug = os.Getenv("TITAN_DEBUG") != ""

func main() {
	var (
		inner       = flag.String("inner", "127.0.0.1:51820", "inner inbound address (UDP) to forward to")
		listen      = flag.String("listen", "0.0.0.0", "local IPv4 address for the raw ICMP socket")
		slotTTL     = flag.Duration("slot-ttl", 250*time.Millisecond, "hold an unspent reply slot this long, then answer empty")
		idle        = flag.Duration("idle-timeout", 120*time.Second, "close a session after this long with no traffic")
		maxDatagram = flag.Int("max-datagram", 1500, "largest inner datagram accepted")
		slotQueue   = flag.Int("slot-queue", 64, "max outstanding reply slots per session")
		downQueue   = flag.Int("down-queue", 256, "max buffered downstream datagrams per session")
		manage      = flag.Bool("manage-icmp-ignore", true, "set net.ipv4.icmp_echo_ignore_all=1 while running")
	)
	flag.Parse()

	if *manage {
		ign, err := enableEchoIgnore()
		if err != nil {
			log.Printf("could not set %s (%v); set it by hand or add an nft rule so the "+
				"kernel does not answer echo requests the daemon handles", ignorePath, err)
		} else {
			defer ign.restore()
			log.Printf("%s set to 1 (was %q), restored on exit", ignorePath, ign.prev)
		}
	}

	tr, err := listenICMP(*listen, *maxDatagram)
	if err != nil {
		log.Fatal(err)
	}

	srv := New(tr, Config{
		Inner:       *inner,
		SlotTTL:     *slotTTL,
		IdleTimeout: *idle,
		MaxDatagram: *maxDatagram,
		SlotQueue:   *slotQueue,
		DownQueue:   *downQueue,
	}, nil)

	go func() {
		stop := make(chan os.Signal, 1)
		signal.Notify(stop, os.Interrupt, syscall.SIGTERM)
		<-stop
		log.Print("shutting down")
		srv.Close()
	}()

	log.Printf("titan-icmp-gw on %s, inner -> %s", *listen, *inner)
	if err := srv.Serve(); err != nil {
		// A closed socket on shutdown is expected.
		log.Printf("stopped: %v", err)
	}
}
