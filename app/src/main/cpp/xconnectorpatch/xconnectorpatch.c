#include <jni.h>
#include <android/log.h>
#include "../logging_disabled.h"
#include <errno.h>
#include <stdbool.h>
#include <stdint.h>
#include <stdlib.h>
#include <string.h>
#include <sys/epoll.h>
#include <sys/eventfd.h>
#include <sys/poll.h>
#include <sys/socket.h>
#include <sys/types.h>
#include <sys/un.h>
#include <unistd.h>

#define LOG_TAG "XConnectorPatch"
#define LOGD(...) __android_log_print(ANDROID_LOG_DEBUG, LOG_TAG, __VA_ARGS__)
#define MAX_EVENTS 10
static void closeTrackedFd(int fd) {
    if (fd >= 0) close(fd);
}

static int waitForEpollEvents(int epollFd, struct epoll_event *events, int maxEvents) {
    while (true) {
        int numFds = epoll_wait(epollFd, events, maxEvents, -1);
        if (numFds >= 0) {
            return numFds;
        }
        if (errno == EINTR) {
            LOGD("epoll_wait interrupted for epollFd=%d, retrying", epollFd);
            continue;
        }
        LOGD("epoll_wait failed for epollFd=%d errno=%d (%s)", epollFd, errno, strerror(errno));
        return -1;
    }
}

JNIEXPORT jint JNICALL
Java_com_winlator_xconnector_XConnectorEpollNative_createAFUnixSocket(JNIEnv *env, jclass clazz, jstring path) {
    int fd = socket(AF_UNIX, SOCK_STREAM, 0);
    if (fd < 0) {
        LOGD("socket creation failed errno=%d (%s)", errno, strerror(errno));
        return -1;
    }

    struct sockaddr_un serverAddr;
    memset(&serverAddr, 0, sizeof(serverAddr));
    serverAddr.sun_family = AF_LOCAL;

    const char *pathPtr = (*env)->GetStringUTFChars(env, path, 0);
    if (pathPtr == NULL) { closeTrackedFd(fd); return -1; }
    size_t pathLength = strlen(pathPtr);
    if (pathLength >= sizeof(serverAddr.sun_path)) {
        (*env)->ReleaseStringUTFChars(env, path, pathPtr);
        closeTrackedFd(fd);
        errno = ENAMETOOLONG;
        return -1;
    }
    int addrLength = (int)(sizeof(sa_family_t) + pathLength);
    strncpy(serverAddr.sun_path, pathPtr, sizeof(serverAddr.sun_path) - 1);
    serverAddr.sun_path[sizeof(serverAddr.sun_path) - 1] = '\0';
    (*env)->ReleaseStringUTFChars(env, path, pathPtr);

    unlink(serverAddr.sun_path);
    if (bind(fd, (struct sockaddr *) &serverAddr, addrLength) < 0) {
        LOGD("bind failed for fd=%d errno=%d (%s)", fd, errno, strerror(errno));
        closeTrackedFd(fd);
        return -1;
    }
    if (listen(fd, MAX_EVENTS) < 0) {
        LOGD("listen failed for fd=%d errno=%d (%s)", fd, errno, strerror(errno));
        closeTrackedFd(fd);
        return -1;
    }

    return fd;
}

JNIEXPORT jint JNICALL
Java_com_winlator_xconnector_XConnectorEpollNative_createEpollFd(JNIEnv *env, jclass clazz) {
    int fd = epoll_create(MAX_EVENTS);
    if (fd < 0) {
        LOGD("epoll_create failed errno=%d (%s)", errno, strerror(errno));
        return -1;
    }
    return fd;
}

JNIEXPORT jboolean JNICALL
Java_com_winlator_xconnector_XConnectorEpollNative_addFdToEpoll(JNIEnv *env, jclass clazz, jint epollFd, jint fd) {
    struct epoll_event event;
    event.data.fd = fd;
    event.events = EPOLLIN | EPOLLRDHUP;
    if (epoll_ctl(epollFd, EPOLL_CTL_ADD, fd, &event) < 0) {
        LOGD("epoll_ctl ADD failed epollFd=%d fd=%d errno=%d (%s)", epollFd, fd, errno, strerror(errno));
        return JNI_FALSE;
    }
    return JNI_TRUE;
}

JNIEXPORT void JNICALL
Java_com_winlator_xconnector_XConnectorEpollNative_removeFdFromEpoll(JNIEnv *env, jclass clazz, jint epollFd, jint fd) {
    if (epoll_ctl(epollFd, EPOLL_CTL_DEL, fd, NULL) < 0 && errno != EBADF && errno != ENOENT) {
        LOGD("epoll_ctl DEL failed epollFd=%d fd=%d errno=%d (%s)", epollFd, fd, errno, strerror(errno));
    }
}

JNIEXPORT jint JNICALL
Java_com_winlator_xconnector_XConnectorEpollNative_createEventFd(JNIEnv *env, jclass clazz) {
    int fd = eventfd(0, EFD_NONBLOCK);
    if (fd < 0) {
        LOGD("eventfd creation failed errno=%d (%s)", errno, strerror(errno));
        return -1;
    }
    return fd;
}

JNIEXPORT void JNICALL
Java_com_winlator_xconnector_XConnectorEpollNative_closeFd(JNIEnv *env, jclass clazz, jint fd) {
    closeTrackedFd(fd);
}

typedef struct {
    jclass cls;                       /* global reference: the class the ids were resolved on */
    jmethodID handleNewConnection, handleExistingConnection, handleConnectionClosed, handleWritable;
} EpollCallbacks;

static EpollCallbacks *g_epollCallbacks = NULL;   /* published once, never freed (a handful of bytes per process) */

/*
 * The four callback ids of the connector class. They are looked up once and kept: the epoll loop calls in once per batch of
 * events, and resolving four methods by name each time was most of the cost of such a call. Returns NULL (with the Java
 * exception from the failed lookup pending, as before) when a callback cannot be resolved. [scratch] holds the result when it
 * is not the cached one (another class, or the first call racing another connector).
 */
static const EpollCallbacks *resolveEpollCallbacks(JNIEnv *env, jclass connectorClass, EpollCallbacks *scratch) {
    EpollCallbacks *cached = __atomic_load_n(&g_epollCallbacks, __ATOMIC_ACQUIRE);
    if (cached != NULL && (*env)->IsSameObject(env, cached->cls, connectorClass)) return cached;

    /* A failed lookup leaves a Java exception pending: no other JNI call may follow it, so each id is checked at once. */
    scratch->cls = NULL;
    scratch->handleNewConnection = (*env)->GetMethodID(env, connectorClass, "handleNewConnection", "(I)V");
    if (scratch->handleNewConnection == NULL) return NULL;
    scratch->handleExistingConnection = (*env)->GetMethodID(env, connectorClass, "handleExistingConnection", "(I)V");
    if (scratch->handleExistingConnection == NULL) return NULL;
    scratch->handleConnectionClosed = (*env)->GetMethodID(env, connectorClass, "handleConnectionClosed", "(I)V");
    if (scratch->handleConnectionClosed == NULL) return NULL;
    scratch->handleWritable = (*env)->GetMethodID(env, connectorClass, "handleWritable", "(I)V");
    if (scratch->handleWritable == NULL) return NULL;

    if (cached == NULL) {
        EpollCallbacks *fresh = (EpollCallbacks *) malloc(sizeof(EpollCallbacks));
        jclass global = fresh != NULL ? (*env)->NewGlobalRef(env, connectorClass) : NULL;
        if (fresh != NULL && global == NULL) {
            /* NewGlobalRef failed: if the VM left an OutOfMemoryError pending, hand it to the caller instead of waiting in epoll. */
            free(fresh);
            if ((*env)->ExceptionCheck(env)) return NULL;
            return scratch;
        }
        if (global != NULL) {
            *fresh = *scratch;
            fresh->cls = global;
            EpollCallbacks *expected = NULL;
            if (!__atomic_compare_exchange_n(&g_epollCallbacks, &expected, fresh, 0, __ATOMIC_RELEASE, __ATOMIC_ACQUIRE)) {
                (*env)->DeleteGlobalRef(env, global);   /* another connector published first */
                free(fresh);
            }
        } else {
            free(fresh);                                /* out of memory: simply keep resolving per call */
        }
    }
    return scratch;
}

JNIEXPORT jboolean JNICALL
Java_com_winlator_xconnector_XConnectorEpollNative_doEpollIndefinitely(JNIEnv *env, jclass clazz, jobject connector, jint epollFd, jint serverFd, jboolean addClientToEpoll) {
    jclass connectorClass = (*env)->GetObjectClass(env, connector);
    if (connectorClass == NULL) return JNI_FALSE;
    EpollCallbacks scratch;
    const EpollCallbacks *callbacks = resolveEpollCallbacks(env, connectorClass, &scratch);
    if (callbacks == NULL) {
        LOGD("failed to resolve XConnectorEpoll callbacks");
        return JNI_FALSE;
    }
    jmethodID handleNewConnection = callbacks->handleNewConnection;
    jmethodID handleExistingConnection = callbacks->handleExistingConnection;
    jmethodID handleConnectionClosed = callbacks->handleConnectionClosed;
    jmethodID handleWritable = callbacks->handleWritable;

    struct epoll_event events[MAX_EVENTS];
    int numFds = waitForEpollEvents(epollFd, events, MAX_EVENTS);
    if (numFds < 0) {
        return JNI_FALSE;
    }

    for (int i = 0; i < numFds; i++) {
        if (events[i].data.fd == serverFd) {
            int clientFd;
            do {
                clientFd = accept(serverFd, NULL, NULL);
            } while (clientFd < 0 && errno == EINTR);

            if (clientFd < 0) {
                if (errno != EAGAIN && errno != EWOULDBLOCK) {
                    LOGD("accept failed for serverFd=%d errno=%d (%s)", serverFd, errno, strerror(errno));
                }
                continue;
            }

            if (addClientToEpoll) {
                struct epoll_event event;
                event.data.fd = clientFd;
                event.events = EPOLLIN | EPOLLRDHUP;
                if (epoll_ctl(epollFd, EPOLL_CTL_ADD, clientFd, &event) < 0) {
                    LOGD("epoll_ctl ADD failed for clientFd=%d errno=%d (%s)", clientFd, errno, strerror(errno));
                    closeTrackedFd(clientFd);
                    continue;
                }
            }
            (*env)->CallVoidMethod(env, connector, handleNewConnection, clientFd);
            /* A Java exception (an Error the handlers do not catch) is pending: no further JNI call may be made with it. Stop
             * here and let it reach the Java loop, which shuts the connector down. */
            if ((*env)->ExceptionCheck(env)) return JNI_FALSE;
        } else if ((events[i].events & (EPOLLRDHUP | EPOLLHUP | EPOLLERR)) != 0) {
            (*env)->CallVoidMethod(env, connector, handleConnectionClosed, events[i].data.fd);
            if ((*env)->ExceptionCheck(env)) return JNI_FALSE;
        } else {
            if ((events[i].events & EPOLLIN) != 0) {
                (*env)->CallVoidMethod(env, connector, handleExistingConnection, events[i].data.fd);
                if ((*env)->ExceptionCheck(env)) return JNI_FALSE;
            }
            // Only clients with queued output ever ask for EPOLLOUT (non-blocking output mode).
            if ((events[i].events & EPOLLOUT) != 0) {
                (*env)->CallVoidMethod(env, connector, handleWritable, events[i].data.fd);
                if ((*env)->ExceptionCheck(env)) return JNI_FALSE;
            }
        }
    }

    return JNI_TRUE;
}

JNIEXPORT void JNICALL
Java_com_winlator_xconnector_XConnectorEpollNative_signalEventFd(JNIEnv *env, jclass clazz, jint fd) {
    uint64_t value = 1;
    ssize_t result;
    do { result = write(fd, &value, sizeof(value)); } while (result < 0 && errno == EINTR);
}

JNIEXPORT void JNICALL
Java_com_winlator_xconnector_XConnectorEpollNative_drainEventFd(JNIEnv *env, jclass clazz, jint fd) {
    uint64_t value;
    ssize_t result;
    do { result = read(fd, &value, sizeof(value)); } while (result < 0 && errno == EINTR);
}

// Retry only interrupted syscalls. A positive short write has consumed the FD
// and is completed by Java with plain writes using the updated byte offset.
static ssize_t sendSocketBytes(int fd, const void* bytes, size_t length, int ancillaryFd, int extraFlags) {
    ssize_t result;
    do {
        if (ancillaryFd < 0) {
            result = send(fd, bytes, length, MSG_NOSIGNAL | extraFlags);
        } else {
            struct iovec iov = { .iov_base = (void*)bytes, .iov_len = length };
            union { struct cmsghdr alignment; char bytes[CMSG_SPACE(sizeof(int))]; } control = {0};
            struct msghdr msg = { .msg_iov = &iov, .msg_iovlen = 1,
                .msg_control = control.bytes, .msg_controllen = sizeof(control) };
            struct cmsghdr* header = CMSG_FIRSTHDR(&msg);
            header->cmsg_level = SOL_SOCKET;
            header->cmsg_type = SCM_RIGHTS;
            header->cmsg_len = CMSG_LEN(sizeof(int));
            memcpy(CMSG_DATA(header), &ancillaryFd, sizeof(ancillaryFd));
            result = sendmsg(fd, &msg, MSG_NOSIGNAL | extraFlags);
        }
    } while (result < 0 && errno == EINTR);
    return result;
}

JNIEXPORT jint JNICALL
Java_com_winlator_xconnector_XConnectorEpollNative_sendSocket(JNIEnv* env, jclass clazz,
        jint fd, jobject data, jint offset, jint length, jint ancillaryFd) {
    char* bytes = (*env)->GetDirectBufferAddress(env, data);
    jlong capacity = (*env)->GetDirectBufferCapacity(env, data);
    if (!bytes || offset < 0 || length < 0 || offset > capacity || length > capacity - offset) {
        jclass error = (*env)->FindClass(env, "java/io/IOException");
        if (error) (*env)->ThrowNew(env, error, "Invalid direct socket buffer range");
        return -1;
    }
    ssize_t result = sendSocketBytes(fd, bytes + offset, (size_t)length, ancillaryFd, 0);
    if (result < 0) {
        int code = errno;
        jclass error = (*env)->FindClass(env, "java/io/IOException");
        if (error) (*env)->ThrowNew(env, error, strerror(code));
    }
    return (jint)result;
}


// Like sendSocket but never blocks: returns the bytes the socket accepted (possibly fewer than asked, possibly 0 for an empty
// request), or -2 when its buffer is full and nothing was consumed (a pending descriptor is then not transferred either).
JNIEXPORT jint JNICALL
Java_com_winlator_xconnector_XConnectorEpollNative_sendSocketNoWait(JNIEnv* env, jclass clazz,
        jint fd, jobject data, jint offset, jint length, jint ancillaryFd) {
    char* bytes = (*env)->GetDirectBufferAddress(env, data);
    jlong capacity = (*env)->GetDirectBufferCapacity(env, data);
    if (!bytes || offset < 0 || length < 0 || offset > capacity || length > capacity - offset) {
        jclass error = (*env)->FindClass(env, "java/io/IOException");
        if (error) (*env)->ThrowNew(env, error, "Invalid direct socket buffer range");
        return -1;
    }
    ssize_t result = sendSocketBytes(fd, bytes + offset, (size_t)length, ancillaryFd, MSG_DONTWAIT);
    if (result < 0) {
        if (errno == EAGAIN || errno == EWOULDBLOCK) return -2;
        int code = errno;
        jclass error = (*env)->FindClass(env, "java/io/IOException");
        if (error) (*env)->ThrowNew(env, error, strerror(code));
        return -1;
    }
    return (jint)result;
}

JNIEXPORT void JNICALL
Java_com_winlator_xconnector_XConnectorEpollNative_setFdEvents(JNIEnv *env, jclass clazz, jint epollFd, jint fd, jboolean readable, jboolean writable) {
    struct epoll_event event = { .data.fd = fd, .events = EPOLLRDHUP | (readable ? EPOLLIN : 0) | (writable ? EPOLLOUT : 0) };
    epoll_ctl(epollFd, EPOLL_CTL_MOD, fd, &event);
}

static void closeReceivedRights(struct msghdr* msg, unsigned int alreadyTransferred) {
    unsigned int index = 0;
    for (struct cmsghdr* header = CMSG_FIRSTHDR(msg); header; header = CMSG_NXTHDR(msg, header)) {
        if (header->cmsg_level != SOL_SOCKET || header->cmsg_type != SCM_RIGHTS || header->cmsg_len < CMSG_LEN(0)) continue;
        size_t count = (header->cmsg_len - CMSG_LEN(0)) / sizeof(int);
        int* fds = (int*)CMSG_DATA(header);
        for (size_t i = 0; i < count; i++, index++) {
            if (index >= alreadyTransferred) closeTrackedFd(fds[i]);
        }
    }
}

JNIEXPORT jint JNICALL
Java_com_winlator_xconnector_XConnectorEpollNative_receiveSocket(JNIEnv* env, jclass clazz,
        jint fd, jobject data, jint offset, jint length, jobject ancillaryReceiver) {
    char* bytes = (*env)->GetDirectBufferAddress(env, data);
    jlong capacity = (*env)->GetDirectBufferCapacity(env, data);
    if (!bytes || offset < 0 || length <= 0 || offset > capacity || length > capacity - offset) {
        jclass error = (*env)->FindClass(env, "java/io/IOException");
        if (error) (*env)->ThrowNew(env, error, "Invalid direct socket buffer range");
        return -1;
    }
    ssize_t result;
    if (!ancillaryReceiver) {
        do { result = read(fd, bytes + offset, (size_t)length); } while (result < 0 && errno == EINTR);
    } else {
        struct iovec iov = { .iov_base = bytes + offset, .iov_len = (size_t)length };
        union { struct cmsghdr alignment; char bytes[CMSG_SPACE(32 * sizeof(int))]; } control = {0};
        struct msghdr msg = { .msg_iov = &iov, .msg_iovlen = 1, .msg_control = control.bytes };
        do {
            msg.msg_controllen = sizeof(control);
            msg.msg_flags = 0;
            result = recvmsg(fd, &msg, MSG_CMSG_CLOEXEC);
        } while (result < 0 && errno == EINTR);
        if (result >= 0) {
            if (msg.msg_flags & MSG_CTRUNC) {
                closeReceivedRights(&msg, 0);
                jclass error = (*env)->FindClass(env, "java/io/IOException");
                if (error) (*env)->ThrowNew(env, error, "Truncated socket descriptor message");
                return -1;
            }
            jclass receiverClass = NULL;
            jmethodID addFd = NULL;
            unsigned int transferred = 0;
            for (struct cmsghdr* header = CMSG_FIRSTHDR(&msg); header; header = CMSG_NXTHDR(&msg, header)) {
                if (header->cmsg_level != SOL_SOCKET || header->cmsg_type != SCM_RIGHTS || header->cmsg_len < CMSG_LEN(0)) continue;
                size_t count = (header->cmsg_len - CMSG_LEN(0)) / sizeof(int);
                if (!count) continue;
                if (!addFd) {
                    receiverClass = (*env)->GetObjectClass(env, ancillaryReceiver);
                    if (receiverClass) addFd = (*env)->GetMethodID(env, receiverClass, "addAncillaryFd", "(I)V");
                    if (!addFd) {
                        closeReceivedRights(&msg, 0);
                        if (receiverClass) (*env)->DeleteLocalRef(env, receiverClass);
                        return -1;
                    }
                }
                int* fds = (int*)CMSG_DATA(header);
                for (size_t i = 0; i < count; i++) {
                    (*env)->CallVoidMethod(env, ancillaryReceiver, addFd, fds[i]);
                    if ((*env)->ExceptionCheck(env)) {
                        closeReceivedRights(&msg, transferred);
                        (*env)->DeleteLocalRef(env, receiverClass);
                        return -1;
                    }
                    transferred++;
                }
            }
            if (receiverClass) (*env)->DeleteLocalRef(env, receiverClass);
        }
    }
    if (result < 0) {
        int code = errno;
        jclass error = (*env)->FindClass(env, "java/io/IOException");
        if (error) (*env)->ThrowNew(env, error, strerror(code));
    }
    return (jint)result;
}
