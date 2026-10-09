package main

import (
	"context"
	"crypto/rand"
	"encoding/hex"
	"net"
	"sync/atomic"
	"syscall"
	"time"

	"github.com/sagernet/sing-box/adapter"
	"github.com/sagernet/sing-box/adapter/outbound"
	"github.com/sagernet/sing-box/log"
	"github.com/sagernet/sing-box/option"
	shadowsocks "github.com/sagernet/sing-shadowsocks2"
	M "github.com/sagernet/sing/common/metadata"
	N "github.com/sagernet/sing/common/network"
)

const accountType = "titan-account"

// accounts: the user → Shadowsocks method map our outbound uses; swapped on updates.
type accounts struct {
	method string
	xray   map[string]string // inbound tag -> placeholder address
	byUser atomic.Pointer[map[string]shadowsocks.Method]
	test   atomic.Pointer[map[string]bool]
}

func newAccounts(method string, xray map[string]string) *accounts {
	return &accounts{method: method, xray: xray}
}

func (a *accounts) update(users []User) {
	byUser := make(map[string]shadowsocks.Method, len(users))
	test := map[string]bool{}
	for _, u := range users {
		if u.Secret == "" {
			test[u.Name] = true
			continue
		}
		m, err := shadowsocks.CreateMethod(context.Background(), a.method, shadowsocks.MethodOptions{Password: u.Secret})
		if err != nil {
			log.Error("account ", u.Name, ": ", err)
			continue
		}
		byUser[u.Name] = m
	}
	a.byUser.Store(&byUser)
	a.test.Store(&test)
}

func (a *accounts) constructor(ctx context.Context, router adapter.Router, logger log.ContextLogger, tag string, _ option.StubOptions) (adapter.Outbound, error) {
	return &accountOutbound{
		Adapter:  outbound.NewAdapter(accountType, tag, []string{N.NetworkTCP, N.NetworkUDP}, nil),
		accounts: a,
		logger:   logger,
	}, nil
}

type accountOutbound struct {
	outbound.Adapter
	*accounts
	logger log.ContextLogger
}

// route: where this connection's user goes out: (method, placeholder) for real users,
// direct for test users; an unknown user is refused.
func (o *accountOutbound) route(ctx context.Context) (shadowsocks.Method, string, bool, error) {
	md := adapter.ContextFrom(ctx)
	if md == nil || md.User == "" {
		return nil, "", false, syscall.EPERM
	}
	if (*o.test.Load())[md.User] {
		return nil, "", true, nil
	}
	m := (*o.byUser.Load())[md.User]
	addr := o.xray[md.Inbound]
	if m == nil || addr == "" {
		return nil, "", false, syscall.EPERM
	}
	return m, addr, false, nil
}

func (o *accountOutbound) DialContext(ctx context.Context, network string, destination M.Socksaddr) (net.Conn, error) {
	m, addr, direct, err := o.route(ctx)
	if err != nil {
		return nil, err
	}
	dialer := net.Dialer{Timeout: 10 * time.Second}
	if direct {
		return dialer.DialContext(ctx, N.NetworkName(network), destination.String())
	}
	raw, err := dialer.DialContext(ctx, "tcp", addr)
	if err != nil {
		return nil, err
	}
	// Early: the destination goes out with the first payload, as Shadowsocks clients do.
	return m.DialEarlyConn(raw, destination), nil
}

func (o *accountOutbound) ListenPacket(ctx context.Context, destination M.Socksaddr) (net.PacketConn, error) {
	m, addr, direct, err := o.route(ctx)
	if err != nil {
		return nil, err
	}
	if direct {
		return net.ListenPacket("udp", "")
	}
	raw, err := net.Dial("udp", addr)
	if err != nil {
		return nil, err
	}
	return m.DialPacketConn(raw), nil
}

func randomHex(n int) string {
	b := make([]byte, n)
	rand.Read(b)
	return hex.EncodeToString(b)
}
