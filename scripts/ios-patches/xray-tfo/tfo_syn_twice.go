package internet

// Titan patch: a TCP Fast Open dial whose SYN went twice, for the notes and TitanTFOStats.
//
// XNU sends a SYN again after 1 s when no SYN-ACK came (TCPTV_RTOBASE), and with
// TCP_FASTOPEN_FORCE_ENABLE (tfo-go sets it on every dial) it keeps the data in it and never sets
// its own "SYN lost" bit (tfoKSynLoss). Two signs are left, both read right after the dial:
//   - The bytes the kernel sent again (tcpi_txretransmitbytes). A SYN resent with its data adds
//     that data there, and on a dial whose SYN data was acked nothing else has been sent again by
//     then. This sees a resent SYN with data whether the first SYN or the SYN-ACK was lost, with
//     or without TCP timestamps (tfoSynResent).
//   - The handshake's time: the connected socket's own, from just before its connect (after the
//     name lookup and any other address tried, which the dial's time also counts), tfoSynTwiceOver
//     or more beyond the path's smoothed RTT (tfoSynTwice). This sees only a lost first SYN, and
//     only with TCP timestamps: a resent SYN-ACK echoes the first SYN's timestamp, and without
//     timestamps XNU on cellular times the handshake from the first SYN, so the RTT spans the
//     resend as well; on Wi-Fi without timestamps there is no RTT sample (0: nothing said).
// A SYN without data (a cookie request) resent on such a path shows in neither: a 0 doesn't rule
// that out.

// How far over the path's RTT a handshake must take to count as a SYN sent twice (the resend
// comes 1 s after the first SYN; a slow or loaded path adds less than this to a handshake).
const tfoSynTwiceOver = 700

// tfoSynTwice: whether a handshake of handshakeMs (-1: unknown, nothing said) on a path with a
// smoothed RTT of srttMs had its SYN sent twice, by its time alone.
func tfoSynTwice(handshakeMs int64, srttMs uint32) bool {
	return srttMs > 0 && handshakeMs >= 0 && handshakeMs-int64(srttMs) >= tfoSynTwiceOver
}

// tfoSynResent: whether the kernel sent the SYN's data again during a dial (see above): the data
// went in the SYN and was acked (synDataAcked), and resentBytes were sent again by then.
func tfoSynResent(synDataAcked bool, resentBytes uint64) bool {
	return synDataAcked && resentBytes > 0
}

// tfoSynSentAgain: either sign (what the notes and synTwice count).
func tfoSynSentAgain(synDataAcked bool, resentBytes uint64, handshakeMs int64, srttMs uint32) bool {
	return tfoSynResent(synDataAcked, resentBytes) || tfoSynTwice(handshakeMs, srttMs)
}
