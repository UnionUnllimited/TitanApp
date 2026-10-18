//go:build windows

package internet

// Windows puts in the SYN what fits in it and sends the rest itself: no split.
const (
	tfoSynData4 = 1 << 30
	tfoSynData6 = 1 << 30
)
