"""Titan patch for libXray: a failed ping carries the TFO dialer's notes (see the Xray-core patch)."""
import pathlib, sys

p = pathlib.Path(sys.argv[1]) / "xray/ping_batch.go"
code = p.read_text()
old = "\tif err != nil {\n\t\tresult = failedPingBatchResult(delay, err)\n\t}\n"
new = ("\tif err != nil {\n\t\tresult = failedPingBatchResult(delay, err)\n"
       "\t\tif notes := internet.TitanTFOLog(); notes != \"\" {\n\t\t\tresult.Error += \" [\" + notes + \"]\"\n\t\t}\n\t}\n")
imp = '\t"github.com/xtls/xray-core/infra/conf"\n'
if code.count(old) != 1 or code.count(imp) != 1:
    sys.exit("ping_batch.go: anchors not found")
code = code.replace(old, new).replace(imp, imp + '\t"github.com/xtls/xray-core/transport/internet"\n')
p.write_text(code)
print("libXray ping patch applied")
