//go:build !darwin && !windows

package internet

import (
	"context"
	stdnet "net"
)

// Titan patch: elsewhere the socket option alone gives TCP Fast Open.
func dialTFO(ctx context.Context, d *stdnet.Dialer, network, address string) (stdnet.Conn, error) {
	return d.DialContext(ctx, network, address)
}

// TitanTFOLog: nothing to report here.
func TitanTFOLog() string { return "" }

// TitanTFOStats: nothing counted here.
func TitanTFOStats() string { return "{}" }
