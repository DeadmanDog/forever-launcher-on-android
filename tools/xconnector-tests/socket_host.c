#include <jni.h>
#include <sys/socket.h>
#include <errno.h>
#include <unistd.h>
#include <stdlib.h>
#include <string.h>
static int interrupt_next=0,last_fds=0;
ssize_t short_send(int fd,const void*bytes,size_t n,int flags){
 if(interrupt_next){interrupt_next=0;errno=EINTR;return -1;}
 return send(fd,bytes,n>3?3:n,flags);
}
ssize_t short_sendmsg(int fd,const struct msghdr*msg,int flags){
 if(interrupt_next){interrupt_next=0;errno=EINTR;return -1;}
 struct msghdr copy=*msg;struct iovec iov=*msg->msg_iov;
 if(iov.iov_len>3)iov.iov_len=3;copy.msg_iov=&iov;
 return sendmsg(fd,&copy,flags);
}
JNIEXPORT jint JNICALL Java_com_winlator_xconnector_ClientSocket_write(JNIEnv*env,jobject obj,jint fd,jobject data,jint len){return short_send(fd,(*env)->GetDirectBufferAddress(env,data),len,MSG_NOSIGNAL);}
JNIEXPORT jintArray JNICALL Java_com_winlator_xconnector_SocketOutputIntegration_socketPair(JNIEnv*env,jclass cls){int fd[2];if(socketpair(AF_UNIX,SOCK_STREAM,0,fd))abort();jintArray a=(*env)->NewIntArray(env,2);(*env)->SetIntArrayRegion(env,a,0,2,fd);return a;}
JNIEXPORT void JNICALL Java_com_winlator_xconnector_SocketOutputIntegration_close(JNIEnv*e,jclass c,jint fd){close(fd);}
JNIEXPORT void JNICALL Java_com_winlator_xconnector_SocketOutputIntegration_interruptNext(JNIEnv*e,jclass c){interrupt_next=1;}
JNIEXPORT jint JNICALL Java_com_winlator_xconnector_SocketOutputIntegration_fdCount(JNIEnv*e,jclass c){return last_fds;}
JNIEXPORT jbyteArray JNICALL Java_com_winlator_xconnector_SocketOutputIntegration_receive(JNIEnv*env,jclass cls,jint fd,jint len){
 char*bytes=calloc(1,len);int done=0;last_fds=0;
 while(done<len){
  union{struct cmsghdr align;char bytes[CMSG_SPACE(sizeof(int)*16)];}control;
  struct iovec iov={bytes+done,(size_t)(len-done)};struct msghdr msg={.msg_iov=&iov,.msg_iovlen=1,.msg_control=control.bytes,.msg_controllen=sizeof(control)};
  ssize_t n=recvmsg(fd,&msg,0);if(n<0&&errno==EINTR)continue;if(n<=0)abort();
  for(struct cmsghdr*h=CMSG_FIRSTHDR(&msg);h;h=CMSG_NXTHDR(&msg,h))if(h->cmsg_level==SOL_SOCKET&&h->cmsg_type==SCM_RIGHTS){
   int count=(h->cmsg_len-CMSG_LEN(0))/sizeof(int);int*fds=(int*)CMSG_DATA(h);for(int i=0;i<count;i++){last_fds++;close(fds[i]);}
  }
  done+=n;
 }
 jbyteArray result=(*env)->NewByteArray(env,len);(*env)->SetByteArrayRegion(env,result,0,len,(jbyte*)bytes);free(bytes);return result;
}
