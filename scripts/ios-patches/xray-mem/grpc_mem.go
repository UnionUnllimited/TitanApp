package grpc

// Titan patch, iOS memory (scripts/ios-patches/xray-mem): copied into transport/internet/grpc.

import (
	"context"
	"sync"
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
// (internal/idle). The pool keeps the ClientConn (an idle one isn't Shutdown). The probes'
// RPCs aren't on the pool's ClientConns (titanDone): their idle time is from the tunnel's flows.
func titanDialOptions(opts []grpc.DialOption, configured int32) []grpc.DialOption {
	opts = append(opts, grpc.WithIdleTimeout(internet.TitanIdleAfter))
	if configured > 0 && configured <= titanStreamWindow {
		return opts
	}
	return append(opts, grpc.WithStaticStreamWindowSize(titanStreamWindow), grpc.WithStaticConnWindowSize(titanConnWindow))
}

// The observatory's probes' ClientConns now (each its own: getGrpcClient), under
// globalDialerAccess.
var titanProbeConns = map[*grpc.ClientConn]struct{}{}

// titanDone is what the flow's close calls (the HunkConn's cancel; at once if its RPC didn't
// open), once. The observatory's probes (internet.TitanProbe) don't use the pool: each gets a
// ClientConn of its own (getGrpcClient), with its own TCP (+ TLS / REALITY) connection, and
// closes it here. So every probe is cold, as TCP REALITY's always were (it pools nothing), on a
// subject that carries the tunnel's flows too, and the pool's ClientConns carry only those flows'
// RPCs. With the probes in the pool a subject's were a random mix of warm ones and cold ones
// (internet.TitanIdleAfter), and leastLoad ranks by their deviation. Its ClientConn dials with
// the probe's mark copied into grpc-go's dial context (dial.go), so a dialerProxy hop's
// transport gives the probe a connection of its own as well, not one of its pool's.
func titanDone(ctx context.Context, conn *grpc.ClientConn) func() {
	if !internet.TitanProbe(ctx) {
		return func() {}
	}
	var once sync.Once
	return func() {
		once.Do(func() {
			globalDialerAccess.Lock()
			delete(titanProbeConns, conn)
			globalDialerAccess.Unlock()
			go conn.Close()
		})
	}
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
	// The probes' too (their flows close them as they end).
	for conn := range titanProbeConns {
		go conn.Close()
		n++
	}
	clear(titanProbeConns)
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
