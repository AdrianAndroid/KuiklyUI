/*
 * Tencent is pleased to support the open source community by making KuiklyUI
 * available.
 * Copyright (C) 2025 Tencent. All rights reserved.
 * Licensed under the License of KuiklyUI;
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * https://github.com/Tencent-TDS/KuiklyUI/blob/main/LICENSE
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
#include "KRSftpSession.h"

#include <fcntl.h>
#include <netdb.h>
#include <netinet/in.h>
#include <netinet/tcp.h>
#include <sys/select.h>
#include <sys/socket.h>
#include <sys/stat.h>
#include <unistd.h>

#include <algorithm>
#include <cctype>
#include <cstdint>
#include <cstdio>
#include <cstring>
#include <stdexcept>
#include <string>
#include <vector>

#include "libohos_render/foundation/type/KRRenderValue.h"

namespace kuikly {
namespace module {

namespace {

constexpr int kIoTimeoutSec = 30;
constexpr int kMaxReadChunk = 64 * 1024;

void EnsureLibssh2() {
    static std::once_flag once;
    std::call_once(once, [] { libssh2_init(0); });
}

std::string JoinRemote(const std::string &dir, const std::string &name) {
    if (dir.empty() || dir == "/") {
        return "/" + name;
    }
    if (dir.back() == '/') {
        return dir + name;
    }
    return dir + "/" + name;
}

std::string ParentDir(const std::string &path) {
    auto pos = path.find_last_of('/');
    if (pos == std::string::npos) {
        return "";
    }
    if (pos == 0) {
        return "/";
    }
    return path.substr(0, pos);
}

std::string BaseName(const std::string &path) {
    auto pos = path.find_last_of('/');
    if (pos == std::string::npos) {
        return path;
    }
    return path.substr(pos + 1);
}

/** 权限位 → `ls -l` 风格字符串（drwxr-xr-x）。 */
std::string PermString(unsigned long mode, bool isDir, bool isLink) {
    std::string out;
    out.push_back(isLink ? 'l' : (isDir ? 'd' : '-'));
    const char *rwx = "rwx";
    for (int shift = 6; shift >= 0; shift -= 3) {
        for (int bit = 0; bit < 3; ++bit) {
            bool set = (mode >> (shift + (2 - bit))) & 0x1;
            if (set) {
                out.push_back(rwx[bit]);
            } else {
                out.push_back('-');
            }
        }
    }
    return out;
}

KRRenderValueMap EntryMap(const std::string &name, const std::string &path,
                          const LIBSSH2_SFTP_ATTRIBUTES &a, bool followSymlink) {
    bool isLink = (a.flags & LIBSSH2_SFTP_ATTR_PERMISSIONS) && S_ISLNK(a.permissions);
    bool isDir = (a.flags & LIBSSH2_SFTP_ATTR_PERMISSIONS) && S_ISDIR(a.permissions);
    KRRenderValueMap m;
    m["name"] = KRRenderValue::Make(name);
    m["path"] = KRRenderValue::Make(path);
    m["isDir"] = KRRenderValue::Make(isDir && !isLink);
    m["size"] = KRRenderValue::Make(static_cast<int64_t>(a.filesize));
    m["mtime"] = KRRenderValue::Make(static_cast<int64_t>(a.mtime));
    m["permission"] = KRRenderValue::Make(
        PermString(static_cast<unsigned long>(a.permissions), isDir, isLink));
    if (a.flags & LIBSSH2_SFTP_ATTR_UIDGID) {
        m["uid"] = KRRenderValue::Make(static_cast<int>(a.uid));
        m["gid"] = KRRenderValue::Make(static_cast<int>(a.gid));
    }
    if (isLink) {
        m["isSymlink"] = KRRenderValue::Make(true);
        m["followsTarget"] = KRRenderValue::Make(followSymlink);
    }
    return m;
}

class MethodGuard {
 public:
    /** 取会话并持有其 io 锁；会话不存在或已断开抛异常。 */
    static sftp_internal::SessionPtr Acquire(const std::string &sessionId,
                                             std::unique_lock<std::recursive_mutex> &lock) {
        auto session = KRSftpSession::FindSession(sessionId);
        if (!session) {
            throw std::runtime_error("session not found: " + sessionId);
        }
        lock = std::unique_lock<std::recursive_mutex>(session->io);
        if (session->broken) {
            throw std::runtime_error("session is broken");
        }
        return session;
    }
};

[[noreturn]] void ThrowSftp(const sftp_internal::SessionPtr &s, const std::string &what) {
    s->broken = true;
    throw std::runtime_error(what + ": sftp error " +
                             std::to_string(libssh2_sftp_last_error(s->sftp)));
}

bool StatImpl(const sftp_internal::SessionPtr &s,
              const std::string &path,
              bool follow,
              LIBSSH2_SFTP_ATTRIBUTES *out) {
    int rc = follow ? libssh2_sftp_stat(s->sftp, path.c_str(), out)
                    : libssh2_sftp_lstat(s->sftp, path.c_str(), out);
    return rc == 0;
}

void EnsureRemoteDir(const sftp_internal::SessionPtr &s, const std::string &dir) {
    if (dir.empty() || dir == "/") {
        return;
    }
    LIBSSH2_SFTP_ATTRIBUTES attrs{};
    if (StatImpl(s, dir, true, &attrs)) {
        return;
    }
    EnsureRemoteDir(s, ParentDir(dir));
    if (libssh2_sftp_mkdir(s->sftp, dir.c_str(), 0755) != 0) {
        LIBSSH2_SFTP_ATTRIBUTES check{};
        if (!StatImpl(s, dir, true, &check)) {
            ThrowSftp(s, "mkdir " + dir);
        }
    }
}

void RemoveRemoteRecursive(const sftp_internal::SessionPtr &s, const std::string &path) {
    LIBSSH2_SFTP_ATTRIBUTES attrs{};
    if (!StatImpl(s, path, false, &attrs)) {
        throw std::runtime_error("stat failed before remove: " + path);
    }
    bool isDir = (attrs.flags & LIBSSH2_SFTP_ATTR_PERMISSIONS) && S_ISDIR(attrs.permissions);
    if (!isDir) {
        if (libssh2_sftp_unlink(s->sftp, path.c_str()) != 0) {
            ThrowSftp(s, "unlink " + path);
        }
        return;
    }
    LIBSSH2_SFTP_HANDLE *dir = libssh2_sftp_opendir(s->sftp, path.c_str());
    if (!dir) {
        ThrowSftp(s, "opendir " + path);
    }
    std::vector<std::string> children;
    char name[512];
    LIBSSH2_SFTP_ATTRIBUTES child{};
    int rc;
    while ((rc = libssh2_sftp_readdir_ex(dir, name, sizeof(name), nullptr, 0, &child)) > 0) {
        std::string n(name);
        if (n == "." || n == "..") {
            continue;
        }
        children.push_back(JoinRemote(path, n));
    }
    libssh2_sftp_closedir(dir);
    for (const auto &childPath : children) {
        RemoveRemoteRecursive(s, childPath);
    }
    if (libssh2_sftp_rmdir(s->sftp, path.c_str()) != 0) {
        ThrowSftp(s, "rmdir " + path);
    }
}

struct TransferResult {
    long long bytes = 0;
};

/** 远端 → 本地，支持 offset 续传。 */
TransferResult CopyRemoteToLocal(const sftp_internal::SessionPtr &s,
                                 const std::string &remotePath,
                                 const std::string &localPath,
                                 long long offset) {
    LIBSSH2_SFTP_HANDLE *src = libssh2_sftp_open(s->sftp, remotePath.c_str(),
                                                 LIBSSH2_FXF_READ, 0);
    if (!src) {
        ThrowSftp(s, "open remote for read " + remotePath);
    }
    int flags = O_WRONLY | O_CREAT | (offset > 0 ? 0 : O_TRUNC);
    int fd = ::open(localPath.c_str(), flags, 0644);
    if (fd < 0) {
        libssh2_sftp_close(src);
        throw std::runtime_error("open local for write failed: " + localPath);
    }
    if (offset > 0) {
        libssh2_sftp_seek64(src, static_cast<libssh2_uint64_t>(offset));
        if (::lseek(fd, static_cast<off_t>(offset), SEEK_SET) < 0) {
            ::close(fd);
            libssh2_sftp_close(src);
            throw std::runtime_error("lseek local failed: " + localPath);
        }
    }
    TransferResult result;
    std::vector<char> buf(kMaxReadChunk);
    for (;;) {
        ssize_t n = libssh2_sftp_read(src, buf.data(), buf.size());
        if (n < 0) {
            ::close(fd);
            libssh2_sftp_close(src);
            ThrowSftp(s, "read remote " + remotePath);
        }
        if (n == 0) {
            break;
        }
        ssize_t written = 0;
        while (written < n) {
            ssize_t w = ::write(fd, buf.data() + written, static_cast<size_t>(n - written));
            if (w <= 0) {
                ::close(fd);
                libssh2_sftp_close(src);
                throw std::runtime_error("write local failed: " + localPath);
            }
            written += w;
        }
        result.bytes += n;
    }
    ::close(fd);
    libssh2_sftp_close(src);
    return result;
}

/** 本地 → 远端，支持 offset 续传。 */
TransferResult CopyLocalToRemote(const sftp_internal::SessionPtr &s,
                                 const std::string &localPath,
                                 const std::string &remotePath,
                                 long long offset) {
    int fd = ::open(localPath.c_str(), O_RDONLY);
    if (fd < 0) {
        throw std::runtime_error("open local for read failed: " + localPath);
    }
    // 续传（offset>0）不能用 APPEND：服务端会忽略 seek 位置，导致断点续传错位。
    long flags = LIBSSH2_FXF_WRITE | LIBSSH2_FXF_CREAT |
                 (offset > 0 ? 0 : LIBSSH2_FXF_TRUNC);
    LIBSSH2_SFTP_HANDLE *dst = libssh2_sftp_open(s->sftp, remotePath.c_str(), flags, 0644);
    if (!dst) {
        ::close(fd);
        ThrowSftp(s, "open remote for write " + remotePath);
    }
    if (offset > 0) {
        if (::lseek(fd, static_cast<off_t>(offset), SEEK_SET) < 0) {
            ::close(fd);
            libssh2_sftp_close(dst);
            throw std::runtime_error("lseek local failed: " + localPath);
        }
        libssh2_sftp_seek64(dst, static_cast<libssh2_uint64_t>(offset));
    }
    TransferResult result;
    std::vector<char> buf(kMaxReadChunk);
    for (;;) {
        ssize_t n = ::read(fd, buf.data(), buf.size());
        if (n < 0) {
            ::close(fd);
            libssh2_sftp_close(dst);
            throw std::runtime_error("read local failed: " + localPath);
        }
        if (n == 0) {
            break;
        }
        ssize_t written = 0;
        while (written < n) {
            ssize_t w = libssh2_sftp_write(dst, buf.data() + written,
                                           static_cast<size_t>(n - written));
            if (w < 0) {
                ::close(fd);
                libssh2_sftp_close(dst);
                ThrowSftp(s, "write remote " + remotePath);
            }
            written += w;
        }
        result.bytes += n;
    }
    ::close(fd);
    libssh2_sftp_close(dst);
    return result;
}

/** 本地建目录（含父级）；失败不抛，交给后续 open 报出明确错误。 */
void EnsureLocalDir(const std::string &dir) {
    if (dir.empty() || dir == "/" || dir == ".") {
        return;
    }
    EnsureLocalDir(ParentDir(dir));
    if (::mkdir(dir.c_str(), 0755) != 0 && errno != EEXIST) {
        // 目录不可用时由后续 open/写失败给出准确错误，这里只做尽力创建。
    }
}

/** 远端 → 远端流式复制（不经本地临时文件，避免沙盒不可写与重复传输）。 */
TransferResult CopyRemoteToRemote(const sftp_internal::SessionPtr &s,
                                  const std::string &srcPath,
                                  const std::string &destPath) {
    LIBSSH2_SFTP_HANDLE *src = libssh2_sftp_open(s->sftp, srcPath.c_str(), LIBSSH2_FXF_READ, 0);
    if (!src) {
        ThrowSftp(s, "open remote for read " + srcPath);
    }
    LIBSSH2_SFTP_HANDLE *dst =
        libssh2_sftp_open(s->sftp, destPath.c_str(),
                          LIBSSH2_FXF_WRITE | LIBSSH2_FXF_CREAT | LIBSSH2_FXF_TRUNC, 0644);
    if (!dst) {
        libssh2_sftp_close(src);
        ThrowSftp(s, "open remote for write " + destPath);
    }
    TransferResult result;
    std::vector<char> buf(kMaxReadChunk);
    for (;;) {
        ssize_t n = libssh2_sftp_read(src, buf.data(), buf.size());
        if (n < 0) {
            libssh2_sftp_close(src);
            libssh2_sftp_close(dst);
            ThrowSftp(s, "read remote " + srcPath);
        }
        if (n == 0) {
            break;
        }
        ssize_t written = 0;
        while (written < n) {
            ssize_t w = libssh2_sftp_write(dst, buf.data() + written,
                                           static_cast<size_t>(n - written));
            if (w < 0) {
                libssh2_sftp_close(src);
                libssh2_sftp_close(dst);
                ThrowSftp(s, "write remote " + destPath);
            }
            written += w;
        }
        result.bytes += n;
    }
    libssh2_sftp_close(src);
    libssh2_sftp_close(dst);
    return result;
}

void CopyRemoteRecursive(const sftp_internal::SessionPtr &s,
                         const std::string &srcPath,
                         const std::string &destPath,
                         int *fileCount,
                         int *dirCount) {
    LIBSSH2_SFTP_ATTRIBUTES attrs{};
    if (!StatImpl(s, srcPath, false, &attrs)) {
        throw std::runtime_error("stat failed before copy: " + srcPath);
    }
    bool isLink = (attrs.flags & LIBSSH2_SFTP_ATTR_PERMISSIONS) && S_ISLNK(attrs.permissions);
    bool isDir = (attrs.flags & LIBSSH2_SFTP_ATTR_PERMISSIONS) && S_ISDIR(attrs.permissions);
    if (isLink) {
        // 复制符号链接：读出目标并重建链接。
        char target[1024];
        int rc = libssh2_sftp_symlink_ex(s->sftp, srcPath.c_str(),
                                         static_cast<unsigned int>(srcPath.size()),
                                         target, sizeof(target) - 1, LIBSSH2_SFTP_READLINK);
        if (rc < 0) {
            ThrowSftp(s, "readlink " + srcPath);
        }
        target[rc] = '\0';
        // libssh2_sftp_symlink 宏的 linkpath 参数非 const，需可写缓冲。
        std::vector<char> linkBuf(destPath.begin(), destPath.end());
        linkBuf.push_back('\0');
        if (libssh2_sftp_symlink(s->sftp, target, linkBuf.data()) != 0) {
            ThrowSftp(s, "symlink " + destPath);
        }
        return;
    }
    if (!isDir) {
        CopyRemoteToRemote(s, srcPath, destPath);
        if (fileCount) {
            ++(*fileCount);
        }
        return;
    }
    EnsureRemoteDir(s, destPath);
    if (dirCount) {
        ++(*dirCount);
    }
    LIBSSH2_SFTP_HANDLE *dir = libssh2_sftp_opendir(s->sftp, srcPath.c_str());
    if (!dir) {
        ThrowSftp(s, "opendir " + srcPath);
    }
    std::vector<std::string> names;
    char name[512];
    LIBSSH2_SFTP_ATTRIBUTES child{};
    int rc;
    while ((rc = libssh2_sftp_readdir_ex(dir, name, sizeof(name), nullptr, 0, &child)) > 0) {
        std::string n(name);
        if (n == "." || n == "..") {
            continue;
        }
        names.push_back(n);
    }
    libssh2_sftp_closedir(dir);
    for (const auto &n : names) {
        CopyRemoteRecursive(s, JoinRemote(srcPath, n), JoinRemote(destPath, n), fileCount, dirCount);
    }
}

unsigned long ParseMode(const std::string &mode) {
    if (mode.empty()) {
        return 0;
    }
    return static_cast<unsigned long>(std::stoul(mode, nullptr, 8));
}

long long ValueAsInt64(const KRRenderValue::Map &m, const std::string &key, long long fallback) {
    auto it = m.find(key);
    if (it == m.end() || !it->second) {
        return fallback;
    }
    return it->second->toLong();
}

std::string ValueAsString(const KRRenderValue::Map &m, const std::string &key) {
    auto it = m.find(key);
    if (it == m.end() || !it->second) {
        return "";
    }
    return it->second->toString();
}

bool ValueAsBool(const KRRenderValue::Map &m, const std::string &key, bool fallback) {
    auto it = m.find(key);
    if (it == m.end() || !it->second) {
        return fallback;
    }
    return it->second->toBool();
}

KRRenderValue::Map ParamsMap(const KRAnyValue &params) {
    if (!params) {
        throw std::runtime_error("sftp: empty params");
    }
    return params->toMap();
}

}  // namespace

std::mutex KRSftpSession::gLock;
std::unordered_map<std::string, std::shared_ptr<sftp_internal::SessionHandle>> KRSftpSession::gSessions;
long long KRSftpSession::gSessionIdCounter = 0;

namespace sftp_internal {

std::string Libssh2Error(LIBSSH2_SESSION *session, const std::string &what) {
    char *msg = nullptr;
    int len = 0;
    libssh2_session_last_error(session, &msg, &len, 0);
    std::string detail = (msg && len > 0) ? std::string(msg, static_cast<size_t>(len)) : "unknown";
    return what + ": " + detail;
}

int TcpConnect(const std::string &host, int port, int timeoutSec) {
    struct addrinfo hints {};
    hints.ai_family = AF_UNSPEC;
    hints.ai_socktype = SOCK_STREAM;
    struct addrinfo *res = nullptr;
    std::string portStr = std::to_string(port);
    int gai = getaddrinfo(host.c_str(), portStr.c_str(), &hints, &res);
    if (gai != 0 || res == nullptr) {
        throw std::runtime_error("dns resolve failed for " + host);
    }
    int fd = -1;
    std::string lastError;
    for (struct addrinfo *ai = res; ai != nullptr; ai = ai->ai_next) {
        fd = ::socket(ai->ai_family, ai->ai_socktype, ai->ai_protocol);
        if (fd < 0) {
            continue;
        }
        int flags = ::fcntl(fd, F_GETFL, 0);
        ::fcntl(fd, F_SETFL, flags | O_NONBLOCK);
        int rc = ::connect(fd, ai->ai_addr, ai->ai_addrlen);
        if (rc == 0) {
            ::fcntl(fd, F_SETFL, flags);
            break;
        }
        if (errno != EINPROGRESS) {
            lastError = std::strerror(errno);
            ::close(fd);
            fd = -1;
            continue;
        }
        fd_set wset;
        FD_ZERO(&wset);
        FD_SET(fd, &wset);
        struct timeval tv {};
        tv.tv_sec = timeoutSec;
        rc = ::select(fd + 1, nullptr, &wset, nullptr, &tv);
        if (rc <= 0) {
            lastError = (rc == 0) ? "connect timeout" : std::strerror(errno);
            ::close(fd);
            fd = -1;
            continue;
        }
        int soError = 0;
        socklen_t len = sizeof(soError);
        if (::getsockopt(fd, SOL_SOCKET, SO_ERROR, &soError, &len) != 0 || soError != 0) {
            lastError = std::strerror(soError);
            ::close(fd);
            fd = -1;
            continue;
        }
        ::fcntl(fd, F_SETFL, flags);
        break;
    }
    freeaddrinfo(res);
    if (fd < 0) {
        throw std::runtime_error("tcp connect failed to " + host + ":" + portStr +
                                 (lastError.empty() ? "" : " (" + lastError + ")"));
    }
    int one = 1;
    ::setsockopt(fd, IPPROTO_TCP, TCP_NODELAY, &one, sizeof(one));
    return fd;
}

}  // namespace sftp_internal

std::shared_ptr<sftp_internal::SessionHandle> KRSftpSession::FindSession(
    const std::string &sessionId) {
    std::lock_guard<std::mutex> lock(gLock);
    auto it = gSessions.find(sessionId);
    if (it == gSessions.end()) {
        return nullptr;
    }
    return it->second;
}

std::string KRSftpSession::Connect(const KRAnyValue &params) {
    EnsureLibssh2();
    auto m = ParamsMap(params);
    // 字段名必须与 Kotlin SftpConnectParam.toJson() 一致：user / privateKey
    // （曾误读 username / privateKeyPath，会导致连接恒失败）。
    std::string host = ValueAsString(m, "host");
    int port = static_cast<int>(ValueAsInt64(m, "port", 22));
    std::string user = ValueAsString(m, "user");
    std::string password = ValueAsString(m, "password");
    std::string privateKey = ValueAsString(m, "privateKey");
    std::string passphrase = ValueAsString(m, "passphrase");
    long long connectTimeoutMs = ValueAsInt64(m, "connectTimeoutMs", kIoTimeoutSec * 1000);
    long long readTimeoutMs = ValueAsInt64(m, "readTimeoutMs", kIoTimeoutSec * 1000);
    int connectTimeoutSec = static_cast<int>(connectTimeoutMs > 0 ? connectTimeoutMs / 1000 : kIoTimeoutSec);
    if (connectTimeoutSec <= 0) {
        connectTimeoutSec = kIoTimeoutSec;
    }
    if (host.empty() || user.empty()) {
        throw std::runtime_error("sftp connect: host/user required");
    }

    auto handle = std::make_shared<sftp_internal::SessionHandle>();
    handle->sock = sftp_internal::TcpConnect(host, port, connectTimeoutSec);
    handle->session = libssh2_session_init();
    if (!handle->session) {
        ::close(handle->sock);
        throw std::runtime_error("libssh2_session_init failed");
    }
    libssh2_session_set_blocking(handle->session, 1);
    libssh2_session_set_timeout(handle->session, static_cast<long>(readTimeoutMs > 0 ? readTimeoutMs : kIoTimeoutSec * 1000));
    if (libssh2_session_handshake(handle->session, handle->sock) != 0) {
        std::string err = sftp_internal::Libssh2Error(handle->session, "ssh handshake failed");
        libssh2_session_free(handle->session);
        ::close(handle->sock);
        throw std::runtime_error(err);
    }
    int authRc = -1;
    if (!privateKey.empty()) {
        authRc = libssh2_userauth_publickey_fromfile(handle->session, user.c_str(),
                                                     nullptr, privateKey.c_str(),
                                                     password.empty() ? nullptr : password.c_str());
    } else {
        authRc = libssh2_userauth_password(handle->session, user.c_str(), password.c_str());
    }
    if (authRc != 0) {
        std::string err = sftp_internal::Libssh2Error(handle->session, "auth failed");
        libssh2_session_disconnect(handle->session, "auth failed");
        libssh2_session_free(handle->session);
        ::close(handle->sock);
        throw std::runtime_error(err);
    }
    handle->sftp = libssh2_sftp_init(handle->session);
    if (!handle->sftp) {
        std::string err = sftp_internal::Libssh2Error(handle->session, "sftp init failed");
        libssh2_session_disconnect(handle->session, "sftp init failed");
        libssh2_session_free(handle->session);
        ::close(handle->sock);
        throw std::runtime_error(err);
    }
    char homeBuf[1024];
    int homeLen = libssh2_sftp_realpath(handle->sftp, ".", homeBuf, sizeof(homeBuf) - 1);
    handle->home = (homeLen > 0) ? std::string(homeBuf, static_cast<size_t>(homeLen)) : "/";

    std::string sessionId;
    {
        std::lock_guard<std::mutex> lock(gLock);
        sessionId = "sftp-" + std::to_string(++gSessionIdCounter);
        gSessions[sessionId] = handle;
    }
    return sessionId;
}

void KRSftpSession::Disconnect(const std::string &sessionId) {
    std::shared_ptr<sftp_internal::SessionHandle> handle;
    {
        std::lock_guard<std::mutex> lock(gLock);
        auto it = gSessions.find(sessionId);
        if (it == gSessions.end()) {
            return;
        }
        handle = it->second;
        gSessions.erase(it);
    }
    std::lock_guard<std::recursive_mutex> io(handle->io);
    // 先标记 broken：并发中的 list/read 等会据此快速失败，而不是解引用已释放句柄。
    handle->broken = true;
    if (handle->sftp) {
        libssh2_sftp_shutdown(handle->sftp);
        handle->sftp = nullptr;
    }
    if (handle->session) {
        libssh2_session_disconnect(handle->session, "bye");
        libssh2_session_free(handle->session);
        handle->session = nullptr;
    }
    if (handle->sock >= 0) {
        ::close(handle->sock);
        handle->sock = -1;
    }
}

std::vector<KRRenderValueMap> KRSftpSession::List(const std::string &sessionId,
                                                  const std::string &remotePath) {
    std::unique_lock<std::recursive_mutex> lock;
    auto s = MethodGuard::Acquire(sessionId, lock);
    std::string dirPath = remotePath.empty() ? s->home : remotePath;
    LIBSSH2_SFTP_HANDLE *dir = libssh2_sftp_opendir(s->sftp, dirPath.c_str());
    if (!dir) {
        ThrowSftp(s, "opendir " + dirPath);
    }
    std::vector<KRRenderValueMap> entries;
    char name[512];
    LIBSSH2_SFTP_ATTRIBUTES attrs{};
    int rc;
    while ((rc = libssh2_sftp_readdir_ex(dir, name, sizeof(name), nullptr, 0, &attrs)) > 0) {
        std::string entryName(name);
        if (entryName == "." || entryName == "..") {
            continue;
        }
        entries.push_back(EntryMap(entryName, JoinRemote(dirPath, entryName), attrs, false));
    }
    libssh2_sftp_closedir(dir);
    if (rc < 0) {
        ThrowSftp(s, "readdir " + dirPath);
    }
    return entries;
}

KRRenderValueMap KRSftpSession::Stat(const std::string &sessionId,
                                     const std::string &remotePath,
                                     bool followSymlink) {
    std::unique_lock<std::recursive_mutex> lock;
    auto s = MethodGuard::Acquire(sessionId, lock);
    LIBSSH2_SFTP_ATTRIBUTES attrs{};
    if (!StatImpl(s, remotePath, followSymlink, &attrs)) {
        ThrowSftp(s, "stat " + remotePath);
    }
    auto entry = EntryMap(BaseName(remotePath), remotePath, attrs, followSymlink);
    if (followSymlink) {
        LIBSSH2_SFTP_ATTRIBUTES linkAttrs{};
        if (StatImpl(s, remotePath, false, &linkAttrs) &&
            (linkAttrs.flags & LIBSSH2_SFTP_ATTR_PERMISSIONS) &&
            S_ISLNK(linkAttrs.permissions)) {
            char target[1024];
            int len = libssh2_sftp_symlink_ex(s->sftp, remotePath.c_str(),
                                              static_cast<unsigned int>(remotePath.size()),
                                              target, sizeof(target) - 1, LIBSSH2_SFTP_READLINK);
            if (len > 0) {
                entry["isSymlink"] = KRRenderValue::Make(true);
                entry["symlinkTarget"] = KRRenderValue::Make(std::string(target, static_cast<size_t>(len)));
                entry["followsTarget"] = KRRenderValue::Make(true);
            }
        }
    }
    return entry;
}

float KRSftpSession::Download(const KRAnyValue &params) {
    auto m = ParamsMap(params);
    std::string sessionId = ValueAsString(m, "sessionId");
    std::string remotePath = ValueAsString(m, "remotePath");
    std::string localPath = ValueAsString(m, "localPath");
    long long offset = ValueAsInt64(m, "offset", 0);
    if (localPath.empty()) {
        throw std::runtime_error("sftp download: localPath required");
    }
    std::unique_lock<std::recursive_mutex> lock;
    auto s = MethodGuard::Acquire(sessionId, lock);
    LIBSSH2_SFTP_ATTRIBUTES attrs{};
    long long total = StatImpl(s, remotePath, true, &attrs)
                          ? static_cast<long long>(attrs.filesize)
                          : 0;
    // localPath 是宿主沙盒路径：只在本地建父目录，绝不能拿去远端 mkdir。
    EnsureLocalDir(ParentDir(localPath));
    auto result = CopyRemoteToLocal(s, remotePath, localPath, offset);
    if (total <= 0) {
        return 1.0f;
    }
    float progress = static_cast<float>(static_cast<double>(offset + result.bytes) / total);
    return std::min(1.0f, progress);
}

float KRSftpSession::Upload(const KRAnyValue &params) {
    auto m = ParamsMap(params);
    std::string sessionId = ValueAsString(m, "sessionId");
    std::string localPath = ValueAsString(m, "localPath");
    std::string remotePath = ValueAsString(m, "remotePath");
    long long offset = ValueAsInt64(m, "offset", 0);
    std::unique_lock<std::recursive_mutex> lock;
    auto s = MethodGuard::Acquire(sessionId, lock);
    long long total = 0;
    struct stat st {};
    if (::stat(localPath.c_str(), &st) == 0) {
        total = static_cast<long long>(st.st_size);
    }
    EnsureRemoteDir(s, ParentDir(remotePath));
    auto result = CopyLocalToRemote(s, localPath, remotePath, offset);
    if (total <= 0) {
        return 1.0f;
    }
    return std::min(1.0f, static_cast<float>(static_cast<double>(offset + result.bytes) / total));
}

void KRSftpSession::Mkdir(const KRAnyValue &params) {
    auto m = ParamsMap(params);
    std::string sessionId = ValueAsString(m, "sessionId");
    std::string remotePath = ValueAsString(m, "remotePath");
    bool recursive = ValueAsBool(m, "recursive", true);
    std::unique_lock<std::recursive_mutex> lock;
    auto s = MethodGuard::Acquire(sessionId, lock);
    if (recursive) {
        EnsureRemoteDir(s, remotePath);
        return;
    }
    if (libssh2_sftp_mkdir(s->sftp, remotePath.c_str(), 0755) != 0) {
        ThrowSftp(s, "mkdir " + remotePath);
    }
}

void KRSftpSession::Rm(const KRAnyValue &params) {
    auto m = ParamsMap(params);
    std::string sessionId = ValueAsString(m, "sessionId");
    std::string remotePath = ValueAsString(m, "remotePath");
    bool recursive = ValueAsBool(m, "recursive", false);
    std::unique_lock<std::recursive_mutex> lock;
    auto s = MethodGuard::Acquire(sessionId, lock);
    if (recursive) {
        RemoveRemoteRecursive(s, remotePath);
        return;
    }
    LIBSSH2_SFTP_ATTRIBUTES attrs{};
    if (!StatImpl(s, remotePath, false, &attrs)) {
        throw std::runtime_error("stat failed before remove: " + remotePath);
    }
    bool isDir = (attrs.flags & LIBSSH2_SFTP_ATTR_PERMISSIONS) && S_ISDIR(attrs.permissions);
    int rc = isDir ? libssh2_sftp_rmdir(s->sftp, remotePath.c_str())
                   : libssh2_sftp_unlink(s->sftp, remotePath.c_str());
    if (rc != 0) {
        ThrowSftp(s, "remove " + remotePath);
    }
}

void KRSftpSession::Rename(const KRAnyValue &params) {
    auto m = ParamsMap(params);
    std::string sessionId = ValueAsString(m, "sessionId");
    // Kotlin 侧 rename 传 oldPath/newPath，move 传 srcPath/destPath。
    std::string oldPath = ValueAsString(m, "oldPath");
    std::string newPath = ValueAsString(m, "newPath");
    if (oldPath.empty()) {
        oldPath = ValueAsString(m, "srcPath");
    }
    if (newPath.empty()) {
        newPath = ValueAsString(m, "destPath");
    }
    std::unique_lock<std::recursive_mutex> lock;
    auto s = MethodGuard::Acquire(sessionId, lock);
    if (libssh2_sftp_rename(s->sftp, oldPath.c_str(), newPath.c_str()) != 0) {
        ThrowSftp(s, "rename " + oldPath + " -> " + newPath);
    }
}

void KRSftpSession::Move(const KRAnyValue &params) {
    auto m = ParamsMap(params);
    std::string srcPath = ValueAsString(m, "srcPath");
    std::string destDir = ValueAsString(m, "destDir");
    if (srcPath.empty() || destDir.empty()) {
        throw std::runtime_error("sftp move: srcPath/destDir required");
    }
    // move = rename 到目标目录下的同名文件；跨设备由 libssh2 直接报错（与各端一致）。
    KRRenderValue::Map map;
    map["sessionId"] = KRRenderValue::Make(ValueAsString(m, "sessionId"));
    map["oldPath"] = KRRenderValue::Make(srcPath);
    map["newPath"] = KRRenderValue::Make(JoinRemote(destDir, BaseName(srcPath)));
    Rename(KRRenderValue::Make(map));
}

KRRenderValueMap KRSftpSession::Copy(const KRAnyValue &params) {
    auto m = ParamsMap(params);
    std::string sessionId = ValueAsString(m, "sessionId");
    std::string srcPath = ValueAsString(m, "srcPath");
    std::string destPath = ValueAsString(m, "destPath");
    std::unique_lock<std::recursive_mutex> lock;
    auto s = MethodGuard::Acquire(sessionId, lock);
    int fileCount = 0;
    int dirCount = 0;
    CopyRemoteRecursive(s, srcPath, destPath, &fileCount, &dirCount);
    KRRenderValueMap result;
    result["success"] = KRRenderValue::Make(true);
    result["copiedCount"] = KRRenderValue::Make(fileCount);
    result["failedCount"] = KRRenderValue::Make(0);
    result["errors"] = KRRenderValue::Make(KRRenderValue::Array{});
    return result;
}

void KRSftpSession::Chmod(const KRAnyValue &params) {
    auto m = ParamsMap(params);
    std::string sessionId = ValueAsString(m, "sessionId");
    std::string remotePath = ValueAsString(m, "remotePath");
    std::string mode = ValueAsString(m, "mode");
    unsigned long perm = ParseMode(mode);
    std::unique_lock<std::recursive_mutex> lock;
    auto s = MethodGuard::Acquire(sessionId, lock);
    LIBSSH2_SFTP_ATTRIBUTES attrs{};
    attrs.flags = LIBSSH2_SFTP_ATTR_PERMISSIONS;
    attrs.permissions = perm;
    if (libssh2_sftp_setstat(s->sftp, remotePath.c_str(), &attrs) != 0) {
        ThrowSftp(s, "chmod " + remotePath);
    }
}

void KRSftpSession::Chown(const KRAnyValue &params) {
    auto m = ParamsMap(params);
    std::string sessionId = ValueAsString(m, "sessionId");
    std::string remotePath = ValueAsString(m, "remotePath");
    int uid = static_cast<int>(ValueAsInt64(m, "uid", -1));
    int gid = static_cast<int>(ValueAsInt64(m, "gid", -1));
    std::unique_lock<std::recursive_mutex> lock;
    auto s = MethodGuard::Acquire(sessionId, lock);
    LIBSSH2_SFTP_ATTRIBUTES attrs{};
    attrs.flags = LIBSSH2_SFTP_ATTR_UIDGID;
    attrs.uid = static_cast<unsigned long>(uid);
    attrs.gid = static_cast<unsigned long>(gid);
    if (libssh2_sftp_setstat(s->sftp, remotePath.c_str(), &attrs) != 0) {
        ThrowSftp(s, "chown " + remotePath);
    }
}

void KRSftpSession::SetMtime(const KRAnyValue &params) {
    auto m = ParamsMap(params);
    std::string sessionId = ValueAsString(m, "sessionId");
    std::string remotePath = ValueAsString(m, "remotePath");
    long long mtime = ValueAsInt64(m, "mtime", 0);
    long long atime = ValueAsInt64(m, "atime", mtime);
    std::unique_lock<std::recursive_mutex> lock;
    auto s = MethodGuard::Acquire(sessionId, lock);
    LIBSSH2_SFTP_ATTRIBUTES attrs{};
    attrs.flags = LIBSSH2_SFTP_ATTR_ACMODTIME;
    attrs.mtime = static_cast<unsigned long>(mtime);
    attrs.atime = static_cast<unsigned long>(atime);
    if (libssh2_sftp_setstat(s->sftp, remotePath.c_str(), &attrs) != 0) {
        ThrowSftp(s, "setMtime " + remotePath);
    }
}

float KRSftpSession::BatchTask(const KRAnyValue &params) {
    // 契约与 Android（KRSftpClient.batchTask）严格对齐：
    // {sessionId, action: DELETE|MOVE|COPY|DOWNLOAD, items:[路径字符串], targetDir?, localDir?}
    // 单项失败不中断，收集后统一抛汇总错误；全部成功返回 1.0f。
    auto m = ParamsMap(params);
    std::string sessionId = ValueAsString(m, "sessionId");
    std::string action = ValueAsString(m, "action");
    if (action.empty()) {
        action = "DELETE";
    }
    std::transform(action.begin(), action.end(), action.begin(),
                   [](unsigned char c) { return static_cast<char>(std::toupper(c)); });
    std::string targetDir = ValueAsString(m, "targetDir");
    std::string localDir = ValueAsString(m, "localDir");

    auto itemsIt = m.find("items");
    if (itemsIt == m.end() || !itemsIt->second) {
        throw std::runtime_error("batchTask: items required");
    }
    auto items = itemsIt->second->toArray();
    if (items.empty()) {
        throw std::runtime_error("batchTask: items required");
    }

    if (action == "COPY" || action == "DOWNLOAD") {
        if (localDir.empty()) {
            throw std::runtime_error("batchTask: localDir required for " + action);
        }
    }
    if ((action == "COPY" || action == "MOVE") && targetDir.empty()) {
        throw std::runtime_error("batchTask: targetDir required for " + action);
    }

    std::vector<std::string> failures;
    int index = 0;
    for (const auto &item : items) {
        ++index;
        std::string path = item ? item->toString() : std::string();
        if (path.empty()) {
            continue;
        }
        try {
            std::string name = BaseName(path);
            if (action == "DELETE") {
                KRRenderValue::Map sub;
                sub["sessionId"] = KRRenderValue::Make(sessionId);
                sub["remotePath"] = KRRenderValue::Make(path);
                sub["recursive"] = KRRenderValue::Make(true);
                Rm(KRRenderValue::Make(sub));
            } else if (action == "MOVE") {
                // 与 Android batch 一致：先把目标目录建好，否则 rename 会失败。
                {
                    std::unique_lock<std::recursive_mutex> lock;
                    auto s = MethodGuard::Acquire(sessionId, lock);
                    EnsureRemoteDir(s, targetDir);
                }
                KRRenderValue::Map sub;
                sub["sessionId"] = KRRenderValue::Make(sessionId);
                sub["srcPath"] = KRRenderValue::Make(path);
                sub["destDir"] = KRRenderValue::Make(targetDir);
                Move(KRRenderValue::Make(sub));
            } else if (action == "COPY") {
                // 同上：目标目录不存在时，文件级 copy 的 open(write) 会失败。
                {
                    std::unique_lock<std::recursive_mutex> lock;
                    auto s = MethodGuard::Acquire(sessionId, lock);
                    EnsureRemoteDir(s, targetDir);
                }
                KRRenderValue::Map sub;
                sub["sessionId"] = KRRenderValue::Make(sessionId);
                sub["srcPath"] = KRRenderValue::Make(path);
                sub["destPath"] = KRRenderValue::Make(JoinRemote(targetDir, name));
                Copy(KRRenderValue::Make(sub));
            } else if (action == "DOWNLOAD") {
                KRRenderValue::Map sub;
                sub["sessionId"] = KRRenderValue::Make(sessionId);
                sub["remotePath"] = KRRenderValue::Make(path);
                sub["localPath"] = KRRenderValue::Make(JoinRemote(localDir, name));
                sub["offset"] = KRRenderValue::Make(static_cast<int64_t>(0));
                Download(KRRenderValue::Make(sub));
            } else {
                throw std::runtime_error("unsupported action: " + action);
            }
        } catch (const std::exception &e) {
            failures.push_back(path + ": " + e.what());
        }
    }
    if (!failures.empty()) {
        throw std::runtime_error("batch " + action + " failed " +
                                 std::to_string(failures.size()) + "/" +
                                 std::to_string(items.size()) + ": " + failures.front());
    }
    return 1.0f;
}

void KRSftpSession::CancelBatchTask(const std::string &taskId) {
    // 批量任务在调用线程内同步执行且不暴露中断点，取消为「尽力而为」的幂等空操作。
    // 注意：绝不能在这里 Disconnect——taskId 是调用方自造的批次标识，与会话无关，
    // 误当 sessionId 会断开正常连接。
    (void)taskId;
}

void KRSftpSession::ShutdownAll() {
    std::vector<std::string> ids;
    {
        std::lock_guard<std::mutex> lock(gLock);
        for (const auto &kv : gSessions) {
            ids.push_back(kv.first);
        }
    }
    for (const auto &id : ids) {
        Disconnect(id);
    }
}

}  // namespace module
}  // namespace kuikly
