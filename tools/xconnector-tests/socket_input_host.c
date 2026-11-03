#include <jni.h>
#include <sys/socket.h>
#include <unistd.h>
#include <errno.h>
#include <stdlib.h>
#include <string.h>
#include <fcntl.h>
#include <dirent.h>
static int interrupted_reads, forced_error, read_calls, recv_calls;
ssize_t test_read(int fd,void*bytes,size_t length){read_calls++;if(interrupted_reads){interrupted_reads--;errno=EINTR;return -1;}if(forced_error){errno=forced_error;forced_error=0;return -1;}return read(fd,bytes,length);}
ssize_t test_recvmsg(int fd,struct msghdr*msg,int flags){recv_calls++;if(interrupted_reads){interrupted_reads--;errno=EINTR;return -1;}if(forced_error){errno=forced_error;forced_error=0;return -1;}return recvmsg(fd,msg,flags);}
JNIEXPORT jintArray JNICALL Java_com_winlator_xconnector_SocketInputIntegration_socketPair(JNIEnv*e,jclass c){int fd[2];if(socketpair(AF_UNIX,SOCK_STREAM,0,fd))abort();jintArray r=(*e)->NewIntArray(e,2);(*e)->SetIntArrayRegion(e,r,0,2,fd);return r;}
JNIEXPORT void JNICALL Java_com_winlator_xconnector_SocketInputIntegration_close(JNIEnv*e,jclass c,jint fd){close(fd);}
JNIEXPORT void JNICALL Java_com_winlator_xconnector_SocketInputIntegration_interruptReads(JNIEnv*e,jclass c,jint n){interrupted_reads=n;read_calls=recv_calls=0;}
JNIEXPORT void JNICALL Java_com_winlator_xconnector_SocketInputIntegration_failRead(JNIEnv*e,jclass c,jint code){forced_error=code;}
JNIEXPORT jint JNICALL Java_com_winlator_xconnector_SocketInputIntegration_calls(JNIEnv*e,jclass c,jboolean ancillary){return ancillary?recv_calls:read_calls;}
JNIEXPORT void JNICALL Java_com_winlator_xconnector_SocketInputIntegration_send(JNIEnv*e,jclass c,jint fd,jint count){
 if(count<0||count>64)abort(); char bytes[]={1,2,3,4};struct iovec iov={bytes,sizeof(bytes)};char control[CMSG_SPACE(sizeof(int)*64)]={0};struct msghdr msg={.msg_iov=&iov,.msg_iovlen=1};int fds[64];if(count){msg.msg_control=control;msg.msg_controllen=CMSG_SPACE(sizeof(int)*count);struct cmsghdr*h=CMSG_FIRSTHDR(&msg);h->cmsg_level=SOL_SOCKET;h->cmsg_type=SCM_RIGHTS;h->cmsg_len=CMSG_LEN(sizeof(int)*count);for(int i=0;i<count;i++){fds[i]=open("/dev/null",O_RDONLY);if(fds[i]<0)abort();}memcpy(CMSG_DATA(h),fds,sizeof(int)*count);}if(sendmsg(fd,&msg,MSG_NOSIGNAL)!=sizeof(bytes))abort();for(int i=0;i<count;i++)close(fds[i]);
}
JNIEXPORT jboolean JNICALL Java_com_winlator_xconnector_SocketInputIntegration_isCloseOnExec(JNIEnv*e,jclass c,jint fd){return (fcntl(fd,F_GETFD)&FD_CLOEXEC)!=0;}
JNIEXPORT jint JNICALL Java_com_winlator_xconnector_SocketInputIntegration_fdCount(JNIEnv*e,jclass c){DIR*d=opendir("/proc/self/fd");int n=0;struct dirent*entry;while((entry=readdir(d)))if(entry->d_name[0]!='.')n++;closedir(d);return n;}
