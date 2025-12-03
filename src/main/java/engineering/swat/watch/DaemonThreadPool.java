/*
 * BSD 2-Clause License
 *
 * Copyright (c) 2023, Swat.engineering
 *
 * Redistribution and use in source and binary forms, with or without
 * modification, are permitted provided that the following conditions are met:
 *
 * 1. Redistributions of source code must retain the above copyright notice, this
 *    list of conditions and the following disclaimer.
 *
 * 2. Redistributions in binary form must reproduce the above copyright notice,
 *    this list of conditions and the following disclaimer in the documentation
 *    and/or other materials provided with the distribution.
 *
 * THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS"
 * AND ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE
 * IMPLIED WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE ARE
 * DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT HOLDER OR CONTRIBUTORS BE LIABLE
 * FOR ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR CONSEQUENTIAL
 * DAMAGES (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR
 * SERVICES; LOSS OF USE, DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER
 * CAUSED AND ON ANY THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY,
 * OR TORT (INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE
 * OF THIS SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
 */
package engineering.swat.watch;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Build thread pools that even when not properly shutdown, will still not prevent the termination of the JVM.
 */
public class DaemonThreadPool {
    private DaemonThreadPool() {}

    /**
     * Generate a thread pool that will reuse threads, clear them after a while, but constrain the total amount of threads.
     * @param name name of the thread pool
     * @param maxThreads the maximum amount of threads to start in this pool, after this things will get queued.
     * @return an exectutor with deamon threads and constainted to a certain maximum
     */
    public static ExecutorService buildConstrainedCached(String name, int maxThreads) {
        if (maxThreads <= 0) {
            throw new IllegalArgumentException("maxThreads should be higher than 0");
        }
        var pool = new ThreadPoolExecutor(maxThreads, maxThreads,
            60, TimeUnit.SECONDS,
            new LinkedBlockingQueue<>(),
            buildFactory(name)
        );
        pool.allowCoreThreadTimeOut(true);
        return pool;
    }

    private static ThreadFactory buildFactory(String name) {
        return new ThreadFactory() {
            private final AtomicInteger id = new AtomicInteger(0);
            private final ThreadGroup group = new ThreadGroup(name);
            @Override
            public Thread newThread(Runnable r) {
                var t = new Thread(group, r, name + "-" + id.getAndIncrement());
                t.setDaemon(true);
                return t;
            }
        };
    }



}
