#define _GNU_SOURCE
#include <errno.h>
#include <poll.h>
#include <signal.h>
#include <stdio.h>
#include <string.h>
#include <arpa/inet.h>
#include <netinet/in.h>
#include <netinet/tcp.h>
#include <sys/socket.h>
#include <time.h>
#include <unistd.h>

/* Must match CalvinEngineService.SOCKET_PORT and SOCKET_TOKEN. */
#define SOCKET_HOST "127.0.0.1"
#define SOCKET_PORT 43187
#define SOCKET_TOKEN "CalvinOEXBridgeV1-6f9c1a82"
#define CONNECT_ATTEMPTS 120
#define CONNECT_DELAY_NS 100000000L
#define BUFFER_SIZE 8192

static int write_all(int fd, const char *buffer, size_t length) {
    size_t offset = 0;
    while (offset < length) {
        ssize_t written = write(fd, buffer + offset, length - offset);
        if (written > 0) {
            offset += (size_t)written;
            continue;
        }
        if (written < 0 && errno == EINTR) continue;
        return -1;
    }
    return 0;
}

static int connect_to_calvin(void) {
    struct sockaddr_in address;
    memset(&address, 0, sizeof(address));
    address.sin_family = AF_INET;
    address.sin_port = htons(SOCKET_PORT);
    if (inet_pton(AF_INET, SOCKET_HOST, &address.sin_addr) != 1) {
        fprintf(stderr, "Calvin bridge: invalid loopback address\n");
        return -1;
    }

    for (int attempt = 0; attempt < CONNECT_ATTEMPTS; ++attempt) {
        int fd = socket(AF_INET, SOCK_STREAM | SOCK_CLOEXEC, 0);
        if (fd < 0) {
            fprintf(stderr, "Calvin bridge: socket() failed: %s\n", strerror(errno));
            return -1;
        }
        if (connect(fd, (struct sockaddr *)&address, sizeof(address)) == 0) {
            int no_delay = 1;
            (void)setsockopt(fd, IPPROTO_TCP, TCP_NODELAY, &no_delay, sizeof(no_delay));
            if (write_all(fd, SOCKET_TOKEN, sizeof(SOCKET_TOKEN) - 1) == 0) return fd;
            fprintf(stderr, "Calvin bridge: failed to authenticate with Calvin service: %s\n",
                    strerror(errno));
            close(fd);
            return -1;
        }

        int saved_errno = errno;
        close(fd);
        if (saved_errno != ECONNREFUSED && saved_errno != EINTR
                && saved_errno != EAGAIN && saved_errno != ETIMEDOUT) {
            fprintf(stderr, "Calvin bridge: cannot connect to Calvin service: %s\n",
                    strerror(saved_errno));
            return -1;
        }
        struct timespec delay = { .tv_sec = 0, .tv_nsec = CONNECT_DELAY_NS };
        nanosleep(&delay, NULL);
    }

    fprintf(stderr, "Calvin bridge: Calvin service did not become available. "
            "Open/select the Calvin OEX engine again after checking the OEX app.\n");
    return -1;
}

int main(void) {
    signal(SIGPIPE, SIG_IGN);

    int socket_fd = connect_to_calvin();
    if (socket_fd < 0) return 2;

    int stdin_open = 1;
    char buffer[BUFFER_SIZE];
    struct pollfd descriptors[2];

    while (1) {
        descriptors[0].fd = stdin_open ? STDIN_FILENO : -1;
        descriptors[0].events = POLLIN | POLLHUP;
        descriptors[0].revents = 0;
        descriptors[1].fd = socket_fd;
        descriptors[1].events = POLLIN | POLLHUP;
        descriptors[1].revents = 0;

        int ready = poll(descriptors, 2, -1);
        if (ready < 0) {
            if (errno == EINTR) continue;
            fprintf(stderr, "Calvin bridge: poll() failed: %s\n", strerror(errno));
            break;
        }

        if (stdin_open && (descriptors[0].revents & (POLLIN | POLLHUP))) {
            ssize_t count = read(STDIN_FILENO, buffer, sizeof(buffer));
            if (count > 0) {
                if (write_all(socket_fd, buffer, (size_t)count) != 0) break;
            } else if (count == 0) {
                stdin_open = 0;
                (void)shutdown(socket_fd, SHUT_WR);
            } else if (errno != EINTR) {
                break;
            }
        }

        if (descriptors[1].revents & POLLIN) {
            ssize_t count = read(socket_fd, buffer, sizeof(buffer));
            if (count > 0) {
                if (write_all(STDOUT_FILENO, buffer, (size_t)count) != 0) break;
            } else if (count == 0) {
                break;
            } else if (errno != EINTR) {
                break;
            }
        } else if (descriptors[1].revents & (POLLERR | POLLHUP | POLLNVAL)) {
            break;
        }

        if (descriptors[1].revents & POLLNVAL) break;
        if (descriptors[0].revents & (POLLERR | POLLNVAL)) break;
    }

    close(socket_fd);
    return 0;
}
