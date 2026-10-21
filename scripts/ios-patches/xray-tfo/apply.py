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
for f in ("system_dialer_tfo.go", "tfo_syn_darwin.go", "tfo_syn_windows.go", "system_dialer_tfo_other.go", "tfo_safe.go"):
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
    # An upload's request is safe to repeat: it may go in the SYN whole (tfo_safe.go).
    ("client.go", None,
     "\t\t\t\tnewConn, err := c.dialUploadConn(context.WithoutCancel(ctx))\n",
     "\t\t\t\tnewConn, err := c.dialUploadConn(titanUploadCtx(context.WithoutCancel(ctx)))\n"),
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

# dialerProxy found in the dialing instance's own outbounds, not the process-wide manager
# the last core.New installed: the app runs several ping instances side by side (each with
# its own tags), and a chained server's next hop must come from its own instance.
for rel, edits in [
    ("transport/internet/dialer.go", [
        ("\tif len(sockopt.DialerProxy) > 0 {\n\t\tif obm == nil {\n",
         "\tif len(sockopt.DialerProxy) > 0 {\n\t\tobm := obm\n"
         "\t\tif m, ok := ctx.Value(titanOutboundManagerKey{}).(outbound.Manager); ok && m != nil {\n"
         "\t\t\tobm = m\n\t\t}\n\t\tif obm == nil {\n"),
        ("func InitSystemDialer(dc dns.Client, om outbound.Manager) {\n",
         "type titanOutboundManagerKey struct{}\n\n"
         "// TitanWithOutboundManager: DialSystem takes dialerProxy from this manager (Titan patch).\n"
         "func TitanWithOutboundManager(ctx context.Context, m outbound.Manager) context.Context {\n"
         "\treturn context.WithValue(ctx, titanOutboundManagerKey{}, m)\n}\n\n"
         "func InitSystemDialer(dc dns.Client, om outbound.Manager) {\n"),
    ]),
    ("app/proxyman/outbound/handler.go", [
        ("\tconn, err := internet.Dial(ctx, dest, h.streamSettings)\n\tconn = h.getStatCouterConnection(conn)\n",
         "\tif v := core.FromContext(ctx); v != nil {\n"
         "\t\tif m, ok := v.GetFeature(outbound.ManagerType()).(outbound.Manager); ok {\n"
         "\t\t\tctx = internet.TitanWithOutboundManager(ctx, m)\n\t\t}\n\t}\n"
         "\tconn, err := internet.Dial(ctx, dest, h.streamSettings)\n\tconn = h.getStatCouterConnection(conn)\n"),
    ]),
]:
    target = src / rel
    code = target.read_text()
    for old, new in edits:
        if code.count(old) != 1:
            sys.exit(rel + ": anchor not found: " + old.strip())
        code = code.replace(old, new)
    target.write_text(code)
print("xray-core TFO patch applied")
