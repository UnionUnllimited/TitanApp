//go:build darwin

package internet

// Data Apple's TCP puts in a SYN: before the handshake it assumes the default MSS (512
// for IPv4, 1024 for IPv6) less 40 bytes of options.
const (
	tfoSynData4 = 512 - 40
	tfoSynData6 = 1024 - 40
)
