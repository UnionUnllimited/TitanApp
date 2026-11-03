package titan

import "github.com/xtls/xray-core/app/observatory/burst"

// sleep: the device goes to sleep (PacketTunnelProvider.sleep): the burst observatory's rounds
// stop (the xray-mem patch, burst.TitanSleep); the balancers keep their results, which don't age
// meanwhile. A first check of a new instance (a restart) still runs.
func sleep() string {
	return answer(map[string]any{"paused": burst.TitanSleep()}, nil)
}

// wake: the device woke (PacketTunnelProvider.wake): the rounds go on at once, after 5 min or
// more asleep with a wake round first (every subject once, 5-30 s after the wake, 4 at a time).
// "slept" is how long it slept (s, the wall clock), 0 if it wasn't asleep.
func wake() string {
	return answer(map[string]any{"slept": burst.TitanWake().Seconds()}, nil)
}
