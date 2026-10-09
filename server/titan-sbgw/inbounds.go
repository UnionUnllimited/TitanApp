package main

import (
	"fmt"

	box "github.com/sagernet/sing-box"
	"github.com/sagernet/sing-box/option"
	"github.com/sagernet/sing/common/auth"
)

// inboundPlan: which inbounds get users, and in what form.
type inboundPlan struct {
	byTag map[string]string // tag -> type (naive, tuic, anytls, shadowtls, ss2022)
}

func planInbounds(inbounds []any) inboundPlan {
	plan := inboundPlan{byTag: map[string]string{}}
	detours := map[string]bool{}
	for _, v := range inbounds {
		if m, ok := v.(map[string]any); ok && m["type"] == "shadowtls" {
			if d, _ := m["detour"].(string); d != "" {
				detours[d] = true
			}
		}
	}
	for _, v := range inbounds {
		m, ok := v.(map[string]any)
		if !ok {
			continue
		}
		tag, _ := m["tag"].(string)
		switch t, _ := m["type"].(string); {
		case t == "naive" || t == "tuic" || t == "anytls" || t == "shadowtls":
			plan.byTag[tag] = t
		case t == "shadowsocks" && detours[tag]:
			// ShadowTLS v3 doesn't pass its user on: the Shadowsocks 2022 behind it does.
			plan.byTag[tag] = "ss2022"
		}
	}
	return plan
}

// apply writes the users into the JSON config (for the start).
func (p inboundPlan) apply(inbounds []any, users []User) {
	for _, v := range inbounds {
		m, ok := v.(map[string]any)
		if !ok {
			continue
		}
		tag, _ := m["tag"].(string)
		switch p.byTag[tag] {
		case "naive":
			m["users"] = list(users, true, func(u User) any { return map[string]any{"username": u.Name, "password": u.Password} })
		case "anytls", "shadowtls":
			m["users"] = list(users, true, func(u User) any { return map[string]any{"name": u.Name, "password": u.Password} })
			if p.byTag[tag] == "shadowtls" {
				m["version"] = 3
			}
		case "tuic":
			m["users"] = list(users, false, func(u User) any {
				return map[string]any{"name": u.Name, "uuid": tuicUUID(u.Secret), "password": u.Password}
			})
		case "ss2022":
			m["method"] = "2022-blake3-aes-128-gcm"
			m["password"] = ss2022ServerKey
			m["users"] = list(users, false, func(u User) any {
				return map[string]any{"name": u.Name, "password": ss2022Key("titan-ss2022:" + u.Secret)}
			})
		}
	}
}

// list maps the users; withTest: also the test users (no secret). Never empty: an inbound
// without users fails to start (or, for some, would let anyone in).
func list(users []User, withTest bool, f func(User) any) []any {
	var out []any
	for _, u := range users {
		if u.Secret == "" && !withTest {
			continue
		}
		out = append(out, f(u))
	}
	if len(out) == 0 {
		out = append(out, f(User{Name: "disabled", Password: randomHex(16), Secret: randomHex(16)}))
	}
	return out
}

// update hands the new users to the running inbounds (patched sing-box, no restart).
func (p inboundPlan) update(instance *box.Box, users []User) error {
	for tag, kind := range p.byTag {
		in, ok := instance.Inbound().Get(tag)
		if !ok {
			return fmt.Errorf("inbound %s not found", tag)
		}
		switch kind {
		case "naive":
			target, ok := in.(interface{ UpdateUsers([]auth.User) })
			if !ok {
				return fmt.Errorf("%s: sing-box without the titan patch", tag)
			}
			target.UpdateUsers(typed(users, true, func(u User) auth.User { return auth.User{Username: u.Name, Password: u.Password} }))
		case "tuic":
			target, ok := in.(interface{ UpdateUsers([]option.TUICUser) error })
			if !ok {
				return fmt.Errorf("%s: sing-box without the titan patch", tag)
			}
			if err := target.UpdateUsers(typed(users, false, func(u User) option.TUICUser {
				return option.TUICUser{Name: u.Name, UUID: tuicUUID(u.Secret), Password: u.Password}
			})); err != nil {
				return err
			}
		case "anytls":
			target, ok := in.(interface{ UpdateUsers([]option.AnyTLSUser) })
			if !ok {
				return fmt.Errorf("%s: sing-box without the titan patch", tag)
			}
			target.UpdateUsers(typed(users, true, func(u User) option.AnyTLSUser { return option.AnyTLSUser{Name: u.Name, Password: u.Password} }))
		case "shadowtls":
			target, ok := in.(interface{ UpdateUsers([]option.ShadowTLSUser) })
			if !ok {
				return fmt.Errorf("%s: sing-box without the titan patch", tag)
			}
			target.UpdateUsers(typed(users, true, func(u User) option.ShadowTLSUser { return option.ShadowTLSUser{Name: u.Name, Password: u.Password} }))
		case "ss2022":
			// Stock sing-box: the multi-user Shadowsocks inbound can already do this.
			target, ok := in.(interface {
				UpdateUsers([]string, []string) error
			})
			if !ok {
				return fmt.Errorf("%s: not a multi-user shadowsocks inbound", tag)
			}
			var names, keys []string
			for _, u := range users {
				if u.Secret != "" {
					names = append(names, u.Name)
					keys = append(keys, ss2022Key("titan-ss2022:"+u.Secret))
				}
			}
			if len(names) == 0 {
				names, keys = []string{"disabled"}, []string{ss2022Key(randomHex(16))}
			}
			if err := target.UpdateUsers(names, keys); err != nil {
				return err
			}
		}
	}
	return nil
}

func typed[T any](users []User, withTest bool, f func(User) T) []T {
	var out []T
	for _, u := range users {
		if u.Secret == "" && !withTest {
			continue
		}
		out = append(out, f(u))
	}
	if len(out) == 0 {
		out = append(out, f(User{Name: "disabled", Password: randomHex(16), Secret: randomHex(16)}))
	}
	return out
}
