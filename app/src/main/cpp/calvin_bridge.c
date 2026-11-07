#define _FILE_OFFSET_BITS 64
#define _POSIX_C_SOURCE 200809L
#include <errno.h>
#include <fcntl.h>
#include <limits.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/stat.h>
#include <sys/types.h>
#include <unistd.h>

/*
 * OEX copies this PIE executable into the GUI's private engine directory
 * before it launches it. Therefore the executable must be self-contained:
 * its appended payload contains Calvin's DEX, NNUE weights and NEON library.
 * The launcher extracts those files beside itself, then replaces itself with
 * Android app_process. UCI stdin/stdout/stderr remain inherited by ART.
 */
#define CALVIN_MAIN_CLASS "com.kelseyde.calvin.android.CalvinUciMain"
#define CALVIN_RUNTIME_DIR ".calvin-runtime-6.1.1"
#define CALVIN_MAX_DEX_BYTES (64ULL * 1024ULL * 1024ULL)
#define CALVIN_MAX_NNUE_BYTES (64ULL * 1024ULL * 1024ULL)
#define CALVIN_MAX_NEON_BYTES (16ULL * 1024ULL * 1024ULL)

static const char FOOTER_MAGIC[8] = {'C', 'V', 'R', 'T', '0', '0', '1', '\0'};

/* Footer is appended after [classes.dex][network.nnue][libcalvinneon.so]. */
struct __attribute__((packed)) CalvinPayloadFooter {
    char magic[8];
    uint64_t dex_bytes;
    uint64_t nnue_bytes;
    uint64_t neon_bytes;
};

_Static_assert(sizeof(struct CalvinPayloadFooter) == 32, "Unexpected Calvin payload footer size");

static void report_error(const char *message) {
    fprintf(stdout, "info string Calvin launcher error: %s\n", message);
    fflush(stdout);
    fprintf(stderr, "Calvin launcher error: %s\n", message);
}

static int find_self_path(char *out, size_t capacity) {
    ssize_t length = readlink("/proc/self/exe", out, capacity - 1);
    if (length < 0 || (size_t) length >= capacity - 1) return -1;
    out[length] = '\0';
    return 0;
}

static int parent_directory(char *path) {
    char *slash = strrchr(path, '/');
    if (slash == NULL) return -1;
    if (slash == path) {
        path[1] = '\0';
        return 0;
    }
    *slash = '\0';
    return 0;
}

static int read_exact_at(int fd, void *buffer, size_t length, off_t offset) {
    unsigned char *cursor = (unsigned char *) buffer;
    size_t completed = 0;
    while (completed < length) {
        ssize_t count = pread(fd, cursor + completed, length - completed,
                              offset + (off_t) completed);
        if (count < 0 && errno == EINTR) continue;
        if (count <= 0) return -1;
        completed += (size_t) count;
    }
    return 0;
}

static int write_all(int fd, const void *buffer, size_t length) {
    const unsigned char *cursor = (const unsigned char *) buffer;
    size_t completed = 0;
    while (completed < length) {
        ssize_t count = write(fd, cursor + completed, length - completed);
        if (count < 0 && errno == EINTR) continue;
        if (count <= 0) return -1;
        completed += (size_t) count;
    }
    return 0;
}

static int extract_segment(int source_fd, uint64_t source_offset, uint64_t byte_count,
                           const char *destination) {
    char temporary[PATH_MAX];
    int length = snprintf(temporary, sizeof(temporary), "%s.tmp.%ld",
                          destination, (long) getpid());
    if (length < 0 || (size_t) length >= sizeof(temporary)) return -1;

    int output_fd = open(temporary, O_WRONLY | O_CREAT | O_TRUNC | O_CLOEXEC, 0600);
    if (output_fd < 0) return -1;

    unsigned char buffer[64 * 1024];
    uint64_t copied = 0;
    int result = 0;
    while (copied < byte_count) {
        size_t chunk = (size_t) ((byte_count - copied) < sizeof(buffer)
                ? (byte_count - copied) : sizeof(buffer));
        if (read_exact_at(source_fd, buffer, chunk,
                          (off_t) (source_offset + copied)) != 0
                || write_all(output_fd, buffer, chunk) != 0) {
            result = -1;
            break;
        }
        copied += chunk;
    }

    if (result == 0 && fsync(output_fd) != 0) result = -1;
    if (close(output_fd) != 0) result = -1;
    if (result == 0 && rename(temporary, destination) != 0) result = -1;
    if (result != 0) unlink(temporary);
    return result;
}

static const char *find_app_process(void) {
    static const char *candidates[] = {
        "/system/bin/app_process64",
        "/system/bin/app_process"
    };
    for (size_t i = 0; i < sizeof(candidates) / sizeof(candidates[0]); ++i) {
        if (access(candidates[i], X_OK) == 0) return candidates[i];
    }
    return NULL;
}

static int prepare_runtime(const char *executable_path, char *runtime_dir,
                           size_t runtime_capacity, char *dex_path,
                           size_t dex_capacity, char *nnue_path,
                           size_t nnue_capacity, char *neon_path,
                           size_t neon_capacity) {
    char engine_dir[PATH_MAX];
    if (snprintf(engine_dir, sizeof(engine_dir), "%s", executable_path)
            >= (int) sizeof(engine_dir) || parent_directory(engine_dir) != 0) {
        report_error("cannot determine the GUI engine directory");
        return -1;
    }

    if (snprintf(runtime_dir, runtime_capacity, "%s/%s", engine_dir,
                 CALVIN_RUNTIME_DIR) >= (int) runtime_capacity
            || snprintf(dex_path, dex_capacity, "%s/classes.dex", runtime_dir)
                    >= (int) dex_capacity
            || snprintf(nnue_path, nnue_capacity, "%s/calvin1024_8b.nnue", runtime_dir)
                    >= (int) nnue_capacity
            || snprintf(neon_path, neon_capacity, "%s/libcalvinneon.so", runtime_dir)
                    >= (int) neon_capacity) {
        report_error("runtime path exceeds the Android path limit");
        return -1;
    }

    int source_fd = open(executable_path, O_RDONLY | O_CLOEXEC);
    if (source_fd < 0) {
        report_error("cannot open the copied OEX launcher file");
        return -1;
    }

    struct stat source_stat;
    struct CalvinPayloadFooter footer;
    if (fstat(source_fd, &source_stat) != 0
            || source_stat.st_size < (off_t) sizeof(footer)
            || read_exact_at(source_fd, &footer, sizeof(footer),
                    source_stat.st_size - (off_t) sizeof(footer)) != 0
            || memcmp(footer.magic, FOOTER_MAGIC, sizeof(FOOTER_MAGIC)) != 0) {
        close(source_fd);
        report_error("embedded runtime payload footer is missing or corrupt");
        return -1;
    }

    if (footer.dex_bytes == 0 || footer.dex_bytes > CALVIN_MAX_DEX_BYTES
            || footer.nnue_bytes < 12589058ULL || footer.nnue_bytes > CALVIN_MAX_NNUE_BYTES
            || footer.neon_bytes == 0 || footer.neon_bytes > CALVIN_MAX_NEON_BYTES) {
        close(source_fd);
        report_error("embedded runtime payload has invalid component sizes");
        return -1;
    }

    uint64_t payload_bytes = footer.dex_bytes + footer.nnue_bytes + footer.neon_bytes;
    if (payload_bytes < footer.dex_bytes || payload_bytes < footer.nnue_bytes
            || payload_bytes > (uint64_t) source_stat.st_size - sizeof(footer)) {
        close(source_fd);
        report_error("embedded runtime payload offsets are invalid");
        return -1;
    }
    uint64_t payload_offset = (uint64_t) source_stat.st_size
            - sizeof(footer) - payload_bytes;

    if (mkdir(runtime_dir, 0700) != 0 && errno != EEXIST) {
        close(source_fd);
        report_error("cannot create the GUI-owned Calvin runtime directory");
        return -1;
    }
    struct stat runtime_stat;
    if (stat(runtime_dir, &runtime_stat) != 0 || !S_ISDIR(runtime_stat.st_mode)) {
        close(source_fd);
        report_error("Calvin runtime path is not a directory");
        return -1;
    }

    uint64_t dex_offset = payload_offset;
    uint64_t nnue_offset = dex_offset + footer.dex_bytes;
    uint64_t neon_offset = nnue_offset + footer.nnue_bytes;
    int result = extract_segment(source_fd, dex_offset, footer.dex_bytes, dex_path);
    if (result == 0) result = extract_segment(source_fd, nnue_offset,
                                               footer.nnue_bytes, nnue_path);
    if (result == 0) result = extract_segment(source_fd, neon_offset,
                                               footer.neon_bytes, neon_path);
    close(source_fd);

    if (result != 0) {
        report_error("unable to extract the embedded DEX, NNUE or NEON runtime");
        return -1;
    }
    return 0;
}

int main(void) {
    char executable_path[PATH_MAX];
    char runtime_dir[PATH_MAX];
    char dex_path[PATH_MAX];
    char nnue_path[PATH_MAX];
    char neon_path[PATH_MAX];

    if (find_self_path(executable_path, sizeof(executable_path)) != 0) {
        report_error("cannot resolve /proc/self/exe");
        return 70;
    }
    if (prepare_runtime(executable_path, runtime_dir, sizeof(runtime_dir),
                        dex_path, sizeof(dex_path), nnue_path, sizeof(nnue_path),
                        neon_path, sizeof(neon_path)) != 0) {
        return 71;
    }

    const char *app_process = find_app_process();
    if (app_process == NULL) {
        report_error("Android app_process64/app_process was not found or is not executable");
        return 72;
    }

    if (setenv("CLASSPATH", dex_path, 1) != 0
            || setenv("CALVIN_NNUE_PATH", nnue_path, 1) != 0
            || setenv("CALVIN_NEON_LIBRARY", neon_path, 1) != 0) {
        report_error("cannot configure the standalone ART runtime environment");
        return 73;
    }

    char *const child_argv[] = {
        (char *) app_process,
        (char *) "--nice-name=calvin-6.1.1",
        (char *) "/system/bin",
        (char *) CALVIN_MAIN_CLASS,
        NULL
    };

    execv(app_process, child_argv);
    char message[256];
    snprintf(message, sizeof(message), "exec app_process failed: %s", strerror(errno));
    report_error(message);
    return 74;
}
