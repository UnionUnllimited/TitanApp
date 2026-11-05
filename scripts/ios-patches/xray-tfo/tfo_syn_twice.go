package internet

// Titan patch: a TCP Fast Open dial whose SYN went twice, for the notes and TitanTFOStats.
//
// XNU sends a SYN again after 1 s when no SYN-ACK came (TCPTV_RTOBASE), and with
// TCP_FASTOPEN_FORCE_ENABLE (tfo-go sets it on every dial) it keeps the data in it and never sets
// its own "SYN lost" bit (tfoKSynLoss). So only the dial's time tells: a dial that took
// tfoSynTwiceOver or more beyond the path's smoothed RTT (the kernel's, read right after it) had
// its SYN, or the SYN-ACK, sent twice. With no RTT known (0) nothing is said.

// How far over the path's RTT a dial must take to count as a SYN sent twice (the resend comes
// 1 s after the first SYN; a slow or loaded path adds less than this to a dial).
const tfoSynTwiceOver = 700

// tfoSynTwice: whether a dial of dialMs on a path with a smoothed RTT of srttMs had its SYN sent
// twice.
func tfoSynTwice(dialMs int64, srttMs uint32) bool {
	return srttMs > 0 && dialMs-int64(srttMs) >= tfoSynTwiceOver
}
