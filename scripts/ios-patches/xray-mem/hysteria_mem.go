package hysteria

// Titan patch, iOS memory (scripts/ios-patches/xray-mem): copied into
// transport/internet/hysteria.

import (
	"time"

	"github.com/xtls/xray-core/transport/internet"
)

func init() {
	internet.TitanRegisterCloser(titanCloseAll)
}

// titanCloseAll closes every pooled QUIC connection (a keep-alive kept them up for good: the
// cleaner only drops dead ones) and empties the pool. A dial of the stopped instance still
// under way may connect one of these clients again, or pool a new one under its stream
// settings: closed later too.
func titanCloseAll() int {
	// The manager as Dial makes it (it may not have run yet).
	initmanager.Do(func() {
		manager = &clientManager{
			m: make(map[dialerConf]*client),
		}
		go manager.clean()
	})
	manager.Lock()
	old := manager.m
	manager.m = make(map[dialerConf]*client)
	manager.Unlock()
	stale := map[*internet.MemoryStreamConfig]bool{}
	clients := make([]*client, 0, len(old))
	for key, c := range old {
		stale[key.MemoryStreamConfig] = true
		clients = append(clients, c)
	}
	// In a goroutine: a client is locked while it dials.
	go titanClose(clients)
	if len(stale) > 0 {
		time.AfterFunc(internet.TitanSweepAfter, func() {
			manager.Lock()
			for key, c := range manager.m {
				if stale[key.MemoryStreamConfig] {
					delete(manager.m, key)
					clients = append(clients, c)
				}
			}
			manager.Unlock()
			titanClose(clients)
		})
	}
	return len(clients)
}

// titanClose closes the clients' connections (a QUIC close only sends a packet).
func titanClose(clients []*client) {
	for _, c := range clients {
		c.Lock()
		if c.conn != nil {
			c.close()
		}
		c.Unlock()
	}
}
