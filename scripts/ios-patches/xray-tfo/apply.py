"""Titan patch for Xray-core: TCP dials with tcpFastOpen go through dialTFO (real TFO on Apple and Windows)."""
import pathlib, shutil, sys

src = pathlib.Path(sys.argv[1])
here = pathlib.Path(__file__).parent
target = src / "transport/internet/system_dialer.go"
code = target.read_text()
old = "\treturn dialer.DialContext(ctx, dest.Network.SystemString(), dest.NetAddr())\n}"
new = ("\tif sockopt != nil && sockopt.ParseTFOValue() > 0 {\n"
       "\t\treturn dialTFO(ctx, dialer, dest.Network.SystemString(), dest.NetAddr())\n"
       "\t}\n" + old)
if code.count(old) != 1:
    sys.exit("system_dialer.go: dial line not found")
target.write_text(code.replace(old, new))
for f in ("system_dialer_tfo.go", "tfo_syn_darwin.go", "tfo_syn_windows.go", "system_dialer_tfo_other.go"):
    shutil.copy(here / f, src / "transport/internet" / f)
print("xray-core TFO patch applied")
