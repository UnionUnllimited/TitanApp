package grpc

// Titan patch, iOS memory (scripts/ios-patches/xray-mem): copied into transport/internet/grpc.

import (
	"time"

	"github.com/xtls/xray-core/transport/internet"
	"google.golang.org/grpc"
)

// What a gRPC stream (multiMode: one per proxied connection) holds unread when its reader
// stalls: grpc-go's BDP estimate grows the windows to 16 MiB. Fixed windows instead; a
// connection's carries its streams' credit (grpc-go sends it on receipt, not on read).
const (
	titanStreamWindow = 1 << 20
	titanConnWindow   = 4 << 20
)

// titanDialOptions adds the fixed windows (unless the config has a smaller stream window) and
// the idle timeout: grpc-go's default is 30 min, and with the observatory's probes ~5 min apart
// every subject's ClientConn stayed READY with its transport for good (72 KiB of heap and 5
// goroutines each), though only the balancers' picks carry traffic. With no RPC (a stream:
// multiMode's per proxied connection, a probe's) for internet.TitanIdleAfter the channel goes
// IDLE and drops its transport; the next RPC connects again. grpc-go's own idleness: an RPC
// open keeps it from going idle, and one that comes as it goes brings it back
// (internal/idle). The pool keeps the ClientConn (an idle one isn't Shutdown).
func titanDialOptions(opts []grpc.DialOption, configured int32) []grpc.DialOption {
	opts = append(opts, grpc.WithIdleTimeout(internet.TitanIdleAfter))
	if configured > 0 && configured <= titanStreamWindow {
		return opts
	}
	return append(opts, grpc.WithStaticStreamWindowSize(titanStreamWindow), grpc.WithStaticConnWindowSize(titanConnWindow))
}

func init() {
	internet.TitanRegisterCloser(titanCloseAll)
}

// titanCloseAll closes every pooled ClientConn (each also kept the instance that first dialed
// it: its dialer's context) and empties the pool; later ones a dial of the stopped instance
// still pooled under its stream settings too. ClientConn.Close in a goroutine: the TLS close
// under it may wait on a dead network.
func titanCloseAll() int {
	globalDialerAccess.Lock()
	stale := map[*internet.MemoryStreamConfig]bool{}
	for key := range globalDialerMap {
		stale[key.MemoryStreamConfig] = true
	}
	n := titanClose(func(*internet.MemoryStreamConfig) bool { return true })
	globalDialerAccess.Unlock()
	if len(stale) > 0 {
		time.AfterFunc(internet.TitanSweepAfter, func() {
			globalDialerAccess.Lock()
			defer globalDialerAccess.Unlock()
			titanClose(func(s *internet.MemoryStreamConfig) bool { return stale[s] })
		})
	}
	return n
}

// titanClose, under globalDialerAccess.
func titanClose(old func(*internet.MemoryStreamConfig) bool) int {
	n := 0
	for key, conn := range globalDialerMap {
		if !old(key.MemoryStreamConfig) {
			continue
		}
		delete(globalDialerMap, key)
		// A failed NewClient leaves nil.
		if conn != nil {
			go conn.Close()
			n++
		}
	}
	return n
}
