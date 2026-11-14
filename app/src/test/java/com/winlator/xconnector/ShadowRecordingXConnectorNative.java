package com.winlator.xconnector;

import org.robolectric.annotation.Implementation;
import org.robolectric.annotation.Implements;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * JVM tests: XConnectorEpollNative loads libxconnectorpatch, which does not exist on the host. Under Robolectric this shadow
 * replaces the library with fakes that record what would have reached the kernel (signalled eventfds, closed fds, socket writes).
 */
@Implements(className = "com.winlator.xconnector.XConnectorEpollNative")
public class ShadowRecordingXConnectorNative {
    public static final List<Integer> signalled = Collections.synchronizedList(new ArrayList<>());
    public static final List<Integer> closed = Collections.synchronizedList(new ArrayList<>());
    public static final List<Integer> writesToFd = Collections.synchronizedList(new ArrayList<>());

    public static void reset() {
        signalled.clear();
        closed.clear();
        writesToFd.clear();
    }

    @Implementation protected static void __staticInitializer__() { }
    @Implementation protected static boolean addFdToEpoll(int epollFd, int fd) { return true; }
    @Implementation protected static int createAFUnixSocket(String path) { return 900; }
    @Implementation protected static int createEpollFd() { return 901; }
    @Implementation protected static int createEventFd() { return 902; }
    @Implementation protected static void removeFdFromEpoll(int epollFd, int fd) { }
    @Implementation protected static void signalEventFd(int fd) { signalled.add(fd); }
    @Implementation protected static void drainEventFd(int fd) { }
    @Implementation protected static void setFdEvents(int epollFd, int fd, boolean readable, boolean writable) { }
    @Implementation protected static void closeFd(int fd) { closed.add(fd); }

    @Implementation
    protected static int sendSocket(int fd, ByteBuffer data, int offset, int length, int ancillaryFd) {
        writesToFd.add(fd);
        return length;
    }

    @Implementation
    protected static int sendSocketNoWait(int fd, ByteBuffer data, int offset, int length, int ancillaryFd) {
        writesToFd.add(fd);
        return length;
    }
}
