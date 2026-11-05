"""Titan patch for libXray's ping (pingBatch).

- A failed ping carries the TFO dialer's notes (see the Xray-core patch).
- "warmTimeout" (seconds; 0 or absent: as upstream): an outbound that answered is probed once
  more on the same Xray instance, with that timeout (titan_warm.go, copied from libxray_warm.go).
  "delay" is the better of the two, "delays" both (-1: the second didn't answer).

Every anchor must be found, once: another libXray (or a second run) stops the build.
"""
import pathlib, shutil, sys

src = pathlib.Path(sys.argv[1])
here = pathlib.Path(__file__).parent
edits = {
    "xray/ping_batch.go": [
        # A failed ping: the TFO notes in [brackets].
        ("\tif err != nil {\n\t\tresult = failedPingBatchResult(delay, err)\n\t}\n",
         "\tif err != nil {\n\t\tresult = failedPingBatchResult(delay, err)\n"
         "\t\tif notes := internet.TitanTFOLog(); notes != \"\" {\n\t\t\tresult.Error += \" [\" + notes + \"]\"\n\t\t}\n\t}\n"),
        ('\t"github.com/xtls/xray-core/infra/conf"\n',
         '\t"github.com/xtls/xray-core/infra/conf"\n\t"github.com/xtls/xray-core/transport/internet"\n'),
        # The warm probe: each probe's delays, the entry point with warmTimeout, the worker's call.
        ("\tLocationJSON  *string\n\tLocationError string\n}\n",
         "\tLocationJSON  *string\n\tLocationError string\n"
         "\t// Titan patch: with warmTimeout, each probe's delay (titan_warm.go).\n"
         "\tDelays []int64\n}\n"),
        ("func PingBatchWithLocation(items []PingBatchItem, timeout int, targetURL, locationURL string) ([]PingBatchResult, error) {\n",
         "func PingBatchWithLocation(items []PingBatchItem, timeout int, targetURL, locationURL string) ([]PingBatchResult, error) {\n"
         "\treturn PingBatchWarm(items, timeout, 0, targetURL, locationURL)\n}\n\n"
         "// PingBatchWarm (Titan patch): PingBatchWithLocation where, with warmTimeout > 0, an outbound\n"
         "// that answered is probed once more on the same instance (titanProbeWarm, titan_warm.go).\n"
         "func PingBatchWarm(items []PingBatchItem, timeout, warmTimeout int, targetURL, locationURL string) ([]PingBatchResult, error) {\n"),
        ("\t\t\t\tresults[item.resultIndex] = probeOutbound(\n\t\t\t\t\tserver,\n\t\t\t\t\titem.outboundTag,\n"
         "\t\t\t\t\ttimeout,\n\t\t\t\t\ttargetURL,\n\t\t\t\t\tlocationURL,\n\t\t\t\t)\n",
         "\t\t\t\tresults[item.resultIndex] = titanProbeWarm(\n\t\t\t\t\tserver,\n\t\t\t\t\titem.outboundTag,\n"
         "\t\t\t\t\ttimeout,\n\t\t\t\t\twarmTimeout,\n\t\t\t\t\ttargetURL,\n\t\t\t\t\tlocationURL,\n\t\t\t\t)\n"),
    ],
    "invoke_model.go": [
        ("\tLocationURL string                 `json:\"locationUrl,omitempty\"`\n}\n",
         "\tLocationURL string                 `json:\"locationUrl,omitempty\"`\n"
         "\t// Titan patch: seconds for a second probe on the same instance (0: none).\n"
         "\tWarmTimeout int `json:\"warmTimeout,omitempty\"`\n}\n"),
        ("\tLocationError string  `json:\"locationError,omitempty\"`\n}\n",
         "\tLocationError string  `json:\"locationError,omitempty\"`\n"
         "\t// Titan patch: with warmTimeout, each probe's delay (-1: no answer); delay is the better.\n"
         "\tDelays []int64 `json:\"delays,omitempty\"`\n}\n"),
    ],
    "invoke.go": [
        ("\tresults, err := xray.PingBatchWithLocation(\n\t\tconfigs,\n\t\trequest.Timeout,\n"
         "\t\trequest.URL,\n\t\trequest.LocationURL,\n\t)\n",
         "\tresults, err := xray.PingBatchWarm(\n\t\tconfigs,\n\t\trequest.Timeout,\n\t\trequest.WarmTimeout,\n"
         "\t\trequest.URL,\n\t\trequest.LocationURL,\n\t)\n"),
        ("\t\t\tLocationError: result.LocationError,\n\t\t}\n",
         "\t\t\tLocationError: result.LocationError,\n\t\t\tDelays:        result.Delays,\n\t\t}\n"),
    ],
}
patched = {}
for rel, pairs in edits.items():
    code = (src / rel).read_text()
    for old, new in pairs:
        if code.count(old) != 1:
            sys.exit(rel + ": anchor not found: " + old.strip().splitlines()[0])
        code = code.replace(old, new)
    patched[rel] = code
if (src / "xray/titan_warm.go").exists():
    sys.exit("xray/titan_warm.go exists already")
for rel, code in patched.items():
    (src / rel).write_text(code)
shutil.copy(here / "libxray_warm.go", src / "xray/titan_warm.go")
print("libXray ping patch applied")
