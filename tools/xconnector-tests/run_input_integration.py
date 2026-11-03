#!/usr/bin/env python3
from pathlib import Path
import os,sys,subprocess,tempfile,re
root=Path(__file__).resolve().parents[2];tests=Path(__file__).resolve().parent;java=Path(os.environ['JAVA_HOME']);baseline=Path(sys.argv[1]);mode=sys.argv[2]
with tempfile.TemporaryDirectory(prefix='wow-input-jni-') as name:
 f=Path(name);(f/'include/android').mkdir(parents=True);(f/'classes').mkdir();(f/'include/android/log.h').write_text('#define ANDROID_LOG_DEBUG 3\nstatic inline int __android_log_print(int p,const char*t,const char*f,...){return 0;}\n');(f/'Keep.java').write_text('package androidx.annotation;public @interface Keep{}');(f/'XConnectorEpoll.java').write_text('package com.winlator.xconnector;public final class XConnectorEpoll{}')
 source=(root/'docs/legacy-native-src/xconnector_epoll.c').read_text()
 def body(name):
  start=source.index('JNIEXPORT jint JNICALL\nJava_com_winlator_xconnector_ClientSocket_'+name+'(');end=source.index('\n}\n',start)+3;return source[start:end]
 (f/'original-read.c').write_text('#include <jni.h>\n#include <sys/socket.h>\n#include <unistd.h>\n#define MAX_FDS 32\n#define printf(...) ((void)0)\nstatic void trackFd(int fd){}\n'+body('read')+'\n'+body('recvAncillaryMsg'))
 includes=['-I'+str(java/'include'),'-I'+str(java/'include/linux'),'-I'+str(f/'include')]
 subprocess.run(['gcc','-std=c11','-O2','-U_FORTIFY_SOURCE','-D_FORTIFY_SOURCE=0','-fPIC',*includes,'-Dread=test_read','-Drecvmsg=test_recvmsg','-c',str(f/'original-read.c'),'-o',str(f/'original.o')],check=True)
 subprocess.run(['gcc','-std=c11','-O2','-U_FORTIFY_SOURCE','-D_FORTIFY_SOURCE=0','-fPIC','-shared',*includes,str(tests/'socket_input_host.c'),str(f/'original.o'),'-o',str(f/'libwinlator.so')],check=True)
 subprocess.run(['gcc','-std=c11','-O2','-U_FORTIFY_SOURCE','-D_FORTIFY_SOURCE=0','-fPIC','-shared',*includes,'-Dread=test_read','-Drecvmsg=test_recvmsg',str(root/'app/src/main/cpp/xconnectorpatch/xconnectorpatch.c'),'-L'+str(f),'-lwinlator','-o',str(f/'libxconnectorpatch.so')],check=True)
 current=root/'app/src/main/java/com/winlator/xconnector';selected=baseline/'app/src/main/java/com/winlator/xconnector' if mode=='baseline' else current
 harness=(tests/'SocketInputIntegration.java').read_text()
 if mode=='baseline':harness=harness[:harness.index('  for(boolean withFds:')]+' }\n}\n'
 (f/'SocketInputIntegration.java').write_text(harness)
 files=[f/'Keep.java',f/'XConnectorEpoll.java',f/'SocketInputIntegration.java',selected/'ClientSocket.java',current/'SocketWritePump.java',current/'XConnectorEpollNative.java']
 subprocess.run([str(java/'bin/javac'),'-d',str(f/'classes'),*map(str,files)],check=True)
 env=dict(os.environ,LD_LIBRARY_PATH=str(f));results=[]
 for kind in ['plain','ancillary']:
  cmd=[str(java/'bin/java'),'-Djava.library.path='+str(f),'-cp',str(f/'classes'),'com.winlator.xconnector.SocketInputIntegration',mode,kind]
  result=subprocess.run(cmd,env=env,timeout=20);results.append(result.returncode)
 assert results==([2,2] if mode=='baseline' else [0,0]),results
 print(mode,results)
