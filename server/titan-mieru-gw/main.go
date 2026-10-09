// titan-mieru-gw: a Mieru server whose users' traffic leaves through the node's Xray
// (a Remnawave-managed Shadowsocks placeholder on 127.0.0.1) with each user's own
// Shadowsocks password, so Remnawave counts it, shows the connections and applies its
// rules. Replaces mita for accounting.
//
// The config (written by titan-node-sync, MIERU_GATEWAY_CONFIG) is re-read when it
// changes; established connections survive a user list update.
//
//	titan-mieru-gw -config /etc/titan-mieru/config.json
package main

import (
	"context"
	"encoding/json"
	"errors"
	"flag"
	"fmt"
	"io"
	"log"
	"net"
	"os"
	"sync"
	"time"

	mierucommon "github.com/enfein/mieru/v3/apis/common"
	mieruconstant "github.com/enfein/mieru/v3/apis/constant"
	mierumodel "github.com/enfein/mieru/v3/apis/model"
	mieruserver "github.com/enfein/mieru/v3/apis/server"
	mierupb "github.com/enfein/mieru/v3/pkg/appctl/appctlpb"
	shadowsocks "github.com/sagernet/sing-shadowsocks2"
	"github.com/sagernet/sing/common/buf"
	M "github.com/sagernet/sing/common/metadata"
	"google.golang.org/protobuf/proto"
)

type User struct {
	Name     string `json:"name"`
	Password string `json:"password"`
	// Shadowsocks password of this user on the Xray placeholder.
	Secret string `json:"secret"`
}

type Config struct {
	Listen    string `json:"listen"`    // e.g. "0.0.0.0"
	PortRange string `json:"portRange"` // e.g. "30120-30130"
	Transport string `json:"transport"` // TCP (default) or UDP
	Xray      string `json:"xray"`      // Shadowsocks placeholder, e.g. "127.0.0.1:61002"
	Method    string `json:"method"`    // e.g. "chacha20-ietf-poly1305"
	Users     []User `json:"users"`
	// Where to write which IPs each user came from in the last hour (empty: off).
	IPsFile string `json:"ipsFile"`
	// Optional SSH tunnels for the same users (see ssh.go).
	SSH *SSHConfig `json:"ssh"`
}

type gateway struct {
	mu      sync.RWMutex
	cfg     *Config
	ciphers map[string]shadowsocks.Method // user name -> Shadowsocks cipher with their password
	// user name -> password (SSH checks it itself; Mieru does inside its server)
	passwords map[string]string
	sshUp     bool
	ips       *ipLog
}

func main() {
	path := flag.String("config", "/etc/titan-mieru/config.json", "config file")
	flag.Parse()

	g := &gateway{ips: newIPLog()}
	var server mieruserver.Server
	var stamp time.Time
	for {
		if st, err := os.Stat(*path); err == nil && !st.ModTime().Equal(stamp) {
			cfg, ciphers, err := load(*path)
			if err != nil {
				log.Printf("config: %v", err)
			} else {
				stamp = st.ModTime()
				// Only the users changed: hand them to the running server (patched mieru),
				// sessions stay up. Otherwise (first start, other ports): a new server.
				if server != nil && g.cfg != nil && sameListen(g.cfg, cfg) {
					if err := updateUsers(server, cfg); err == nil {
						g.setUsers(cfg, ciphers)
						log.Printf("mieru users updated: %d", len(cfg.Users))
						continue
					} else {
						log.Printf("update users: %v, restarting the server", err)
					}
				}
				next, err := start(cfg)
				if err != nil {
					log.Printf("start: %v", err)
				} else {
					g.setUsers(cfg, ciphers)
					// SSH starts once; later user lists only update the passwords above.
					if cfg.SSH != nil && !g.sshUp {
						if err := g.serveSSH(*cfg.SSH); err != nil {
							log.Printf("ssh: %v", err)
						} else {
							g.sshUp = true
						}
					}
					if server != nil {
						server.Stop() // established connections are kept
					}
					server = next
					go g.accept(server)
					log.Printf("mieru on %s %s, %d users -> %s", cfg.PortRange, cfg.Transport, len(cfg.Users), cfg.Xray)
				}
			}
		}
		g.mu.RLock()
		ipsFile := ""
		if g.cfg != nil {
			ipsFile = g.cfg.IPsFile
		}
		g.mu.RUnlock()
		g.ips.dump(ipsFile)
		time.Sleep(15 * time.Second)
	}
}

func (g *gateway) setUsers(cfg *Config, ciphers map[string]shadowsocks.Method) {
	passwords := make(map[string]string, len(cfg.Users))
	for _, u := range cfg.Users {
		passwords[u.Name] = u.Password
	}
	g.mu.Lock()
	g.cfg, g.ciphers, g.passwords = cfg, ciphers, passwords
	g.mu.Unlock()
}

func sameListen(a, b *Config) bool {
	return a.Listen == b.Listen && a.PortRange == b.PortRange && a.Transport == b.Transport
}

func updateUsers(s mieruserver.Server, cfg *Config) error {
	target, ok := s.(interface{ UpdateUsers([]*mierupb.User) error })
	if !ok {
		return errors.New("mieru without the titan patch")
	}
	users := make([]*mierupb.User, 0, len(cfg.Users))
	for _, u := range cfg.Users {
		users = append(users, &mierupb.User{Name: proto.String(u.Name), Password: proto.String(u.Password)})
	}
	return target.UpdateUsers(users)
}

func load(path string) (*Config, map[string]shadowsocks.Method, error) {
	data, err := os.ReadFile(path)
	if err != nil {
		return nil, nil, err
	}
	cfg := &Config{Transport: "TCP", Method: "chacha20-ietf-poly1305"}
	if err := json.Unmarshal(data, cfg); err != nil {
		return nil, nil, err
	}
	if len(cfg.Users) == 0 || cfg.Xray == "" || cfg.PortRange == "" {
		return nil, nil, errors.New("users, xray and portRange are required")
	}
	ciphers := make(map[string]shadowsocks.Method, len(cfg.Users))
	for _, u := range cfg.Users {
		c, err := shadowsocks.CreateMethod(context.Background(), cfg.Method, shadowsocks.MethodOptions{Password: u.Secret})
		if err != nil {
			return nil, nil, fmt.Errorf("cipher for %s: %w", u.Name, err)
		}
		ciphers[u.Name] = c
	}
	return cfg, ciphers, nil
}

func start(cfg *Config) (mieruserver.Server, error) {
	transport := mierupb.TransportProtocol_TCP.Enum()
	if cfg.Transport == "UDP" {
		transport = mierupb.TransportProtocol_UDP.Enum()
	}
	users := make([]*mierupb.User, 0, len(cfg.Users))
	for _, u := range cfg.Users {
		users = append(users, &mierupb.User{Name: proto.String(u.Name), Password: proto.String(u.Password)})
	}
	s := mieruserver.NewServer()
	if err := s.Store(&mieruserver.ServerConfig{Config: &mierupb.ServerConfig{
		ListenIPAddress: proto.String(cfg.Listen),
		PortBindings:    []*mierupb.PortBinding{{PortRange: proto.String(cfg.PortRange), Protocol: transport}},
		Users:           users,
	}}); err != nil {
		return nil, err
	}
	return s, s.Start()
}

func (g *gateway) accept(s mieruserver.Server) {
	for {
		conn, req, err := s.Accept()
		if err != nil {
			if !s.IsRunning() {
				return
			}
			continue
		}
		go g.handle(conn, req)
	}
}

func (g *gateway) cipher(user string) (shadowsocks.Method, string) {
	g.mu.RLock()
	defer g.mu.RUnlock()
	return g.ciphers[user], g.cfg.Xray
}

// TITAN_DEBUG=1: log every connection.
var debug = os.Getenv("TITAN_DEBUG") != ""

func (g *gateway) handle(conn net.Conn, req *mierumodel.Request) {
	defer conn.Close()
	// One broken connection must not take the whole gateway down.
	defer func() {
		if r := recover(); r != nil {
			log.Printf("connection panic: %v", r)
		}
	}()
	user := conn.(mierucommon.UserContext).UserName()
	ciph, xray := g.cipher(user)
	if ciph == nil {
		return // removed since the connection was authenticated
	}
	if host, _, err := net.SplitHostPort(conn.RemoteAddr().String()); err == nil {
		g.ips.add(user, host)
	}
	if debug {
		log.Printf("%s: %s %v", user, map[uint8]string{1: "CONNECT", 3: "UDP"}[req.Command], req.DstAddr)
	}
	ok := &mierumodel.Response{Reply: mieruconstant.Socks5ReplySuccess, BindAddr: mierumodel.AddrSpec{IP: net.IPv4zero}}
	switch req.Command {
	case mieruconstant.Socks5ConnectCmd:
		raw, err := net.DialTimeout("tcp", xray, 10*time.Second)
		if err != nil {
			log.Printf("%s: xray: %v", user, err)
			return
		}
		// Early: the destination goes out with the first payload, as Shadowsocks clients do.
		up := ciph.DialEarlyConn(raw, destination(req.DstAddr))
		defer up.Close()
		if err := ok.WriteToSocks5(conn); err != nil {
			return
		}
		relay(conn, up)
	case mieruconstant.Socks5UDPAssociateCmd:
		if err := ok.WriteToSocks5(conn); err != nil {
			return
		}
		g.udp(conn, ciph, xray)
	}
}

// udp carries the client's SOCKS5 UDP datagrams (over the Mieru stream) to Xray as
// Shadowsocks UDP, and the replies back.
func (g *gateway) udp(conn net.Conn, ciph shadowsocks.Method, xray string) {
	raw, err := net.Dial("udp", xray)
	if err != nil {
		return
	}
	up := ciph.DialPacketConn(raw)
	defer up.Close()
	tunnel := mierucommon.NewPacketOverStreamTunnel(conn)
	defer tunnel.Close()

	go func() {
		defer tunnel.Close()
		for {
			b := buf.NewPacket()
			from, err := up.ReadPacket(b)
			if err != nil {
				b.Release()
				return
			}
			pkt := make([]byte, 3, 3+M.SocksaddrSerializer.AddrPortLen(from)+b.Len())
			addr := buf.NewSize(M.SocksaddrSerializer.AddrPortLen(from))
			M.SocksaddrSerializer.WriteAddrPort(addr, from)
			pkt = append(append(pkt, addr.Bytes()...), b.Bytes()...)
			addr.Release()
			b.Release()
			if _, err := tunnel.WriteTo(pkt, nil); err != nil {
				return
			}
		}
	}()
	data := make([]byte, 65535)
	for {
		n, _, err := tunnel.ReadFrom(data)
		if err != nil {
			return
		}
		if n < 4 || data[2] != 0 { // fragments aren't supported
			continue
		}
		b := buf.As(data[3:n])
		to, err := M.SocksaddrSerializer.ReadAddrPort(b)
		if err != nil {
			continue
		}
		// Room for the Shadowsocks header (salt + address) and the AEAD tag.
		out := buf.NewSize(udpHeadroom + b.Len() + udpTailroom)
		out.Resize(udpHeadroom, 0)
		out.Write(b.Bytes())
		if err := up.WritePacket(out, to); err != nil {
			return
		}
	}
}

const (
	udpHeadroom = 512
	udpTailroom = 64
)

func destination(a mierumodel.AddrSpec) M.Socksaddr {
	if a.FQDN != "" {
		return M.ParseSocksaddrHostPort(a.FQDN, uint16(a.Port))
	}
	return M.SocksaddrFrom(M.AddrFromIP(a.IP), uint16(a.Port))
}

func relay(a, b net.Conn) {
	done := make(chan struct{}, 2)
	cp := func(dst, src net.Conn) {
		n, err := io.Copy(dst, src)
		if debug {
			log.Printf("copy %v -> %v: %d bytes, %v", src.RemoteAddr(), dst.RemoteAddr(), n, err)
		}
		if cw, ok := dst.(interface{ CloseWrite() error }); ok {
			cw.CloseWrite()
		} else {
			dst.Close()
		}
		done <- struct{}{}
	}
	go cp(a, b)
	go cp(b, a)
	<-done
	<-done
}

// ipLog remembers which IPs each user connected from, for the IP limit check.
type ipLog struct {
	mu   sync.Mutex
	seen map[string]map[string]time.Time
}

func newIPLog() *ipLog { return &ipLog{seen: map[string]map[string]time.Time{}} }

func (l *ipLog) add(user, ip string) {
	l.mu.Lock()
	defer l.mu.Unlock()
	if l.seen[user] == nil {
		l.seen[user] = map[string]time.Time{}
	}
	l.seen[user][ip] = time.Now()
}

// dump writes {"user": ["ip", ...]} for the last hour.
func (l *ipLog) dump(path string) {
	if path == "" {
		return
	}
	l.mu.Lock()
	out := map[string][]string{}
	for user, ips := range l.seen {
		for ip, at := range ips {
			if time.Since(at) > time.Hour {
				delete(ips, ip)
				continue
			}
			out[user] = append(out[user], ip)
		}
		if len(ips) == 0 {
			delete(l.seen, user)
		}
	}
	l.mu.Unlock()
	data, _ := json.Marshal(out)
	tmp := path + ".tmp"
	if os.WriteFile(tmp, data, 0o600) == nil {
		os.Rename(tmp, path)
	}
}
