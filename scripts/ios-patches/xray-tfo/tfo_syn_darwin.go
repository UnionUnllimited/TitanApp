//go:build darwin

package internet

import (
	stdnet "net"
	"unsafe"

	"golang.org/x/sys/unix"
)

// Data Apple's TCP puts in a SYN: before the handshake it assumes the default MSS (512
// for IPv4, 1024 for IPv6) less 40 bytes of options.
const (
	tfoSynData4 = 512 - 40
	tfoSynData6 = 1024 - 40
)

// The TFO bits of tcp_connection_info (<netinet/tcp.h>): what the kernel itself did with
// the connection. x/sys/unix leaves this bit field out of its struct; it is the 4 bytes
// right after tcpi_rttvar.
const (
	tfoKCookieReq     = 1 << 0  // asked the server for a cookie
	tfoKCookieRcv     = 1 << 1  // the server sent one
	tfoKSynLoss       = 1 << 2  // the SYN was sent again without data
	tfoKSynDataSent   = 1 << 3  // data went in the SYN
	tfoKSynDataAcked  = 1 << 4  // the server acknowledged all of it in its SYN-ACK
	tfoKCookieWrong   = 1 << 9  // the server did not take our cookie
	tfoKNoCookieRcv   = 1 << 10 // asked for a cookie, none came
	tfoKHeuristicOff  = 1 << 11 // Apple's TFO heuristics turned it off on this network
	tfoKSendBlackhole = 1 << 12
	tfoKRecvBlackhole = 1 << 13
	tfoKOneByteProxy  = 1 << 14 // a middlebox acknowledged all but one byte of the SYN
)

// What tfo-go sets on every dial (XNU's tcp_private.h): Apple's TFO heuristics, which turn
// TFO off on the whole network for minutes to hours after a couple of lost SYNs, and send
// a lost SYN again without its data, don't apply to the socket.
const tcpFastopenForceEnable = 0x218

var tfoKFlagsOffset = unsafe.Offsetof(unix.TCPConnectionInfo{}.Rttvar) + 4

// tfoKernel reads the kernel's state of a dialed connection.
func tfoKernel(c stdnet.Conn) (k tfoKernelInfo, ok bool) {
	tc, isTCP := c.(*stdnet.TCPConn)
	// The bit field must sit where <netinet/tcp.h> has it (padding in the Go struct).
	if !isTCP || unsafe.Offsetof(unix.TCPConnectionInfo{}.Txpackets) != tfoKFlagsOffset+4 {
		return k, false
	}
	raw, err := tc.SyscallConn()
	if err != nil {
		return k, false
	}
	_ = raw.Control(func(fd uintptr) {
		info, err := unix.GetsockoptTCPConnectionInfo(int(fd), unix.IPPROTO_TCP, unix.TCP_CONNECTION_INFO)
		if err != nil {
			return
		}
		flags := *(*uint32)(unsafe.Add(unsafe.Pointer(info), tfoKFlagsOffset))
		forced, _ := unix.GetsockoptInt(int(fd), unix.IPPROTO_TCP, tcpFastopenForceEnable)
		k = tfoKernelInfo{
			synDataSent:  flags&tfoKSynDataSent != 0,
			synDataAcked: flags&tfoKSynDataAcked != 0,
			cookieReq:    flags&tfoKCookieReq != 0,
			cookieRcv:    flags&tfoKCookieRcv != 0,
			forced:       forced == 1,
			srttMs:       info.Srtt,
			retransmit:   info.Txretransmitbytes,
		}
		for _, f := range []struct {
			bit  uint32
			what string
		}{
			{tfoKSynLoss, "SYN lost"}, {tfoKCookieWrong, "cookie refused"}, {tfoKNoCookieRcv, "no cookie came"},
			{tfoKHeuristicOff, "iOS heuristics off"}, {tfoKSendBlackhole, "send blackhole"},
			{tfoKRecvBlackhole, "receive blackhole"}, {tfoKOneByteProxy, "one-byte proxy"},
		} {
			if flags&f.bit != 0 {
				k.extra = append(k.extra, f.what)
			}
		}
		ok = true
	})
	return k, ok
}
