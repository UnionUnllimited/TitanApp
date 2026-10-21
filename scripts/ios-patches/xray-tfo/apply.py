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
         "// TitanOutboundManager: the one TitanWithOutboundManager put in ctx, if any.\n"
         "func TitanOutboundManager(ctx context.Context) outbound.Manager {\n"
         "\tm, _ := ctx.Value(titanOutboundManagerKey{}).(outbound.Manager)\n\treturn m\n}\n\n"
         "func InitSystemDialer(dc dns.Client, om outbound.Manager) {\n"),
    ]),
    # gRPC with finalmask dials on grpc's own context: the manager is carried over to it.
    ("transport/internet/grpc/dial.go", [
        ("\t\t\tgctx = session.ContextWithTimeoutOnly(gctx, true)\n",
         "\t\t\tgctx = session.ContextWithTimeoutOnly(gctx, true)\n"
         "\t\t\tif m := internet.TitanOutboundManager(ctx); m != nil {\n"
         "\t\t\t\tgctx = internet.TitanWithOutboundManager(gctx, m)\n\t\t\t}\n"),
    ]),
    # Each handler keeps its own instance and puts its manager in the ctx of every dial it
    # makes: Dispatch too (WireGuard dials from there), and mux/XUDP or VLESS pre-connections
    # whose ctx has no instance in it.
    ("app/proxyman/outbound/handler.go", [
        ("\tuplinkCounter   stats.Counter\n\tdownlinkCounter stats.Counter\n}\n",
         "\tuplinkCounter   stats.Counter\n\tdownlinkCounter stats.Counter\n"
         "\t// Titan patch: the instance it belongs to (dialerProxy lookups).\n\ttitanV *core.Instance\n}\n"),
        ("\t\tuplinkCounter:   uplinkCounter,\n\t\tdownlinkCounter: downlinkCounter,\n\t}\n",
         "\t\tuplinkCounter:   uplinkCounter,\n\t\tdownlinkCounter: downlinkCounter,\n\t\ttitanV:          v,\n\t}\n"),
        ("func (h *Handler) Dispatch(ctx context.Context, link *transport.Link) {\n",
         "func (h *Handler) Dispatch(ctx context.Context, link *transport.Link) {\n\tctx = h.titanCtx(ctx)\n"),
        ("\tconn, err := internet.Dial(ctx, dest, h.streamSettings)\n\tconn = h.getStatCouterConnection(conn)\n",
         "\tctx = h.titanCtx(ctx)\n"
         "\tconn, err := internet.Dial(ctx, dest, h.streamSettings)\n\tconn = h.getStatCouterConnection(conn)\n"),
        ("func (h *Handler) SetOutboundGateway(",
         "// titanCtx: DialSystem finds dialerProxy among this handler's own instance's outbounds.\n"
         "func (h *Handler) titanCtx(ctx context.Context) context.Context {\n"
         "\tif h.titanV != nil {\n"
         "\t\tif m, ok := h.titanV.GetFeature(outbound.ManagerType()).(outbound.Manager); ok {\n"
         "\t\t\treturn internet.TitanWithOutboundManager(ctx, m)\n\t\t}\n\t}\n\treturn ctx\n}\n\n"
         "func (h *Handler) SetOutboundGateway("),
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
