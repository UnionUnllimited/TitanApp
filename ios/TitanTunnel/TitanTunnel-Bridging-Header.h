// Kernel-control types the iOS SDK doesn't expose to Swift; used to find the utun file
// descriptor of our tunnel (same approach as WireGuard's iOS app). Below: the tunnel's memory.
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

#include <mach/mach.h>
#include <os/proc.h>
#include <stddef.h>

/// The tunnel's memory as jetsam counts it (0 where the kernel doesn't say).
struct titan_mem {
    int64_t footprint; // phys_footprint: what the limit is checked against
    int64_t remaining; // limit_bytes_remaining: left to the limit
    int64_t available; // os_proc_available_memory(): the same, as iOS documents it
};

static inline struct titan_mem titan_mem_read(void) {
    struct titan_mem m = {0, 0, 0};
    task_vm_info_data_t info;
    mach_msg_type_number_t count = TASK_VM_INFO_COUNT;
    if (task_info(mach_task_self(), TASK_VM_INFO, (task_info_t)&info, &count) == KERN_SUCCESS) {
        // An older kernel fills in less of the struct.
        size_t got = (size_t)count * sizeof(natural_t);
        if (got >= offsetof(task_vm_info_data_t, phys_footprint) + sizeof(info.phys_footprint)) m.footprint = info.phys_footprint;
        if (got >= offsetof(task_vm_info_data_t, limit_bytes_remaining) + sizeof(info.limit_bytes_remaining)) m.remaining = info.limit_bytes_remaining;
    }
    m.available = (int64_t)os_proc_available_memory();
    return m;
}
