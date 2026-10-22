"""Titan patch for Xray-core, iOS only: the TUN's gVisor stack within the tunnel's ~50 MB.

- TCP buffers: 32 KiB to start, 128 KiB (send) and 256 KiB (receive) at most, instead of
  1 MiB and 6 / 8 MiB. rcvWnd 0 gave every accepted connection a 1 MiB receive buffer.
- At most 1024 TCP connections through the TUN at once, not 65535: Xray completes the
  forwarder's request only when its connection ends, so maxInFlight counts live connections.
  Past it new SYNs are dropped until one closes (the memory limit comes well before).
- The stack's Close ends its UDP flows too: a restart frees them (and the old instance)
  now instead of after connIdle.
"""
import pathlib, re, sys

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
    udp.write_text(udp_code)
else:
    print("note: UDP flows are not closed with the stack (udp_fullcone.go is not as expected)")
stack.write_text(code)
print("xray-core memory patch applied")
