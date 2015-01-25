/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.commons.pool2.impl;

import java.security.AccessController;
import java.security.PrivilegedAction;
import java.util.Timer;
import java.util.TimerTask;

/**
 * 提供一个所有"对象池"共享的"空闲对象的驱逐定时器"。<br>
 * 
 * 此类包装了标准的定时器({@link Timer})，并追踪有多少个"对象池"使用它。<br>
 * 
 * 如果没有"对象池"使用"这个定时器"，它会被取消。这样可以防止线程一直运行着
 * (这会导致内存泄漏)，防止应用程序关闭或重新加载。
 * <p>
 * 此类是包范围的，以防止其被纳入到池框架的公共API中。
 * <p>
 * <font color="red">此类是线程安全的！</font>
 * <p>
 * 
 * Provides a shared idle object eviction timer for all pools. This class wraps
 * the standard {@link Timer} and keeps track of how many pools are using it.
 * If no pools are using the timer, it is canceled. This prevents a thread
 * being left running which, in application server environments, can lead to
 * memory leaks and/or prevent applications from shutting down or reloading
 * cleanly.
 * <p>
 * This class has package scope to prevent its inclusion in the pool public API.
 * The class declaration below should *not* be changed to public.
 * <p>
 * This class is intended to be thread-safe.
 *
 * @since 2.0
 */
class EvictionTimer {

    /** Timer instance (定时器实例) */
    private static Timer _timer; //@GuardedBy("this")

    /** Static usage count tracker (使用计数追踪器) */
    private static int _usageCount; //@GuardedBy("this")

    /** Prevent instantiation (防止实例化) */
    private EvictionTimer() {
        // Hide the default constructor
    }

    /**
     * 添加指定的"驱逐检测任务"到这个"定时器"。
     * 任务，是通过调用该方法添加的，必须调用{@link #cancel(TimerTask)}来取消这个任务，
     * 以防止内存或线程泄漏。
     * <p>
     * Add the specified eviction task to the timer. Tasks that are added with a
     * call to this method *must* call {@link #cancel(TimerTask)} to cancel the
     * task to prevent memory and/or thread leaks in application server
     * environments.
     * @param task      Task to be scheduled (待调度的任务)
     * @param delay     Delay in milliseconds before task is executed (任务执行前的等待时间)
     * @param period    Time in milliseconds between executions (执行间隔时间)
     */
    static synchronized void schedule(TimerTask task, long delay, long period) {
        if (null == _timer) {
            // Force the new Timer thread to be created with a context class
            // loader set to the class loader that loaded this library
            ClassLoader ccl = AccessController.doPrivileged(
                    new PrivilegedGetTccl());
            try {
                AccessController.doPrivileged(new PrivilegedSetTccl(
                        EvictionTimer.class.getClassLoader()));
                _timer = new Timer("commons-pool-EvictionTimer", true);
            } finally {
                AccessController.doPrivileged(new PrivilegedSetTccl(ccl));
            }
        }
        // 增加"使用计数器"，并调度"任务"
        _usageCount++;
        _timer.schedule(task, delay, period);
    }

    /**
     * 从"定时器"中删除指定的"驱逐检测任务"。
     * <p>
     * Remove the specified eviction task from the timer.
     * 
     * @param task      Task to be scheduled (待取消的任务)
     */
    static synchronized void cancel(TimerTask task) {
        task.cancel(); // 1. 将任务的状态标记为"取消(CANCELLED)"状态
        _usageCount--;
        if (_usageCount == 0) { // 2. 如果没有对象池使用这个定时器，定时器就会被取消
            _timer.cancel();
            _timer = null;
        }
    }

    /**
     * {@link PrivilegedAction} used to get the ContextClassLoader (获取"上下文类加载器")
     */
    private static class PrivilegedGetTccl implements PrivilegedAction<ClassLoader> {

        @Override
        public ClassLoader run() {
            return Thread.currentThread().getContextClassLoader();
        }
    }

    /**
     * {@link PrivilegedAction} used to set the ContextClassLoader (设置"上下文类加载器")
     */
    private static class PrivilegedSetTccl implements PrivilegedAction<Void> {

        /** ClassLoader */
        private final ClassLoader cl;

        /**
         * Create a new PrivilegedSetTccl using the given classloader
         * @param cl ClassLoader to use
         */
        PrivilegedSetTccl(ClassLoader cl) {
            this.cl = cl;
        }

        @Override
        public Void run() {
            Thread.currentThread().setContextClassLoader(cl);
            return null;
        }
    }

}
