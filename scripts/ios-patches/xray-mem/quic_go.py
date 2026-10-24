"""Titan patch for quic-go (apernet's: Xray's Hysteria and XHTTP over HTTP/3), iOS only: the
receive windows keep to the ceilings the caller sets, Chrome parroting or not, and streams
nobody reads don't freeze the connection. The new code is quic_mem.go (copied in as
titan_mem.go); the edits below hook it in.

Chrome parroting opens every connection with Chrome's windows (6 MiB per stream, 15 MiB per
connection: transport parameters in the handshake, part of its fingerprint) and raised the
auto-tuning ceilings to them, so Xray's ceilings (and the panel's quicParams) did nothing: a
stalled stream (a player with a full buffer) had 6 MiB waiting in memory, a connection 15.
The handshake stays Chrome's; what is granted after it (MAX_STREAM_DATA / MAX_DATA) keeps
to the ceilings: the flow controllers start at the smaller of the two. The connection's goes
to its ceiling at the first update once the handshake gave half of it or more (titanGrow):
from Chrome's 15 MiB auto-tuning hardly ever reached a 16 MiB ceiling.

What waits on a stream nobody reads (a paused player, a suspended app) or reads slower than it
comes (a player topping up its full buffer) is never read, or late, so the connection's other
streams get only what those leave of its window:
- MAX_DATA came only once a quarter of the window had been read: once such streams held more
  than three quarters of it, no MAX_DATA came again. The connection's window also goes up once
  an update would at least double what the peer has left of it (Go's HTTP/2 rule), 64 KiB at
  the least (titanShort).
- New streams open with Chrome's 6 MiB, so three that stall fill 16 MiB anyway, two that stall
  and one read slowly too: once streams whose backlog stayed above 64 KiB for 2 s hold more than
  half the connection's window, those nothing is read from are cancelled (STOP_SENDING, their
  data dropped), the largest first, until all hold half at most; those still read only past
  three quarters (titanCheck).
A small STREAM frame is copied out of its 1452-byte packet buffer when it arrives, and appended
to the stream's previous one while that still waits (titanPush): a stalled stream of 200-byte
frames took 8x its data in heap.
Every anchor must be found.
"""
import pathlib, shutil, sys

src = pathlib.Path(sys.argv[1])
here = pathlib.Path(__file__).parent
MARK = "// Titan patch: iOS memory."
edits = {
    "config.go": [(
        "\t\t// The auto-tuning ceilings must not sit below the starting windows.\n"
        "\t\tmaxStreamReceiveWindow = max(maxStreamReceiveWindow, initialStreamReceiveWindow)\n"
        "\t\tmaxConnectionReceiveWindow = max(maxConnectionReceiveWindow, initialConnectionReceiveWindow)\n",
        "\t\t" + MARK + " The caller's ceilings stay (the flow controllers keep to them).\n",
    )],
    "flow_controller_base.go": [(
        "\tallowWindowIncrease func(size protocol.ByteCount) bool\n",
        "\tallowWindowIncrease func(size protocol.ByteCount) bool\n\n"
        "\t" + MARK + " The connection's: updated when the peer runs short too (titanShort),\n"
        "\t// at the ceiling from the first update (titanGrow).\n"
        "\ttitanConnection, titanGrown bool\n",
    ), (
        "\treturn bytesRemaining <= protocol.ByteCount(float64(c.receiveWindowSize)*(1-protocol.WindowUpdateThreshold))\n}\n",
        "\treturn bytesRemaining <= protocol.ByteCount(float64(c.receiveWindowSize)*(1-protocol.WindowUpdateThreshold)) ||\n"
        "\t\tc.titanShort() " + MARK + "\n}\n",
    ), (
        "\tc.maybeAdjustWindowSize(now)\n\tc.receiveWindow = c.bytesRead + c.receiveWindowSize\n",
        "\tc.titanGrow() " + MARK + "\n"
        "\tc.maybeAdjustWindowSize(now)\n\tc.receiveWindow = c.bytesRead + c.receiveWindowSize\n",
    )],
    "flow_controller_connection.go": [(
        "\tlastBlockedAt protocol.ByteCount\n}\n",
        "\tlastBlockedAt protocol.ByteCount\n\n"
        "\t" + MARK + " Its receive streams, for stalled ones (titanCheck).\n"
        "\ttitan titanStalls\n}\n",
    ), (
        "\t\t\treceiveWindowSize:    receiveWindow,\n",
        "\t\t\treceiveWindowSize:    min(receiveWindow, maxReceiveWindow), " + MARK + "\n"
        "\t\t\ttitanConnection:      true,\n",
    ), (
        "\tc.highestReceived += increment\n\n",
        "\tc.highestReceived += increment\n"
        "\tc.titanArm(titanStallAfter) " + MARK + " Stalled streams holding too much?\n\n",
    )],
    "flow_controller_stream.go": [(
        "\treceivedFinalOffset bool\n}\n",
        "\treceivedFinalOffset bool\n\n"
        "\t" + MARK + " Since when more than titanMinUpdate waits on it, when it was last\n"
        "\t// read (titanCheck).\n"
        "\ttitanSince, titanRead monotime.Time\n}\n",
    ), (
        "\t\t\treceiveWindowSize:    receiveWindow,\n",
        "\t\t\treceiveWindowSize:    min(receiveWindow, maxReceiveWindow), " + MARK + "\n",
    ), (
        "\tincrement := offset - c.highestReceived\n\tc.highestReceived = offset\n",
        "\tc.titanArrived(now) " + MARK + "\n"
        "\tincrement := offset - c.highestReceived\n\tc.highestReceived = offset\n",
    ), (
        "\tc.addBytesRead(n)\n\thasStreamWindowUpdate = c.shouldQueueWindowUpdate()\n",
        "\tc.addBytesRead(n)\n\tc.titanReadNow() " + MARK + "\n"
        "\thasStreamWindowUpdate = c.shouldQueueWindowUpdate()\n",
    )],
    "receive_stream.go": [(
        "\tframeQueue               *frameSorter\n",
        "\tframeQueue               *frameSorter\n"
        "\ttitanTail                protocol.ByteCount " + MARK + " Its last copied frame (titanPush).\n",
    ), (
        "\tif err := s.frameQueue.Push(frame.Data, frame.Offset, frame.PutBack); err != nil {\n",
        "\tif err := s.titanPush(frame); err != nil { " + MARK + " Small frames copied.\n",
    ), (
        "\treturn &ReceiveStream{\n\t\tstreamID:       streamID,\n",
        "\ts := &ReceiveStream{\n\t\tstreamID:       streamID,\n",
    ), (
        "\t\tfinalOffset:    protocol.MaxByteCount,\n\t}\n}\n",
        "\t\tfinalOffset:    protocol.MaxByteCount,\n\t}\n"
        "\ts.titanAdd() " + MARK + " Listed with the connection's streams.\n"
        "\treturn s\n}\n",
    ), (
        "\tif s.finalOffset == protocol.MaxByteCount {\n\t\treturn false\n\t}\n"
        "\t// We're done with the stream if it was cancelled locally...\n",
        "\tif s.finalOffset == protocol.MaxByteCount {\n\t\treturn false\n\t}\n"
        "\tif s.cancelledLocally || s.errorRead { " + MARK + " Done: off the connection's list.\n"
        "\t\ts.titanDone()\n\t}\n"
        "\t// We're done with the stream if it was cancelled locally...\n",
    )],
}
if (src / "titan_mem.go").exists():
    sys.exit("quic-go: titan_mem.go exists already")
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
shutil.copy(here / "quic_mem.go", src / "titan_mem.go")
print("quic-go memory patch applied")
