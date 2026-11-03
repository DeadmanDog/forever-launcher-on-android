#include <stdio.h>
#include <stdlib.h>
#include <sys/mman.h>
#include <sys/socket.h>
#include <sys/un.h>
#include <unistd.h>
#include <string.h>
#include <fcntl.h>
#include <stdbool.h>
#include <pthread.h>
#include <sys/ipc.h>
#include <sys/syscall.h>
#include <jni.h>
#include <android/log.h>
#include "../logging_disabled.h"

#define __u32 uint32_t
#include <linux/ashmem.h>
#include <sys/stat.h>
#include <errno.h>
#include <android/sharedmem.h>

#define printf(...) __android_log_print(ANDROID_LOG_DEBUG, "System.out", __VA_ARGS__);

static int ashmemCreateRegion(const char *name, int64_t size) {
    return ASharedMemory_create(name, size);
}

JNIEXPORT jint JNICALL
Java_com_winlator_sysvshm_SysVSharedMemory_ashmemCreateRegion(JNIEnv *env, jobject obj, jint index,
                                                              jlong size) {
    char name[32];
    sprintf(name, "sysvshm-%d", index);
    return ashmemCreateRegion(name, size);
}

JNIEXPORT jobject JNICALL
Java_com_winlator_sysvshm_SysVSharedMemory_mapSHMSegment(JNIEnv *env, jobject obj, jint fd, jlong size, jint offset, jboolean readonly) {
    // A direct ByteBuffer cannot hold more than Integer.MAX_VALUE bytes; refuse before mapping anything.
    if (size <= 0 || size > 0x7fffffffLL || offset < 0) return NULL;
    char *data = mmap(NULL, size, readonly ? PROT_READ : PROT_WRITE | PROT_READ, MAP_SHARED, fd, offset);
    if (data == MAP_FAILED) return NULL;
    jobject buffer = (*env)->NewDirectByteBuffer(env, data, size);
    if (!buffer) {
        // The wrapper could not be created (out of memory; the exception stays pending for the caller): nothing owns
        // the mapping, so it must be released here.
        munmap(data, size);
        return NULL;
    }
    return buffer;
}

JNIEXPORT void JNICALL
Java_com_winlator_sysvshm_SysVSharedMemory_unmapSHMSegment(JNIEnv *env, jobject obj, jobject data,
                                                           jlong size) {
    char *dataAddr = (*env)->GetDirectBufferAddress(env, data);
    munmap(dataAddr, size);
}
