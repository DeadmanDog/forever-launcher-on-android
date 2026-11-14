#!/usr/bin/env python3
"""Run the production doEpollIndefinitely in a real JVM (-Xcheck:jni): a callback that throws an uncaught Error ends the batch.

    python3 -B tools/xconnector-tests/run_exception_stop.py [xconnectorpatch.c]
"""
from pathlib import Path
import subprocess, tempfile, os, sys
r = Path(__file__).resolve().parents[2]; t = Path(__file__).resolve().parent
java = Path(os.environ['JAVA_HOME'])
with tempfile.TemporaryDirectory(prefix='wow-exception-stop-') as folder:
    f = Path(folder); (f / 'include/android').mkdir(parents=True); (f / 'classes').mkdir()
    (f / 'include/android/log.h').write_text('#define ANDROID_LOG_DEBUG 3\nstatic inline int __android_log_print(int p,const char*t,const char*f,...){return 0;}\n')
    (f / 'Keep.java').write_text('package androidx.annotation; public @interface Keep {}')
    (f / 'XConnectorEpoll.java').write_text('package com.winlator.xconnector; public class XConnectorEpoll {'
        'void handleNewConnection(int fd){} void handleExistingConnection(int fd){} void handleConnectionClosed(int fd){} void handleWritable(int fd){}}')
    (f / 'ClientSocket.java').write_text('package com.winlator.xconnector; public final class ClientSocket {}')
    includes = ['-I' + str(java / 'include'), '-I' + str(java / 'include/linux'), '-I' + str(f / 'include')]
    source = Path(sys.argv[1]) if len(sys.argv) > 1 else r / 'app/src/main/cpp/xconnectorpatch/xconnectorpatch.c'
    subprocess.run(['gcc', '-std=gnu11', '-O2', '-fPIC', '-shared', '-Wno-unused-function', *includes, str(source), '-o', str(f / 'libxconnectorpatch.so')], check=True)
    native = r / 'app/src/main/java/com/winlator/xconnector/XConnectorEpollNative.java'
    subprocess.run([str(java / 'bin/javac'), '-d', str(f / 'classes'), str(f / 'Keep.java'), str(f / 'XConnectorEpoll.java'), str(f / 'ClientSocket.java'), str(native), str(t / 'ExceptionStopIntegration.java')], check=True)
    subprocess.run([str(java / 'bin/java'), '-Xcheck:jni', '-Djava.library.path=' + str(f), '-cp', str(f / 'classes'), 'com.winlator.xconnector.ExceptionStopIntegration'],
                   check=True, env=dict(os.environ, LD_LIBRARY_PATH=str(f)), timeout=60)
