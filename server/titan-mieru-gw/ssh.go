package main

import (
	"crypto/ed25519"
	"crypto/rand"
	"crypto/subtle"
	"encoding/pem"
	"errors"
	"log"
	"net"
	"os"
	"time"

	M "github.com/sagernet/sing/common/metadata"
	"golang.org/x/crypto/ssh"
)

// SSHConfig: an SSH server for "ssh -D"-style tunnels (direct-tcpip channels only, no
// shell). Users are the same as Mieru's; each channel leaves through Xray like Mieru does.
type SSHConfig struct {
	Listen  string `json:"listen"`  // e.g. "0.0.0.0:2222"
	Xray    string `json:"xray"`    // Shadowsocks placeholder, e.g. "127.0.0.1:61008"
	HostKey string `json:"hostKey"` // created on first start if missing
}

// What clients see: the banner of a stock Ubuntu OpenSSH.
const sshVersion = "SSH-2.0-OpenSSH_9.6p1 Ubuntu-3ubuntu13.5"

func (g *gateway) serveSSH(cfg SSHConfig) error {
	signer, err := hostKey(cfg.HostKey)
	if err != nil {
		return err
	}
	server := &ssh.ServerConfig{
		ServerVersion: sshVersion,
		PasswordCallback: func(meta ssh.ConnMetadata, password []byte) (*ssh.Permissions, error) {
			g.mu.RLock()
			want, ok := g.passwords[meta.User()]
			g.mu.RUnlock()
			if ok && subtle.ConstantTimeCompare([]byte(want), password) == 1 {
				return &ssh.Permissions{}, nil
			}
			time.Sleep(time.Second) // slow down guessing
			return nil, errors.New("denied")
		},
	}
	server.AddHostKey(signer)
	ln, err := net.Listen("tcp", cfg.Listen)
	if err != nil {
		return err
	}
	log.Printf("ssh on %s -> %s", cfg.Listen, cfg.Xray)
	go func() {
		for {
			conn, err := ln.Accept()
			if err != nil {
				continue
			}
			go g.handleSSH(conn, server, cfg.Xray)
		}
	}()
	return nil
}

func (g *gateway) handleSSH(raw net.Conn, server *ssh.ServerConfig, xray string) {
	defer raw.Close()
	defer func() {
		if r := recover(); r != nil {
			log.Printf("ssh panic: %v", r)
		}
	}()
	raw.SetDeadline(time.Now().Add(30 * time.Second))
	conn, chans, reqs, err := ssh.NewServerConn(raw, server)
	if err != nil {
		return
	}
	defer conn.Close()
	raw.SetDeadline(time.Time{})
	user := conn.User()
	if host, _, err := net.SplitHostPort(raw.RemoteAddr().String()); err == nil {
		g.ips.add(user, host)
	}
	go ssh.DiscardRequests(reqs)
	for nc := range chans {
		if nc.ChannelType() != "direct-tcpip" {
			nc.Reject(ssh.UnknownChannelType, "only port forwarding")
			continue
		}
		var target struct {
			Host       string
			Port       uint32
			OriginHost string
			OriginPort uint32
		}
		if err := ssh.Unmarshal(nc.ExtraData(), &target); err != nil {
			nc.Reject(ssh.ConnectionFailed, "bad request")
			continue
		}
		go g.forwardSSH(nc, user, M.ParseSocksaddrHostPort(target.Host, uint16(target.Port)), xray)
	}
}

func (g *gateway) forwardSSH(nc ssh.NewChannel, user string, dest M.Socksaddr, xray string) {
	ciph, _ := g.cipher(user)
	if ciph == nil {
		nc.Reject(ssh.Prohibited, "denied")
		return
	}
	raw, err := net.DialTimeout("tcp", xray, 10*time.Second)
	if err != nil {
		nc.Reject(ssh.ConnectionFailed, "upstream")
		return
	}
	up := ciph.DialEarlyConn(raw, dest)
	defer up.Close()
	ch, reqs, err := nc.Accept()
	if err != nil {
		return
	}
	go ssh.DiscardRequests(reqs)
	if debug {
		log.Printf("%s: ssh %s", user, dest)
	}
	relay(channelConn{ch}, up)
}

// channelConn lets relay() treat an SSH channel like a connection.
type channelConn struct{ ssh.Channel }

func (c channelConn) CloseWrite() error                { return c.Channel.CloseWrite() }
func (channelConn) LocalAddr() net.Addr                { return &net.TCPAddr{} }
func (channelConn) RemoteAddr() net.Addr               { return &net.TCPAddr{} }
func (channelConn) SetDeadline(t time.Time) error      { return nil }
func (channelConn) SetReadDeadline(t time.Time) error  { return nil }
func (channelConn) SetWriteDeadline(t time.Time) error { return nil }

func hostKey(path string) (ssh.Signer, error) {
	if data, err := os.ReadFile(path); err == nil {
		return ssh.ParsePrivateKey(data)
	}
	_, key, err := ed25519.GenerateKey(rand.Reader)
	if err != nil {
		return nil, err
	}
	block, err := ssh.MarshalPrivateKey(key, "")
	if err != nil {
		return nil, err
	}
	if err := os.WriteFile(path, pem.EncodeToMemory(block), 0o600); err != nil {
		return nil, err
	}
	return ssh.NewSignerFromKey(key)
}
