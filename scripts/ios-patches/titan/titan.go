// Package titan runs our protocol clients inside the iOS app and its packet tunnel, next to
// Xray (one Go runtime: one framework). On Android they are separate programs; here sing-box
// (TUIC / AnyTLS / ShadowTLS / SSH) and mieru each listen on a local SOCKS port that Xray's
// proxy outbound is pointed at, same as there.
//
// Calls go through Invoke with JSON, like libXray's:
//
//	{"method": "startSingBox", "config": "<sing-box JSON>"}           -> {"handle": n}
//	{"method": "startMieru", "profile": "<ClientProfile JSON>", "port": p} -> {"handle": n}
//	{"method": "stop", "handle": n}                                    -> {}
//	{"method": "freePort"}                                             -> {"port": p}
//	{"method": "tfoStats"}                                             -> {"dials": n, ...}
//
// tfoStats is what Xray's TCP Fast Open dials in this process did, by the kernel's own
// record (the xray-tfo patch: internet.TitanTFOStats).
//
// Errors come back as {"error": "..."}.
package titan

import (
	"encoding/json"
	"errors"
	"io"
	"net"
	"sync"

	"github.com/xtls/xray-core/transport/internet"
)

type request struct {
	Method  string `json:"method"`
	Config  string `json:"config"`
	Profile string `json:"profile"`
	Port    int    `json:"port"`
	Handle  int    `json:"handle"`
}

var (
	mu      sync.Mutex
	running = map[int]io.Closer{}
	next    = 1
)

// Invoke runs one request (see the package comment) and returns the JSON answer.
func Invoke(requestJSON string) string {
	var req request
	if err := json.Unmarshal([]byte(requestJSON), &req); err != nil {
		return answer(nil, err)
	}
	switch req.Method {
	case "startSingBox":
		c, err := startSingBox(req.Config)
		return answer(keep(c), err)
	case "startMieru":
		c, err := startMieru(req.Profile, req.Port)
		return answer(keep(c), err)
	case "stop":
		mu.Lock()
		c := running[req.Handle]
		delete(running, req.Handle)
		mu.Unlock()
		if c != nil {
			c.Close()
		}
		return answer(map[string]any{}, nil)
	case "freePort":
		ln, err := net.Listen("tcp", "127.0.0.1:0")
		if err != nil {
			return answer(nil, err)
		}
		port := ln.Addr().(*net.TCPAddr).Port
		ln.Close()
		return answer(map[string]any{"port": port}, nil)
	case "tfoStats":
		return internet.TitanTFOStats()
	}
	return answer(nil, errors.New("unknown method "+req.Method))
}

func keep(c io.Closer) map[string]any {
	if c == nil {
		return nil
	}
	mu.Lock()
	defer mu.Unlock()
	h := next
	next++
	running[h] = c
	return map[string]any{"handle": h}
}

func answer(data map[string]any, err error) string {
	if err != nil {
		data = map[string]any{"error": err.Error()}
	}
	b, _ := json.Marshal(data)
	return string(b)
}
