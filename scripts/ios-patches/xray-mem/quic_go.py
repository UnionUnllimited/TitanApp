"""Titan patch for quic-go (apernet's: Xray's Hysteria and XHTTP over HTTP/3), iOS only: the
receive windows keep to the ceilings the caller sets, Chrome parroting or not.

Chrome parroting opens every connection with Chrome's windows (6 MiB per stream, 15 MiB per
connection: transport parameters in the handshake, part of its fingerprint) and raised the
auto-tuning ceilings to them, so Xray's ceilings (and the panel's quicParams) did nothing: a
stalled stream (a player with a full buffer) had 6 MiB waiting in memory, a connection 15.
The handshake stays Chrome's; what is granted after it (MAX_STREAM_DATA / MAX_DATA) keeps
to the ceilings: the flow controllers start at the smaller of the two, the ceilings stay.
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
    "flow_controller_connection.go": [(
        "\t\t\treceiveWindowSize:    receiveWindow,\n",
        "\t\t\treceiveWindowSize:    min(receiveWindow, maxReceiveWindow), " + MARK + "\n",
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
