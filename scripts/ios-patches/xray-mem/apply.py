"""Titan patch for Xray-core, iOS only: Xray within the tunnel's ~50 MB.

The TUN's gVisor stack:
- TCP buffers: 32 KiB to start, 128 KiB (send) and 256 KiB (receive) at most, instead of
  1 MiB and 6 / 8 MiB. rcvWnd 0 gave every accepted connection a 1 MiB receive buffer.
- At most 1024 TCP connections through the TUN at once, not 65535: Xray completes the
  forwarder's request only when its connection ends, so maxInFlight counts live connections.
  Past it new SYNs are dropped until one closes (the memory limit comes well before).
- The stack's Close ends its UDP flows too: a restart frees them (and the old instance)
  now instead of after connIdle.

Transports and the observatory (the *_mem.go files, copied in as titan_mem.go):
- XHTTP, gRPC and Hysteria pools closed on request (internet.TitanCloseTransports, titan's
  closeTransports after stopXray): keyed by the instance's own stream settings, a restart
  left the old instance's whole set alive. A retired XMUX client's idle HTTP/2 connection
  closes once its last streams have ended, not after IdleConnTimeout. Hysteria's cleaner no
  longer waits for a dialing client under the pool's lock (closeTransports waited with it).
- Pooled connections with no stream close after internet.TitanIdleAfter (60 s): XHTTP's
  HTTP/2 (300 s before), gRPC's ClientConn goes IDLE (grpc-go's 30 min), Hysteria's QUIC with
  no stream and no UDP session (never before: the panel's keep-alive). Only the balancers'
  picks carry traffic; the other subjects' connections were kept for the next probe. The
  observatory's probes (their context marked, internet.TitanProbe) don't use the pools: each
  gets a connection of its own (an XHTTP client with no keep-alive, a gRPC ClientConn, a
  Hysteria client, for a stream or a UDP session), closed as it ends, so every probe is cold, as
  TCP REALITY's always were, and leastLoad's deviations aren't a mix of warm and cold probes.
  gRPC dials on grpc-go's context, not on that of the proxied connection that made the
  ClientConn (once that one ended, every later dial failed at once), with the flow's values
  copied into it, a probe's mark too (a dialerProxy hop's transport gives the probe its own as
  well). A closed XHTTP connection lets go of its REALITY connection (Go's HTTP/2 client keeps
  itself until its keep-alive timer fires), and REALITY drops uTLS's key share private keys
  after the handshake (ML-KEM-768, ~8 KiB).
  A QUIC transport (Hysteria, XHTTP/3) closes its packet conn before itself: over a dialerProxy
  hop's (it ignores read deadlines) quic.Transport.Close waited for the hop's flow to end
  (Hysteria's under the client's lock). A failed XHTTP/3 dial closes its socket and transport.
  XHTTP's HTTP/2 dials one connection at a time for each XMUX client, as x/net's own pool did
  (Go 1.27's net/http pool dialed one for every request waiting: a burst of flows on a cold
  client made a handshake each), each dial bounded (20 s); the requests waiting behind two dials
  that failed slowly fail with them.
- Receive windows: XHTTP's HTTP/2 1 MiB per stream / 16 MiB per connection (Go: 4 MiB / 1 GiB),
  16 KiB frames both ways (the server's 1 MiB frame size gave each stream-up upload a 512 KiB
  buffer); gRPC fixed 1 / 4 MiB (the BDP estimate grows to 16 MiB); QUIC 1 / 16 MiB (with the
  quic-go patch, quic_go.py; a new stream still opens with Chrome's 6, a fresh connection with
  its 15). The connections' windows leave the other streams room next to a few that stall
  (16 MiB: 12 stalled HTTP/2 streams of 1 MiB, two new QUIC ones of 6); past half of it the
  patched quic-go cancels stalled QUIC streams (a backlog over 64 KiB for 2 s, read or not),
  and it copies small frames out of their packet buffers. Which servers are used doesn't
  change.
- The burst observatory probes at most 4 subjects at once in its rounds (all 60-85 together at
  each start before); the first check at a start 12 at once, starting in its own order: the
  subjects' groups (subjectSelector prefixes) taken in turn, each shuffled, so every group
  is in the first 12. Its probes' context is marked as theirs (above).
- Battery: while the device sleeps (titan sleep / wake, from the tunnel's sleep and wake:
  burst.TitanSleep / TitanWake) the observatory's rounds probe nothing (~60 subjects at 5m x 5, a
  cold handshake every ~5 s, kept the cellular radio up); a first check still runs; the results stay
  and don't age then (validity counts awake time); on wake the schedule goes on at once, after 5 min
  or more asleep with a wake round (every subject once, 5-30 s after it, 4 at once). Hysteria's
  pool cleaner runs every TitanIdleAfter / 6 (10 s), not every second for the process's life.
  libXray's own loop (apply_libxray.py) frees Go's memory once a minute, not every second.
Every anchor must be found: a different Xray-core stops the build.
"""
import pathlib, re, shutil, sys

src = pathlib.Path(sys.argv[1])
tun = src / "proxy/tun"
stack = tun / "stack_gvisor.go"
if not stack.exists():
    print("xray-core memory patch skipped: proxy/tun/stack_gvisor.go not found (another Xray-core)")
    sys.exit(0)
MARK = "// Titan patch: iOS memory."
code = stack.read_text()
if MARK in code:
    print("xray-core memory patch already applied")
    sys.exit(0)


def edit(name, code, old, new):
    if code.count(old) != 1:
        sys.exit(name + ": anchor not found: " + old.strip())
    return code.replace(old, new)


# TCP buffer sizes: the constant whatever upstream's value is now.
for name, value in [("tcpRXBufDefSize", "32 << 10"), ("tcpRXBufMaxSize", "256 << 10"),
                    ("tcpTXBufDefSize", "32 << 10"), ("tcpTXBufMaxSize", "128 << 10")]:
    pattern = re.compile(r"^\t" + name + r"[ \t]*=.*$", re.M)
    if len(pattern.findall(code)) != 1:
        sys.exit("stack_gvisor.go: constant not found: " + name)
    code = pattern.sub("\t" + name + " = " + value, code)
code = edit("stack_gvisor.go", code, "const (\n\tdefaultNIC tcpip.NICID = 1\n",
            MARK + " Smaller TCP buffers (see scripts/ios-patches/xray-mem).\nconst (\n\tdefaultNIC tcpip.NICID = 1\n")
code = edit("stack_gvisor.go", code, "tcp.NewForwarder(ipStack, 0, 65535, ",
            "tcp.NewForwarder(ipStack, 64<<10, 1024, ")

# UDP flows closed with the stack, only on the structure this was checked against: flows in
# udpConns, each ended by closing its egress (readers get EOF), under the handler's lock.
udp = tun / "udp_fullcone.go"
udp_code = udp.read_text() if udp.exists() else ""
udp_ok = "titanCloseAll" not in udp_code and all(udp_code.count(s) == 1 for s in [
    "\tudpConns map[net.Destination]*udpConn\n",
    "\tif u.udpConns[conn.src] == conn {\n\t\tdelete(u.udpConns, conn.src)\n\t\tclose(conn.egress)\n\t}\n",
    "\tegress chan *packet\n",
]) and code.count("\tudpForwarder := newUdpConnectionHandler(") == 1
if udp_ok:
    code = edit("stack_gvisor.go", code, "\tendpoint    stack.LinkEndpoint\n}\n",
                "\tendpoint    stack.LinkEndpoint\n\n"
                "\t// Titan patch: its UDP flows, ended in Close.\n\tudpFlows *udpConnectionHandler\n}\n")
    code, n = re.subn(r"^(\tudpForwarder := newUdpConnectionHandler\(.*\)\n)", r"\1\tt.udpFlows = udpForwarder\n", code, flags=re.M)
    if n != 1:
        sys.exit("stack_gvisor.go: anchor not found: udpForwarder := newUdpConnectionHandler(...)")
    code = edit("stack_gvisor.go", code, "\t\tendpoint.Abort()\n\t}\n",
                "\t\tendpoint.Abort()\n\t}\n"
                "\t// Titan patch: the UDP flows end too, not after connIdle.\n"
                "\tif t.udpFlows != nil {\n\t\tt.udpFlows.titanCloseAll()\n\t}\n")
    udp_code += """
// titanCloseAll ends every flow (Titan patch): its reader gets EOF, so its outbound ends.
// A flow's own Close later finds it gone.
func (u *udpConnectionHandler) titanCloseAll() {
	u.Lock()
	defer u.Unlock()
	for src, conn := range u.udpConns {
		delete(u.udpConns, src)
		close(conn.egress)
	}
}
"""
else:
    print("note: UDP flows are not closed with the stack (udp_fullcone.go is not as expected)")

# Transports and the observatory. Every edit is checked before any file is written.
here = pathlib.Path(__file__).parent
changes = {
    "app/observatory/burst/healthping.go": [
        ("\tticker        *time.Ticker\n",
         "\tticker        *time.Ticker\n\t// Titan patch: the rounds' probes running now, the subjects' groups (titan_mem.go).\n"
         "\ttitanSlots     chan struct{}\n\ttitanSelectors []string\n"),
        ("\t\tdispatcher: dispatcher,\n",
         "\t\tdispatcher: dispatcher,\n\t\ttitanSlots: make(chan struct{}, titanProbesAtOnce),\n"),
        ("\tcount := len(tags) * rounds\n\tif count == 0 {\n\t\treturn\n\t}\n",
         "\t// Titan patch: the first check in its own order, each tag once, with slots of its own\n"
         "\t// (titan_mem.go).\n"
         "\ttags, slots, end := h.titanCheck(tags, duration)\n\tdefer end()\n"
         "\tcount := len(tags) * rounds\n\tif count == 0 {\n\t\treturn\n\t}\n"),
        ("\t\t\ttimers = append(timers, time.AfterFunc(delay, func() {\n"
         "\t\t\t\terrors.LogDebug(h.ctx, \"checking \", handler)\n"
         "\t\t\t\tdelay, err := client.MeasureDelay(h.Settings.HttpMethod)\n",
         "\t\t\ttimers = append(timers, time.AfterFunc(delay, func() {\n"
         "\t\t\t\t// Titan patch: a few probes at once (titan_mem.go).\n"
         "\t\t\t\tif !h.titanSlot(ctx, slots, handler) {\n\t\t\t\t\treturn\n\t\t\t\t}\n"
         "\t\t\t\terrors.LogDebug(h.ctx, \"checking \", handler)\n"
         "\t\t\t\tdelay, err := client.MeasureDelay(h.Settings.HttpMethod)\n"
         "\t\t\t\th.titanFree(slots)\n"),
        ("\t\tclient := newPingClient(\n\t\t\th.ctx,\n",
         "\t\tclient := newPingClient(\n"
         "\t\t\t// Titan patch: the probes' flows marked as theirs (titan_mem.go).\n"
         "\t\t\ttitanProbeContext(h.ctx),\n"),
        # The device's sleep (titan_mem.go): a round under way stops, the ticks' rounds do nothing
        # (titanCheck), a wake is a tick (with a wake round after a long sleep).
        ("\t\tcase <-ctx.Done():\n\t\t\tfor _, timer := range timers {\n\t\t\t\ttimer.Stop()\n\t\t\t}\n\t\t\treturn\n\t\t}\n",
         "\t\tcase <-slots.titanAsleep(): // Titan patch: the device went to sleep (titan_mem.go).\n"
         "\t\t\tfor _, timer := range timers {\n\t\t\t\ttimer.Stop()\n\t\t\t}\n\t\t\treturn\n"
         "\t\tcase <-ctx.Done():\n\t\t\tfor _, timer := range timers {\n\t\t\t\ttimer.Stop()\n\t\t\t}\n\t\t\treturn\n\t\t}\n"),
        ("\t\t\tselect {\n\t\t\tcase <-ticker.C:\n\t\t\t\tcontinue\n\t\t\tcase <-h.ctx.Done():\n\t\t\t\treturn\n\t\t\t}\n",
         "\t\t\t// Titan patch: a wake is a tick too, after a long sleep with a wake round (titan_mem.go).\n"
         "\t\t\tif !h.titanWait(ticker, selector) {\n\t\t\t\treturn\n\t\t\t}\n"),
    ],
    # The results don't age while the device sleeps (titan_mem.go titanAge): a night asleep left
    # none valid (interval × sampling × 2), every subject not alive.
    "app/observatory/burst/healthping_result.go": [
        ("type pingRTT struct {\n\ttime  time.Time\n\tvalue time.Duration\n}\n",
         "type pingRTT struct {\n\ttime  time.Time\n\tvalue time.Duration\n"
         "\t// Titan patch: the device's sleep so far when it was measured (titan_mem.go).\n"
         "\ttitanSlept time.Duration\n}\n"),
        ("\th.rtts[h.idx].time = now\n\th.rtts[h.idx].value = d\n",
         "\th.rtts[h.idx].time = now\n\th.rtts[h.idx].value = d\n"
         "\th.rtts[h.idx].titanSlept = titanSlept() // Titan patch\n"),
        ("\tvalidRTTs := make([]time.Duration, 0)\n\tfor _, rtt := range h.rtts {\n\t\tswitch {\n"
         "\t\tcase rtt.value == rttUntested || time.Since(rtt.time) > h.validity:\n",
         "\tvalidRTTs := make([]time.Duration, 0)\n\tslept := titanSlept() // Titan patch\n"
         "\tfor _, rtt := range h.rtts {\n\t\tswitch {\n"
         "\t\tcase rtt.value == rttUntested || titanAge(rtt, slept) > h.validity: // Titan patch: titanAge\n"),
    ],
    # uTLS keeps the ClientHello's key share private keys in the UConn's handshake state after the
    # handshake (u_handshake_client.go toPublic13): the ML-KEM-768 decapsulation key, ~8 KiB, on
    # every REALITY connection for its whole life. Nothing reads them after it (REALITY's AuthKey
    # comes from them before; KeyUpdate and tickets use the traffic secrets).
    "transport/internet/reality/reality.go": [
        ("\tif err := uConn.HandshakeContext(ctx); err != nil {\n\t\treturn nil, err\n\t}\n",
         "\tif err := uConn.HandshakeContext(ctx); err != nil {\n\t\treturn nil, err\n\t}\n"
         "\t// Titan patch: the key shares' private keys (ML-KEM-768: ~8 KiB) aren't needed any more.\n"
         "\tuConn.HandshakeState.State13.KeyShareKeys = nil\n"),
    ],
    "app/observatory/burst/burstobserver.go": [
        ("\thp := NewHealthPing(ctx, dispatcher, config.PingConfig)\n",
         "\thp := NewHealthPing(ctx, dispatcher, config.PingConfig)\n"
         "\t// Titan patch: the subjects' groups, for the first check's order (titan_mem.go).\n"
         "\thp.titanSelectors = config.SubjectSelector\n"),
    ],
    "transport/internet/splithttp/dialer.go": [
        ('\t"golang.org/x/net/http2"\n', ""),
        ("\t\ttransport = &http2.Transport{\n"
         "\t\t\tDialTLSContext: func(ctxInner context.Context, network string, addr string, cfg *gotls.Config) (net.Conn, error) {\n"
         "\t\t\t\treturn dialContext(ctxInner)\n"
         "\t\t\t},\n"
         "\t\t\tIdleConnTimeout: net.ConnIdleTimeout,\n"
         "\t\t\tReadIdleTimeout: keepAlivePeriod,\n"
         "\t\t}\n",
         "\t\t// Titan patch: smaller windows and frames, connections tracked, closed sooner idle\n"
         "\t\t// (titan_mem.go).\n"
         "\t\ttransport = titanH2Transport(dialContext, streamSettings, internet.TitanIdleAfter, keepAlivePeriod)\n"),
        ("\t\t\tquicConfig.MaxIncomingStreams = -1\n\t\t}\n",
         "\t\t\tquicConfig.MaxIncomingStreams = -1\n\t\t}\n"
         "\t\t// Titan patch: smaller receive windows.\n\t\tinternet.TitanQUICWindows(quicConfig)\n"),
        # quic.Transport.Close sets a read deadline on the packet conn and waits for its reader:
        # a dialerProxy hop's (cnc through FakePacketConn) ignores deadlines, so Close waited for
        # the hop's flow to end (connIdle) and the socket was closed only after. The packet conn
        # first: the connection has sent its CONNECTION_CLOSE by then. A failed dial left both
        # (the socket and the transport's reader) for good.
        ("\t\t\t\tconn, err := tr.DialEarly(ctx, udpAddr, tlsCfg, cfg)\n\t\t\t\tif err != nil {\n\t\t\t\t\treturn nil, err\n\t\t\t\t}\n"
         "\t\t\t\tcontext.AfterFunc(conn.Context(), func() { tr.Close(); pktConn.Close() })\n",
         "\t\t\t\tconn, err := tr.DialEarly(ctx, udpAddr, tlsCfg, cfg)\n\t\t\t\tif err != nil {\n"
         "\t\t\t\t\t// Titan patch: the socket and the transport go with a failed dial.\n"
         "\t\t\t\t\tpktConn.Close()\n\t\t\t\t\ttr.Close()\n\t\t\t\t\treturn nil, err\n\t\t\t\t}\n"
         "\t\t\t\t// Titan patch: the packet conn first (a dialerProxy hop's ignores tr.Close's deadline).\n"
         "\t\t\t\tcontext.AfterFunc(conn.Context(), func() { pktConn.Close(); tr.Close() })\n"),
        # The observatory's probes don't use the pool: a client of their own each, closed as the
        # probe ends (titan_mem.go).
        ("\tglobalDialerAccess.Lock()\n\tdefer globalDialerAccess.Unlock()\n\n\tif globalDialerMap == nil {\n",
         "\t// Titan patch: a probe's flow gets a client of its own, closed as it ends (titan_mem.go).\n"
         "\tif internet.TitanProbe(ctx) {\n\t\treturn titanProbeClient(dest, streamSettings), nil\n\t}\n\n"
         "\tglobalDialerAccess.Lock()\n\tdefer globalDialerAccess.Unlock()\n\n\tif globalDialerMap == nil {\n"),
        ("\tvar closed atomic.Int32\n\n\treader, writer := io.Pipe()\n",
         "\t// Titan patch: a probe's clients close with it (titan_mem.go).\n"
         "\ttitanEnd := titanProbeEnd(ctx, httpClient, httpClient2)\n"
         "\tvar closed atomic.Int32\n\n\treader, writer := io.Pipe()\n"),
        ("\t\t\tif xmuxClient2 != nil && xmuxClient2 != xmuxClient {\n\t\t\t\txmuxClient2.DoneRunning()\n\t\t\t}\n\t\t},\n\t}\n",
         "\t\t\tif xmuxClient2 != nil && xmuxClient2 != xmuxClient {\n\t\t\t\txmuxClient2.DoneRunning()\n\t\t\t}\n"
         "\t\t\ttitanEnd() // Titan patch\n\t\t},\n\t}\n"),
    ],
    "transport/internet/splithttp/client.go": [
        ("\tif h3Transport, ok := transport.(*http3.Transport); ok {\n\t\th3Transport.Close()\n\t}\n\treturn nil\n}\n",
         "\tif h3Transport, ok := transport.(*http3.Transport); ok {\n\t\th3Transport.Close()\n\t} else {\n"
         "\t\t// Titan patch: a retired client's idle HTTP/2 connection closes now, not idle later\n"
         "\t\t// (titan_mem.go).\n"
         "\t\tc.titanRetire()\n\t}\n\treturn nil\n}\n"),
    ],
    "transport/internet/grpc/dial.go": [
        ("\tif grpcSettings.InitialWindowsSize > 0 {\n"
         "\t\tdialOptions = append(dialOptions, grpc.WithInitialWindowSize(grpcSettings.InitialWindowsSize))\n\t}\n",
         "\tif grpcSettings.InitialWindowsSize > 0 {\n"
         "\t\tdialOptions = append(dialOptions, grpc.WithInitialWindowSize(grpcSettings.InitialWindowsSize))\n\t}\n"
         "\t// Titan patch: fixed windows, idle after a minute without RPCs (titan_mem.go).\n"
         "\tdialOptions = titanDialOptions(dialOptions, grpcSettings.InitialWindowsSize)\n"),
        # The ClientConn dials again whenever it connects (after IDLE above, a lost connection),
        # but dialed on the context of the proxied connection that made it: once that one ended
        # (any short flow of an app), every later dial failed at once ("operation was canceled")
        # and the RPCs with it. grpc-go's dial context, with the values copied into it above (as
        # finalmask's dial has it).
        ("\t\t\t\tc, err = internet.DialSystem(ctx, dest, streamSettings.SocketSettings)\n",
         "\t\t\t\t// Titan patch: on grpc-go's dial context, not the first proxied connection's.\n"
         "\t\t\t\tc, err = internet.DialSystem(gctx, dest, streamSettings.SocketSettings)\n"),
        # A probe's mark copied too: with sockopt.dialerProxy (or finalmask's dial) DialSystem hands
        # the flow to the hop's outbound on gctx, and without the mark the hop's XHTTP / gRPC /
        # Hysteria put the probe's flow in their pools (warm for the next probe, XMUX's 3 a hop).
        # ctx is a probe's only in a probe's own ClientConn: probes never make pooled ones.
        ("\t\t\tgctx = session.ContextWithTimeoutOnly(gctx, true)\n",
         "\t\t\tgctx = session.ContextWithTimeoutOnly(gctx, true)\n"
         "\t\t\tif internet.TitanProbe(ctx) { // Titan patch: the probe's mark for a dialerProxy hop\n"
         "\t\t\t\tgctx = internet.TitanProbeContext(gctx)\n\t\t\t}\n"),
        # The observatory's probes don't use the pool: a ClientConn of their own each, closed as the
        # probe ends (titan_mem.go).
        ("\tif client, found := globalDialerMap[dialerConf{dest, streamSettings}]; found && client.GetState() != connectivity.Shutdown {\n",
         "\t// Titan patch: a probe's flow gets a ClientConn of its own, closed as it ends (titan_mem.go).\n"
         "\tprobe := internet.TitanProbe(ctx)\n"
         "\tif client, found := globalDialerMap[dialerConf{dest, streamSettings}]; found && !probe && client.GetState() != connectivity.Shutdown {\n"),
        ("\tglobalDialerMap[dialerConf{dest, streamSettings}] = conn\n\treturn conn, err\n}\n",
         "\tif probe { // Titan patch\n\t\tif err == nil {\n\t\t\ttitanProbeConns[conn] = struct{}{}\n\t\t}\n\t\treturn conn, err\n\t}\n"
         "\tglobalDialerMap[dialerConf{dest, streamSettings}] = conn\n\treturn conn, err\n}\n"),
        ("\tclient := encoding.NewGRPCServiceClient(conn)\n",
         "\t// Titan patch: what the flow's close calls, a probe's ClientConn closed (titan_mem.go).\n"
         "\ttitanEnd := titanDone(ctx, conn)\n"
         "\tclient := encoding.NewGRPCServiceClient(conn)\n"),
        ("\t\tif err != nil {\n\t\t\treturn nil, errors.New(\"Cannot dial gRPC\").Base(err)\n\t\t}\n"
         "\t\treturn encoding.NewMultiHunkConn(grpcService, nil, nil), nil\n",
         "\t\tif err != nil {\n\t\t\ttitanEnd() // Titan patch\n\t\t\treturn nil, errors.New(\"Cannot dial gRPC\").Base(err)\n\t\t}\n"
         "\t\treturn encoding.NewMultiHunkConn(grpcService, titanEnd, nil), nil // Titan patch: titanEnd\n"),
        ("\tif err != nil {\n\t\treturn nil, errors.New(\"Cannot dial gRPC\").Base(err)\n\t}\n\n"
         "\treturn encoding.NewHunkConn(grpcService, nil, nil), nil\n",
         "\tif err != nil {\n\t\ttitanEnd() // Titan patch\n\t\treturn nil, errors.New(\"Cannot dial gRPC\").Base(err)\n\t}\n\n"
         "\treturn encoding.NewHunkConn(grpcService, titanEnd, nil), nil // Titan patch: titanEnd\n"),
    ],
    "transport/internet/hysteria/dialer.go": [
        # The packet conn before the transport (as XHTTP/3's above): with a dialerProxy hop
        # tr.Close waited for the hop's flow to end, under the client's lock (a probe's close, the
        # cleaner's idle close: the subject's next flows and every client's clean waited).
        ("\tc.conn.CloseWithError(closeErrCodeOK, \"\")\n\tc.tr.Close()\n\tc.pktConn.Close()\n",
         "\tc.conn.CloseWithError(closeErrCodeOK, \"\")\n"
         "\t// Titan patch: the packet conn first (a dialerProxy hop's ignores tr.Close's deadline).\n"
         "\tc.pktConn.Close()\n\tc.tr.Close()\n"),
        ("\t\t_ = tr.Close()\n\t\t_ = pktConn.Close()\n\t\treturn err\n",
         "\t\t_ = pktConn.Close() // Titan patch: first, as in close\n\t\t_ = tr.Close()\n\t\treturn err\n"),
        ("\t\t_ = tr.Close()\n\t\t_ = pktConn.Close()\n\t\treturn errors.New(\"auth failed code \", resp.StatusCode)\n",
         "\t\t_ = pktConn.Close() // Titan patch: first, as in close\n\t\t_ = tr.Close()\n"
         "\t\treturn errors.New(\"auth failed code \", resp.StatusCode)\n"),
        ("\tif quicParams.MaxIdleTimeout == 0 {\n\t\tquicConfig.MaxIdleTimeout = 30 * time.Second\n\t}\n",
         "\tif quicParams.MaxIdleTimeout == 0 {\n\t\tquicConfig.MaxIdleTimeout = 30 * time.Second\n\t}\n"
         "\t// Titan patch: smaller receive windows.\n\tinternet.TitanQUICWindows(quicConfig)\n"),
        ("\t\tm.RLock()\n\t\tfor _, c := range m.m {\n\t\t\tc.clean()\n\t\t}\n\t\tm.RUnlock()\n",
         "\t\t// Titan patch: cleaned outside the pool's lock (titan_mem.go).\n"
         "\t\tfor _, c := range m.titanClients() {\n\t\t\tc.clean()\n\t\t}\n"),
        # Every TitanIdleAfter / 6 (10 s), not every second: a wake a second for as long as the process
        # lives, once any Hysteria subject was dialed (titan_mem.go titanCleanWait).
        ("\tticker := time.NewTicker(idleCleanupInterval)\n\tfor range ticker.C {\n\t\t// Titan patch: cleaned outside",
         "\t// Titan patch: every TitanIdleAfter / 6, not every second (titan_mem.go).\n"
         "\tfor titanCleanWait() {\n\t\t// Titan patch: cleaned outside"),
        # A connection with no stream and no UDP session for a minute is closed (titan_mem.go).
        ("\tudpSM   *udpSessionManager\n}\n",
         "\tudpSM   *udpSessionManager\n\n\t// Titan patch: its streams, and when it was last used (titan_mem.go).\n"
         "\ttitan titanUse\n}\n"),
        ("\tc.Lock()\n\tdefer c.Unlock()\n\n\terr := c.dial(ctx)\n\tif err != nil {\n\t\treturn nil, err\n\t}\n\n"
         "\tstream, err := c.conn.OpenStream()\n",
         "\tc.Lock()\n\tdefer c.Unlock()\n\t// Titan patch: in use (titan_mem.go).\n\tc.titanUsed()\n\n"
         "\terr := c.dial(ctx)\n\tif err != nil {\n\t\treturn nil, err\n\t}\n\n"
         "\tstream, err := c.conn.OpenStream()\n"),
        ("\treturn &interConn{\n\t\tstream: stream,\n\t\tlocal:  c.conn.LocalAddr(),\n\t\tremote: c.conn.RemoteAddr(),\n\n"
         "\t\tclient: true,\n\t}, nil\n}\n",
         "\t// Titan patch: counted until closed (titan_mem.go).\n"
         "\treturn c.titanStream(&interConn{\n\t\tstream: stream,\n\t\tlocal:  c.conn.LocalAddr(),\n\t\tremote: c.conn.RemoteAddr(),\n\n"
         "\t\tclient: true,\n\t}), nil\n}\n"),
        ("\tc.Lock()\n\tdefer c.Unlock()\n\n\terr := c.dial(ctx)\n\tif err != nil {\n\t\treturn nil, err\n\t}\n\n"
         "\treturn c.udpSM.udp()\n",
         "\tc.Lock()\n\tdefer c.Unlock()\n\t// Titan patch: in use (titan_mem.go).\n\tc.titanUsed()\n\n"
         "\terr := c.dial(ctx)\n\tif err != nil {\n\t\treturn nil, err\n\t}\n\n"
         "\treturn c.udpSM.udp()\n"),
        # The observatory's probes don't use the pool: a client (a QUIC connection) of their own
        # each, closed as the probe ends (titan_mem.go). A UDP session too: a probe's UDP leg
        # through a Hysteria dialerProxy hop (a Hysteria or XHTTP/3 subject's).
        ("\tmanager.RLock()\n\tc := manager.m[dialerConf{dest, streamSettings}]\n\tmanager.RUnlock()\n",
         "\t// Titan patch: a probe's flow gets a client of its own, closed as it ends (titan_mem.go).\n"
         "\tif internet.TitanProbe(ctx) {\n\t\treturn titanProbeDial(ctx, dest, streamSettings, tlsConfig, datagram)\n\t}\n\n"
         "\tmanager.RLock()\n\tc := manager.m[dialerConf{dest, streamSettings}]\n\tmanager.RUnlock()\n"),
        ("\tc.Lock()\n\tif c.status() == StatusInactive {\n\t\tc.close()\n\t}\n\tc.Unlock()\n",
         "\tc.Lock()\n\tif c.status() == StatusInactive {\n\t\tc.close()\n"
         "\t} else if c.titanIdle() { // Titan patch: idle a minute (titan_mem.go).\n\t\tc.close()\n\t}\n\tc.Unlock()\n"),
    ],
}
files = {"internet_mem.go": "transport/internet", "splithttp_mem.go": "transport/internet/splithttp",
         "grpc_mem.go": "transport/internet/grpc", "hysteria_mem.go": "transport/internet/hysteria",
         "burst_mem.go": "app/observatory/burst"}
patched = {}
for rel, edits in changes.items():
    text = (src / rel).read_text()
    for old, new in edits:
        text = edit(rel, text, old, new)
    patched[rel] = text
for f, rel in files.items():
    if (src / rel / "titan_mem.go").exists():
        sys.exit(rel + "/titan_mem.go exists already")
stack.write_text(code)
if udp_ok:
    udp.write_text(udp_code)
for rel, text in patched.items():
    (src / rel).write_text(text)
for f, rel in files.items():
    shutil.copy(here / f, src / rel / "titan_mem.go")
print("xray-core memory patch applied")
