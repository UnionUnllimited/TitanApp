// Kernel-control types the iOS SDK doesn't expose to Swift; used to find the utun file
// descriptor of our tunnel (same approach as WireGuard's iOS app).
#include <sys/types.h>
#include <sys/socket.h>
#include <sys/ioctl.h>

#define TITAN_CTLIOCGINFO 0xc0644e03UL
#define TITAN_AF_SYSTEM 32
#define TITAN_UTUN_CONTROL_NAME "com.apple.net.utun_control"

struct titan_ctl_info {
    u_int32_t ctl_id;
    char ctl_name[96];
};

struct titan_sockaddr_ctl {
    u_char sc_len;
    u_char sc_family;
    u_int16_t ss_sysaddr;
    u_int32_t sc_id;
    u_int32_t sc_unit;
    u_int32_t sc_reserved[5];
};

#include <string.h>

/// The utun file descriptor NetworkExtension opened for this tunnel, or -1.
static inline int titan_tunnel_fd(void) {
    struct titan_ctl_info info;
    memset(&info, 0, sizeof(info));
    strncpy(info.ctl_name, TITAN_UTUN_CONTROL_NAME, sizeof(info.ctl_name) - 1);
    for (int fd = 0; fd <= 1024; fd++) {
        struct titan_sockaddr_ctl addr;
        socklen_t len = sizeof(addr);
        memset(&addr, 0, sizeof(addr));
        if (getpeername(fd, (struct sockaddr *)&addr, &len) != 0 || addr.sc_family != TITAN_AF_SYSTEM) continue;
        if (info.ctl_id == 0 && ioctl(fd, TITAN_CTLIOCGINFO, &info) != 0) continue;
        if (addr.sc_id == info.ctl_id) return fd;
    }
    return -1;
}
