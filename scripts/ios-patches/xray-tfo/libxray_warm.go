package xray

// Titan patch, iOS (scripts/ios-patches/xray-tfo/apply_libxray.py): copied into libXray's xray
// package as titan_warm.go.

import "github.com/xtls/xray-core/core"

// titanProbeWarm probes an outbound and, with warmTimeout > 0 and an answer, probes it once more
// on the same instance (the batch closes it only after both), with warmTimeout seconds. The
// second probe runs on a warm handler, as the tunnel's long-lived instance does and as the second
// attempt of Android's ping on its warm core: what the outbound keeps between requests (a
// VLESS-encryption 0-RTT ticket, XHTTP's XMUX, pre-connections) is there, and nothing else is
// kept here: the probe's HTTP client has keep-alives off, so the second request goes over new
// connections wherever Xray opens new ones (each with its TCP and TFO dial). The kernel's TFO
// cookie and the DNS cache were warm for a second call already; this adds the handler's state.
//
// Delay: the better of the two. Delays: both, in order (-1: the second didn't answer). A second
// probe that fails leaves the first answer as it is; without warmTimeout nothing changes.
func titanProbeWarm(
	server *core.Instance,
	outboundTag string,
	timeout int,
	warmTimeout int,
	targetURL string,
	locationURL string,
) PingBatchResult {
	result := probeOutbound(server, outboundTag, timeout, targetURL, locationURL)
	if warmTimeout <= 0 || !result.Success {
		return result
	}
	warm := probeOutbound(server, outboundTag, warmTimeout, targetURL, "")
	result.Delays = []int64{result.Delay, -1}
	if warm.Success {
		result.Delays[1] = warm.Delay
		result.Delay = min(result.Delay, warm.Delay)
	}
	return result
}
