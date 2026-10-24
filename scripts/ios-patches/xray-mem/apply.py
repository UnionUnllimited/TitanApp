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
  closes at once, not after 300 s idle. Hysteria's cleaner no longer waits for a dialing
  client under the pool's lock (closeTransports waited with it).
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
  is in the first 12.
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
         "\t\t// Titan patch: smaller windows and frames, connections tracked (titan_mem.go).\n"
         "\t\ttransport = titanH2Transport(dialContext, streamSettings, net.ConnIdleTimeout, keepAlivePeriod)\n"),
        ("\t\t\tquicConfig.MaxIncomingStreams = -1\n\t\t}\n",
         "\t\t\tquicConfig.MaxIncomingStreams = -1\n\t\t}\n"
         "\t\t// Titan patch: smaller receive windows.\n\t\tinternet.TitanQUICWindows(quicConfig)\n"),
    ],
    "transport/internet/splithttp/client.go": [
        ("\tif h3Transport, ok := transport.(*http3.Transport); ok {\n\t\th3Transport.Close()\n\t}\n\treturn nil\n}\n",
         "\tif h3Transport, ok := transport.(*http3.Transport); ok {\n\t\th3Transport.Close()\n\t} else {\n"
         "\t\t// Titan patch: a retired client's idle HTTP/2 connection closes now, not 300 s later.\n"
         "\t\tc.client.CloseIdleConnections()\n\t}\n\treturn nil\n}\n"),
    ],
    "transport/internet/grpc/dial.go": [
        ("\tif grpcSettings.InitialWindowsSize > 0 {\n"
         "\t\tdialOptions = append(dialOptions, grpc.WithInitialWindowSize(grpcSettings.InitialWindowsSize))\n\t}\n",
         "\tif grpcSettings.InitialWindowsSize > 0 {\n"
         "\t\tdialOptions = append(dialOptions, grpc.WithInitialWindowSize(grpcSettings.InitialWindowsSize))\n\t}\n"
         "\t// Titan patch: fixed windows (titan_mem.go).\n"
         "\tdialOptions = titanWindows(dialOptions, grpcSettings.InitialWindowsSize)\n"),
    ],
    "transport/internet/hysteria/dialer.go": [
        ("\tif quicParams.MaxIdleTimeout == 0 {\n\t\tquicConfig.MaxIdleTimeout = 30 * time.Second\n\t}\n",
         "\tif quicParams.MaxIdleTimeout == 0 {\n\t\tquicConfig.MaxIdleTimeout = 30 * time.Second\n\t}\n"
         "\t// Titan patch: smaller receive windows.\n\tinternet.TitanQUICWindows(quicConfig)\n"),
        ("\t\tm.RLock()\n\t\tfor _, c := range m.m {\n\t\t\tc.clean()\n\t\t}\n\t\tm.RUnlock()\n",
         "\t\t// Titan patch: cleaned outside the pool's lock (titan_mem.go).\n"
         "\t\tfor _, c := range m.titanClients() {\n\t\t\tc.clean()\n\t\t}\n"),
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
