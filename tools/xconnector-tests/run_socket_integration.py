#!/usr/bin/env python3
from pathlib import Path
import subprocess, tempfile, os, sys
r=Path(__file__).resolve().parents[2];t=Path(__file__).resolve().parent
java=Path(os.environ['JAVA_HOME'])
baseline=Path(sys.argv[1]) if len(sys.argv)>1 else None
with tempfile.TemporaryDirectory(prefix='wow-socket-test-') as folder:
 f=Path(folder);(f/'include/android').mkdir(parents=True);(f/'classes').mkdir();(f/'baseline').mkdir()
 (f/'include/android/log.h').write_text('#define ANDROID_LOG_DEBUG 3\nstatic inline int __android_log_print(int p,const char*t,const char*f,...){return 0;}\n')
 (f/'Keep.java').write_text('package androidx.annotation; public @interface Keep {}')
 (f/'XServer.java').write_text('package com.winlator.xserver; public final class XServer {public static final java.nio.charset.Charset LATIN1_CHARSET=java.nio.charset.StandardCharsets.ISO_8859_1;}')
 (f/'XConnectorEpoll.java').write_text('package com.winlator.xconnector; public final class XConnectorEpoll {}')
 includes=['-I'+str(java/'include'),'-I'+str(java/'include/linux'),'-I'+str(f/'include')]
 subprocess.run(['gcc','-std=c11','-O2','-fPIC','-shared',*includes,str(t/'socket_host.c'),'-o',str(f/'libwinlator.so')],check=True)
 subprocess.run(['gcc','-std=c11','-O2','-fPIC','-shared',*includes,'-Dsend=short_send','-Dsendmsg=short_sendmsg',str(r/'app/src/main/cpp/xconnectorpatch/xconnectorpatch.c'),'-L'+str(f),'-lwinlator','-o',str(f/'libxconnectorpatch.so')],check=True)
 p=r/'app/src/main/java/com/winlator/xconnector'
 common=[f/'Keep.java',f/'XServer.java',f/'XConnectorEpoll.java',p/'XStreamLock.java',t/'SocketOutputIntegration.java']
 current=[p/(x+'.java') for x in ['ClientSocket','SocketWritePump','XOutputStream','XConnectorEpollNative']]
 subprocess.run([str(java/'bin/javac'),'-d',str(f/'classes'),*map(str,common+current)],check=True)
 env=dict(os.environ,LD_LIBRARY_PATH=str(f))
 if baseline:
  old=[baseline/'app/src/main/java/com/winlator/xconnector'/x for x in ['ClientSocket.java','XOutputStream.java']]
  subprocess.run([str(java/'bin/javac'),'-d',str(f/'baseline'),*map(str,common+old)],check=True)
  subprocess.run([str(java/'bin/java'),'-Djava.library.path='+str(f),'-cp',str(f/'baseline'),'com.winlator.xconnector.SocketOutputIntegration','baseline'],check=True,env=env,timeout=10)
 subprocess.run([str(java/'bin/java'),'-Djava.library.path='+str(f),'-cp',str(f/'classes'),'com.winlator.xconnector.SocketOutputIntegration'],check=True,env=env,timeout=15)
