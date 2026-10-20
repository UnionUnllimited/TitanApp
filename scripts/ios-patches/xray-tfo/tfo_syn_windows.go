//go:build windows

package internet

import stdnet "net"

// Windows puts in the SYN what fits in it and sends the rest itself: no split.
const (
	tfoSynData4 = 1 << 30
	tfoSynData6 = 1 << 30
)

// tfoKernel: Windows tells nothing about TFO per connection (its TCP_INFO has no such
// fields); a server capture is the way to see it there.
func tfoKernel(stdnet.Conn) (tfoKernelInfo, bool) { return tfoKernelInfo{}, false }
