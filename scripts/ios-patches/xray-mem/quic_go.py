"""Titan patch for quic-go (apernet's: Xray's Hysteria and XHTTP over HTTP/3), iOS only: the
receive windows keep to the ceilings the caller sets, Chrome parroting or not, and a few
streams nobody reads don't freeze the connection.

Chrome parroting opens every connection with Chrome's windows (6 MiB per stream, 15 MiB per
connection: transport parameters in the handshake, part of its fingerprint) and raised the
auto-tuning ceilings to them, so Xray's ceilings (and the panel's quicParams) did nothing: a
stalled stream (a player with a full buffer) had 6 MiB waiting in memory, a connection 15.
The handshake stays Chrome's; what is granted after it (MAX_STREAM_DATA / MAX_DATA) keeps
to the ceilings: the flow controllers start at the smaller of the two, the ceilings stay.

MAX_DATA came only once a quarter of the connection's window had been read. What waits on a
stream nobody reads (a paused player, a suspended app) is never read: once such streams held
more than three quarters of the window, no MAX_DATA came again and every stream of the
connection (all TCP through that server) stood until one of them closed. The connection's
window also goes up once an update would at least double what the peer has left of it (Go's
HTTP/2 rule), 64 KiB at the least: the others go on with what the stalled ones leave of the
window. The window is the same, so is what may wait unread in it.
Every anchor must be found.
"""
import pathlib, sys

src = pathlib.Path(sys.argv[1])
MARK = "// Titan patch: iOS memory."
edits = {
    "config.go": [(
        "\t\t// The auto-tuning ceilings must not sit below the starting windows.\n"
        "\t\tmaxStreamReceiveWindow = max(maxStreamReceiveWindow, initialStreamReceiveWindow)\n"
        "\t\tmaxConnectionReceiveWindow = max(maxConnectionReceiveWindow, initialConnectionReceiveWindow)\n",
        "\t\t" + MARK + " The caller's ceilings stay (the flow controllers start at the smaller).\n",
    )],
    "flow_controller_base.go": [(
        "\tallowWindowIncrease func(size protocol.ByteCount) bool\n",
        "\tallowWindowIncrease func(size protocol.ByteCount) bool\n\n"
        "\t" + MARK + " The connection's: updated when the peer runs short too (titanShort).\n"
        "\ttitanConnection bool\n",
    ), (
        "\treturn bytesRemaining <= protocol.ByteCount(float64(c.receiveWindowSize)*(1-protocol.WindowUpdateThreshold))\n}\n",
        "\treturn bytesRemaining <= protocol.ByteCount(float64(c.receiveWindowSize)*(1-protocol.WindowUpdateThreshold)) ||\n"
        "\t\tc.titanShort()\n}\n\n"
        "// titanShort (Titan patch: iOS memory): the connection's window goes up once an update would\n"
        "// at least double what the peer has left of it, by titanMinUpdate at the least. What waits\n"
        "// on streams nobody reads is never read: with a quarter of the window read alone, once they\n"
        "// held three quarters of it no update came again, and every stream stood.\n"
        "func (c *receiveFlowController) titanShort() bool {\n"
        "\tif !c.titanConnection {\n\t\treturn false\n\t}\n"
        "\tupdate := c.bytesRead + c.receiveWindowSize - c.receiveWindow\n"
        "\treturn update >= titanMinUpdate && update >= c.receiveWindow-c.highestReceived\n}\n\n"
        "const titanMinUpdate protocol.ByteCount = 64 << 10\n",
    )],
    "flow_controller_connection.go": [(
        "\t\t\treceiveWindowSize:    receiveWindow,\n",
        "\t\t\treceiveWindowSize:    min(receiveWindow, maxReceiveWindow), " + MARK + "\n"
        "\t\t\ttitanConnection:      true,\n",
    )],
    "flow_controller_stream.go": [(
        "\t\t\treceiveWindowSize:    receiveWindow,\n",
        "\t\t\treceiveWindowSize:    min(receiveWindow, maxReceiveWindow), " + MARK + "\n",
    )],
}
out = {}
for name, changes in edits.items():
    code = (src / name).read_text()
    for old, new in changes:
        if code.count(old) != 1:
            sys.exit("quic-go " + name + ": anchor not found: " + old.strip().splitlines()[0])
        code = code.replace(old, new)
    out[name] = code
for name, code in out.items():
    (src / name).write_text(code)
print("quic-go memory patch applied")
