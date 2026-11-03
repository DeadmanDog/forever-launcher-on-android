package com.winlator.core;

import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

/** A one-shot worker releases its thread after the queued operation, including failures. */
public final class OneShotTasks {
    public static <T> Future<T> submit(Callable<T> task) { return submit(Executors.newSingleThreadExecutor(), task); }
    public static <T> Future<T> submit(ExecutorService executor, Callable<T> task) {
        try { return executor.submit(task); }
        finally { executor.shutdown(); }
    }
    public static void execute(Runnable task) {
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try { executor.execute(task); } finally { executor.shutdown(); }
    }
}
