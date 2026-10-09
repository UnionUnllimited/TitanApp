// titan-sbgw: sing-box (as a library, with small patches for runtime user updates) serving
// Naive, TUIC, AnyTLS and ShadowTLS, whose users change without restarting the core.
//
// Each user's traffic leaves through the node's Xray placeholder for that inbound
// (Shadowsocks on 127.0.0.1, managed by Remnawave) with the user's own Shadowsocks
// password, picked per connection by our "titan-account" outbound — so Remnawave counts
// it, and neither routing rules nor outbounds depend on the user list.
//
//	titan-sbgw -config /etc/titan-gateway/template.json -users /etc/titan-gateway/users.json
//
// The template is a sing-box config plus a "titan" block:
//
//	"titan": {"method": "chacha20-ietf-poly1305", "xray": {"naive-in": "127.0.0.1:61001", ...}}
//
// The users file ([{"name", "password", "secret"}], written by titan-node-sync) is
// re-read when it changes; established connections are kept.
package main

import (
	"context"
	"crypto/sha256"
	"encoding/base64"
	"encoding/hex"
	"encoding/json"
	"flag"
	"log"
	"os"
	"os/signal"
	"syscall"
	"time"

	box "github.com/sagernet/sing-box"
	"github.com/sagernet/sing-box/adapter/outbound"
	"github.com/sagernet/sing-box/include"
	"github.com/sagernet/sing-box/option"
	sjson "github.com/sagernet/sing/common/json"
)

type User struct {
	Name     string `json:"name"`
	Password string `json:"password"`
	// Shadowsocks password of the user on the Xray placeholders; empty: not accounted
	// (test users), they go out directly.
	Secret string `json:"secret"`
}

func main() {
	configPath := flag.String("config", "/etc/titan-gateway/template.json", "sing-box config with a \"titan\" block")
	usersPath := flag.String("users", "/etc/titan-gateway/users.json", "users file")
	flag.Parse()

	raw, err := os.ReadFile(*configPath)
	if err != nil {
		log.Fatal(err)
	}
	var cfg map[string]any
	if err := json.Unmarshal(raw, &cfg); err != nil {
		log.Fatal(err)
	}
	titan, _ := cfg["titan"].(map[string]any)
	delete(cfg, "titan")
	method, _ := titan["method"].(string)
	if method == "" {
		method = "chacha20-ietf-poly1305"
	}
	xray := map[string]string{}
	if m, ok := titan["xray"].(map[string]any); ok {
		for k, v := range m {
			xray[k], _ = v.(string)
		}
	}

	users, stamp, err := readUsers(*usersPath)
	if err != nil {
		log.Fatal(err)
	}
	inbounds, _ := cfg["inbounds"].([]any)
	plan := planInbounds(inbounds)
	plan.apply(inbounds, users) // sing-box wants the users at start too
	cfg["outbounds"] = append([]any{map[string]any{"type": accountType, "tag": accountType}}, nonAccount(cfg["outbounds"])...)
	route, _ := cfg["route"].(map[string]any)
	if route == nil {
		route = map[string]any{}
	}
	route["final"] = accountType
	cfg["route"] = route

	account := newAccounts(method, xray)
	account.update(users)

	outbounds := include.OutboundRegistry()
	outbound.Register[option.StubOptions](outbounds, accountType, account.constructor)
	ctx := box.Context(context.Background(), include.InboundRegistry(), outbounds, include.EndpointRegistry(), include.DNSTransportRegistry(), include.ServiceRegistry())
	content, _ := json.Marshal(cfg)
	options, err := sjson.UnmarshalExtendedContext[option.Options](ctx, content)
	if err != nil {
		log.Fatal("config: ", err)
	}
	instance, err := box.New(box.Options{Context: ctx, Options: options})
	if err != nil {
		log.Fatal("create: ", err)
	}
	if err := instance.Start(); err != nil {
		log.Fatal("start: ", err)
	}
	log.Printf("started: %d users", len(users))

	go func() {
		for range time.Tick(10 * time.Second) {
			st, err := os.Stat(*usersPath)
			if err != nil || st.ModTime().Equal(stamp) {
				continue
			}
			next, nextStamp, err := readUsers(*usersPath)
			if err != nil {
				log.Printf("users: %v", err)
				continue
			}
			stamp = nextStamp
			account.update(next) // first: new users can go out as soon as they get in
			if err := plan.update(instance, next); err != nil {
				log.Printf("update: %v", err)
				continue
			}
			log.Printf("users updated: %d", len(next))
		}
	}()

	stop := make(chan os.Signal, 1)
	signal.Notify(stop, os.Interrupt, syscall.SIGTERM)
	<-stop
	instance.Close()
}

func readUsers(path string) ([]User, time.Time, error) {
	st, err := os.Stat(path)
	if err != nil {
		return nil, time.Time{}, err
	}
	data, err := os.ReadFile(path)
	if err != nil {
		return nil, time.Time{}, err
	}
	var users []User
	if err := json.Unmarshal(data, &users); err != nil {
		return nil, time.Time{}, err
	}
	return users, st.ModTime(), nil
}

func nonAccount(v any) []any {
	list, _ := v.([]any)
	var out []any
	for _, o := range list {
		if m, ok := o.(map[string]any); ok && m["type"] == accountType {
			continue
		}
		out = append(out, o)
	}
	return out
}

// Credentials, the same derivations as server/titan-node-sync.py and the apps.

func digest(text string) []byte { h := sha256.Sum256([]byte(text)); return h[:] }

func tuicUUID(secret string) string {
	u := hex.EncodeToString(digest("titan:" + secret))[32:64]
	return u[:8] + "-" + u[8:12] + "-" + u[12:16] + "-" + u[16:20] + "-" + u[20:]
}

func ss2022Key(text string) string { return base64.StdEncoding.EncodeToString(digest(text)[:16]) }

var ss2022ServerKey = ss2022Key("titan-ss2022-server")
