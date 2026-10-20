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

# XHTTP packet-up over TFO: one upload per connection (splithttp_upload_once.go).
split = src / "transport/internet/splithttp"
client = (split / "client.go").read_text()
dialer = (split / "dialer.go").read_text()
for name, text, old, new in [
    ("client.go", client,
     "\tdialUploadConn func(ctxInner context.Context) (net.Conn, error)\n}",
     "\tdialUploadConn func(ctxInner context.Context) (net.Conn, error)\n\t// Titan patch: TFO, an upload per connection.\n\tuploadOnce bool\n}"),
    ("client.go", None,
     "\t\tc.uploadRawPool.Put(uploadConn)\n",
     "\t\tc.titanReleaseUpload(h1UploadConn, uploadConn)\n"),
    ("dialer.go", dialer,
     "\t\tdialUploadConn: dialContext,\n\t}",
     "\t\tdialUploadConn: dialContext,\n\t\tuploadOnce:     streamSettings.SocketSettings != nil && streamSettings.SocketSettings.ParseTFOValue() > 0,\n\t}"),
]:
    target = split / name
    code = target.read_text()
    if code.count(old) != 1:
        sys.exit(name + ": anchor not found: " + old.strip())
    target.write_text(code.replace(old, new))
shutil.copy(here / "splithttp_upload_once.go", split / "titan_upload_once.go")
print("xray-core TFO patch applied")
