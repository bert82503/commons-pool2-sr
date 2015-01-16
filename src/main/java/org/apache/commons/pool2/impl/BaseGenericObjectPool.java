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

import java.io.PrintWriter;
import java.io.StringWriter;
import java.io.Writer;
import java.lang.management.ManagementFactory;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.List;
import java.util.TimerTask;
import java.util.concurrent.atomic.AtomicLong;

import javax.management.InstanceAlreadyExistsException;
import javax.management.InstanceNotFoundException;
import javax.management.MBeanRegistrationException;
import javax.management.MBeanServer;
import javax.management.MalformedObjectNameException;
import javax.management.NotCompliantMBeanException;
import javax.management.ObjectName;

import org.apache.commons.pool2.PooledObject;
import org.apache.commons.pool2.PooledObjectFactory;
import org.apache.commons.pool2.SwallowedExceptionListener;

/**
 * 为"可配置的对象池({@link GenericObjectPool})"提供通用的功能。
 * <p>
 * 此类存在的主要原因是减少两个对象池实现的重复代码。
 * <p>
 * Base class that provides common functionality for {@link GenericObjectPool}
 * and {@link GenericKeyedObjectPool}. The primary reason this class exists is
 * reduce code duplication between the two pool implementations.
 *
 * @param <T> Type of element pooled in this pool.
 *
 * This class is intended to be thread-safe.
 *
 * @version $Revision: $
 *
 * @since 2.0
 */
public abstract class BaseGenericObjectPool<T> {

    // Constants (常量)
    /**
     * The size of the caches used to store historical data for some attributes
     * so that rolling means may be calculated.
     * 用于存储一些属性的历史数据的缓存大小
     */
    public static final int MEAN_TIMING_STATS_CACHE_SIZE = 100;

    // Configuration attributes (配置属性，全部使用volatile实现线程之间的可见性)
    /** 对象池在某一个时刻拥有的最大池对象数量 (默认是无限个) */
    private volatile int maxTotal =
            GenericKeyedObjectPoolConfig.DEFAULT_MAX_TOTAL;
    // 获取池对象
    /** 池对象被耗尽时，操作是否被阻塞 (默认是阻塞) */
    private volatile boolean blockWhenExhausted =
            BaseObjectPoolConfig.DEFAULT_BLOCK_WHEN_EXHAUSTED;
    /** 获取池对象的最大等待时间ms (默认是无限等待) */
    private volatile long maxWaitMillis =
            BaseObjectPoolConfig.DEFAULT_MAX_WAIT_MILLIS;
    /** 对象池的使用方式 (默认是"先进先出"栈方式) */
    private volatile boolean lifo = BaseObjectPoolConfig.DEFAULT_LIFO;
    // "池对象的有效性"检测
    /** 在"创建池对象"时，检测其有效性 (默认是不检测) */
    private volatile boolean testOnCreate =
            BaseObjectPoolConfig.DEFAULT_TEST_ON_CREATE;
    /** 在"借用池对象"时，检测其有效性 (默认是不检测) */
    private volatile boolean testOnBorrow =
            BaseObjectPoolConfig.DEFAULT_TEST_ON_BORROW;
    /** 在"返回池对象"时，检测其有效性 (默认是不检测) */
    private volatile boolean testOnReturn =
            BaseObjectPoolConfig.DEFAULT_TEST_ON_RETURN;
    /** 在"池对象空闲"时，检测其有效性 (默认是不检测) */
    // "空闲对象的驱逐回收策略"检测
    private volatile boolean testWhileIdle =
            BaseObjectPoolConfig.DEFAULT_TEST_WHILE_IDLE;
    /** 空闲对象的驱逐者线程的运行间隔时间ms (默认是不运行) */
    private volatile long timeBetweenEvictionRunsMillis =
            BaseObjectPoolConfig.DEFAULT_TIME_BETWEEN_EVICTION_RUNS_MILLIS;
    /** 每次驱逐者线程运行的检测数量 (默认是3个) */
    private volatile int numTestsPerEvictionRun =
            BaseObjectPoolConfig.DEFAULT_NUM_TESTS_PER_EVICTION_RUN;
    /** 对象池中一个空闲对象的最大可空闲时间ms (默认是30分钟) */
    private volatile long minEvictableIdleTimeMillis =
            BaseObjectPoolConfig.DEFAULT_MIN_EVICTABLE_IDLE_TIME_MILLIS;
    /** 对象池中一个空闲对象的最小可空闲时间ms (默认是空闲对象立刻被驱逐) */
    private volatile long softMinEvictableIdleTimeMillis =
            BaseObjectPoolConfig.DEFAULT_SOFT_MIN_EVICTABLE_IDLE_TIME_MILLIS;
    /** 驱逐回收策略 */
    private volatile EvictionPolicy<T> evictionPolicy;


    // Internal (primarily state) attributes (内部属性(主要是状态))
    /** 关闭操作同步对象 */
    final Object closeLock = new Object();
    /** "对象池是否已被关闭"标记 (volatile实现线程之间的可见性) */
    volatile boolean closed = false;
    // 空闲对象的驱逐回收策略
    /** 用于初始化"驱逐者线程"的同步对象 */
    final Object evictionLock = new Object();
    /** "空闲对象"驱逐者线程 */
    private Evictor evictor = null; // @GuardedBy("evictionLock")
    /** 驱逐检测对象("空闲池对象")的迭代器 */
    Iterator<PooledObject<T>> evictionIterator = null; // @GuardedBy("evictionLock")
    /**
     * Class loader for evictor thread to use since in a J2EE or similar
     * environment the context class loader for the evictor thread may have
     * visibility of the correct factory. See POOL-161.
     * 驱逐者线程的类加载器
     */
    private final ClassLoader factoryClassLoader;


    // Monitoring (primarily JMX) attributes (监视属性(主要是JMX))
    /** 对象名称 */
    private final ObjectName oname;
    /** 创建行为的栈踪迹 */
    private final String creationStackTrace;
    // 各种行为的计数器
    /** 借用的池对象计数器 */
    private final AtomicLong borrowedCount = new AtomicLong(0);
    /** 返回的池对象计数器 */
    private final AtomicLong returnedCount = new AtomicLong(0);
    /** 创建的池对象计数器 */
    final AtomicLong createdCount = new AtomicLong(0);
    /** 销毁的池对象计数器 */
    final AtomicLong destroyedCount = new AtomicLong(0);
    /** 被驱逐者销毁的池对象计数器 */
    final AtomicLong destroyedByEvictorCount = new AtomicLong(0);
    /** 被借用校验销毁的池对象计数器 */
    final AtomicLong destroyedByBorrowValidationCount = new AtomicLong(0);
    // 各种状态的时间统计
    /** 各个池对象的活跃时间统计 */
    private final LinkedList<Long> activeTimes = new LinkedList<Long>(); // @GuardedBy("activeTimes") - except in initStats()
    /** 各个池对象的空闲时间统计 */
    private final LinkedList<Long> idleTimes = new LinkedList<Long>(); // @GuardedBy("activeTimes") - except in initStats()
    /** 各个池对象的等待时间统计 */
    private final LinkedList<Long> waitTimes = new LinkedList<Long>(); // @GuardedBy("activeTimes") - except in initStats()
    // 池对象借用等待
    /** 池对象借用等待同步锁 */
    private final Object maxBorrowWaitTimeMillisLock = new Object();
    /** 池对象借用的最大等待时间ms (默认是不等待) */
    private volatile long maxBorrowWaitTimeMillis = 0; // @GuardedBy("maxBorrowWaitTimeMillisLock")
    /** "被吞掉的异常"监视器 */
    private SwallowedExceptionListener swallowedExceptionListener = null;


    /**
     * Handles JMX registration (if required) and the initialization required for
     * monitoring.
     *
     * @param config        Pool configuration
     * @param jmxNameBase   The default base JMX name for the new pool unless
     *                      overridden by the config
     * @param jmxNamePrefix Prefix to be used for JMX name for the new pool
     */
    public BaseGenericObjectPool(BaseObjectPoolConfig config,
            String jmxNameBase, String jmxNamePrefix) {
        if (config.getJmxEnabled()) {
            this.oname = jmxRegister(config, jmxNameBase, jmxNamePrefix);
        } else {
            this.oname = null;
        }

        // Populate the creation stack trace
        this.creationStackTrace = getStackTrace(new Exception());

        // save the current CCL to be used later by the evictor Thread
        factoryClassLoader = Thread.currentThread().getContextClassLoader();

        // Initialise the attributes used to record rolling averages
        initStats();
    }


    /**
     * Returns the maximum number of objects that can be allocated by the pool
     * (checked out to clients, or idle awaiting checkout) at a given time. When
     * negative, there is no limit to the number of objects that can be
     * managed by the pool at one time.
     *
     * @return the cap on the total number of object instances managed by the
     *         pool.
     *
     * @see #setMaxTotal
     */
    public final int getMaxTotal() {
        return maxTotal;
    }

    /**
     * Sets the cap on the number of objects that can be allocated by the pool
     * (checked out to clients, or idle awaiting checkout) at a given time. Use
     * a negative value for no limit.
     *
     * @param maxTotal  The cap on the total number of object instances managed
     *                  by the pool. Negative values mean that there is no limit
     *                  to the number of objects allocated by the pool.
     *
     * @see #getMaxTotal
     */
    public final void setMaxTotal(int maxTotal) {
        this.maxTotal = maxTotal;
    }

    /**
     * Returns whether to block when the <code>borrowObject()</code> method is
     * invoked when the pool is exhausted (the maximum number of "active"
     * objects has been reached).
     *
     * @return <code>true</code> if <code>borrowObject()</code> should block
     *         when the pool is exhausted
     *
     * @see #setBlockWhenExhausted
     */
    public final boolean getBlockWhenExhausted() {
        return blockWhenExhausted;
    }

    /**
     * Sets whether to block when the <code>borrowObject()</code> method is
     * invoked when the pool is exhausted (the maximum number of "active"
     * objects has been reached).
     *
     * @param blockWhenExhausted    <code>true</code> if
     *                              <code>borrowObject()</code> should block
     *                              when the pool is exhausted
     *
     * @see #getBlockWhenExhausted
     */
    public final void setBlockWhenExhausted(boolean blockWhenExhausted) {
        this.blockWhenExhausted = blockWhenExhausted;
    }

    /**
     * Returns the maximum amount of time (in milliseconds) the
     * <code>borrowObject()</code> method should block before throwing an
     * exception when the pool is exhausted and
     * {@link #getBlockWhenExhausted} is true. When less than 0, the
     * <code>borrowObject()</code> method may block indefinitely.
     *
     * @return the maximum number of milliseconds <code>borrowObject()</code>
     *         will block.
     *
     * @see #setMaxWaitMillis
     * @see #setBlockWhenExhausted
     */
    public final long getMaxWaitMillis() {
        return maxWaitMillis;
    }

    /**
     * Sets the maximum amount of time (in milliseconds) the
     * <code>borrowObject()</code> method should block before throwing an
     * exception when the pool is exhausted and
     * {@link #getBlockWhenExhausted} is true. When less than 0, the
     * <code>borrowObject()</code> method may block indefinitely.
     *
     * @param maxWaitMillis the maximum number of milliseconds
     *                      <code>borrowObject()</code> will block or negative
     *                      for indefinitely.
     *
     * @see #getMaxWaitMillis
     * @see #setBlockWhenExhausted
     */
    public final void setMaxWaitMillis(long maxWaitMillis) {
        this.maxWaitMillis = maxWaitMillis;
    }

    /**
     * 返回"对象池"是否使用空闲对象的LIFO(后进先出)行为，总是从对象池中返回最近使用的对象；
     * 或者，使用FIFO(先进先出)队列，对象池总是返回空闲对象池中最老的那个对象。
     * <p>
     * 默认是{@code true}，即使用LIFO(后进先出)行为。
     * <p>
     * 
     * Returns whether the pool has LIFO (last in, first out) behaviour with
     * respect to idle objects - always returning the most recently used object
     * from the pool, or as a FIFO (first in, first out) queue, where the pool
     * always returns the oldest object in the idle object pool.
     *
     * @return <code>true</code> if the pool is configured with LIFO behaviour
     *         or <code>false</code> if the pool is configured with FIFO
     *         behaviour
     *
     * @see #setLifo
     */
    public final boolean getLifo() {
        return lifo;
    }

    /**
     * Sets whether the pool has LIFO (last in, first out) behaviour with
     * respect to idle objects - always returning the most recently used object
     * from the pool, or as a FIFO (first in, first out) queue, where the pool
     * always returns the oldest object in the idle object pool.
     *
     * @param lifo  <code>true</code> if the pool is to be configured with LIFO
     *              behaviour or <code>false</code> if the pool is to be
     *              configured with FIFO behaviour
     *
     * @see #getLifo()
     */
    public final void setLifo(boolean lifo) {
        this.lifo = lifo;
    }

    /**
     * Returns whether objects created for the pool will be validated before
     * being returned from the <code>borrowObject()</code> method. Validation is
     * performed by the <code>validateObject()</code> method of the factory
     * associated with the pool. If the object fails to validate, then
     * <code>borrowObject()</code> will fail.
     *
     * @return <code>true</code> if newly created objects are validated before
     *         being returned from the <code>borrowObject()</code> method
     *
     * @see #setTestOnCreate
     *
     * @since 2.2
     */
    public final boolean getTestOnCreate() {
        return testOnCreate;
    }

    /**
     * Sets whether objects created for the pool will be validated before
     * being returned from the <code>borrowObject()</code> method. Validation is
     * performed by the <code>validateObject()</code> method of the factory
     * associated with the pool. If the object fails to validate, then
     * <code>borrowObject()</code> will fail.
     *
     * @param testOnCreate  <code>true</code> if newly created objects should be
     *                      validated before being returned from the
     *                      <code>borrowObject()</code> method
     *
     * @see #getTestOnCreate
     *
     * @since 2.2
     */
    public final void setTestOnCreate(boolean testOnCreate) {
        this.testOnCreate = testOnCreate;
    }

    /**
     * Returns whether objects borrowed from the pool will be validated before
     * being returned from the <code>borrowObject()</code> method. Validation is
     * performed by the <code>validateObject()</code> method of the factory
     * associated with the pool. If the object fails to validate, it will be
     * removed from the pool and destroyed, and a new attempt will be made to
     * borrow an object from the pool.
     *
     * @return <code>true</code> if objects are validated before being returned
     *         from the <code>borrowObject()</code> method
     *
     * @see #setTestOnBorrow
     */
    public final boolean getTestOnBorrow() {
        return testOnBorrow;
    }

    /**
     * Sets whether objects borrowed from the pool will be validated before
     * being returned from the <code>borrowObject()</code> method. Validation is
     * performed by the <code>validateObject()</code> method of the factory
     * associated with the pool. If the object fails to validate, it will be
     * removed from the pool and destroyed, and a new attempt will be made to
     * borrow an object from the pool.
     *
     * @param testOnBorrow  <code>true</code> if objects should be validated
     *                      before being returned from the
     *                      <code>borrowObject()</code> method
     *
     * @see #getTestOnBorrow
     */
    public final void setTestOnBorrow(boolean testOnBorrow) {
        this.testOnBorrow = testOnBorrow;
    }

    /**
     * Returns whether objects borrowed from the pool will be validated when
     * they are returned to the pool via the <code>returnObject()</code> method.
     * Validation is performed by the <code>validateObject()</code> method of
     * the factory associated with the pool. Returning objects that fail validation
     * are destroyed rather then being returned the pool.
     *
     * @return <code>true</code> if objects are validated on return to
     *         the pool via the <code>returnObject()</code> method
     *
     * @see #setTestOnReturn
     */
    public final boolean getTestOnReturn() {
        return testOnReturn;
    }

    /**
     * Sets whether objects borrowed from the pool will be validated when
     * they are returned to the pool via the <code>returnObject()</code> method.
     * Validation is performed by the <code>validateObject()</code> method of
     * the factory associated with the pool. Returning objects that fail validation
     * are destroyed rather then being returned the pool.
     *
     * @param testOnReturn <code>true</code> if objects are validated on
     *                     return to the pool via the
     *                     <code>returnObject()</code> method
     *
     * @see #getTestOnReturn
     */
    public final void setTestOnReturn(boolean testOnReturn) {
        this.testOnReturn = testOnReturn;
    }

    /**
     * 返回对象池中的空闲对象是否会被"空闲对象驱逐者"验证其有效性
     * (见{@link #setTimeBetweenEvictionRunsMillis(long)})。
     * <p>
     * 校验是通过与该对象池相关的池对象工厂的
     * {@link PooledObjectFactory#validateObject(PooledObject) validateObject(PooledObject)}
     * 方法进行的。
     * <p>
     * 如果对象校验失败，则它会从对象池中被删除并销毁。
     * <p>
     * 默认是 {@code false}，池对象不会被"空闲对象驱逐者"验证其有效性。
     * <p>
     * 
     * Returns whether objects sitting idle in the pool will be validated by the
     * idle object evictor (if any - see
     * {@link #setTimeBetweenEvictionRunsMillis(long)}). Validation is performed
     * by the <code>validateObject()</code> method of the factory associated
     * with the pool. If the object fails to validate, it will be removed from
     * the pool and destroyed.
     *
     * @return <code>true</code> if objects will be validated by the evictor
     *
     * @see #setTestWhileIdle
     * @see #setTimeBetweenEvictionRunsMillis
     */
    public final boolean getTestWhileIdle() {
        return testWhileIdle;
    }

    /**
     * Returns whether objects sitting idle in the pool will be validated by the
     * idle object evictor (if any - see
     * {@link #setTimeBetweenEvictionRunsMillis(long)}). Validation is performed
     * by the <code>validateObject()</code> method of the factory associated
     * with the pool. If the object fails to validate, it will be removed from
     * the pool and destroyed.  Note that setting this property has no effect
     * unless the idle object evictor is enabled by setting
     * <code>timeBetweenEvictionRunsMillis</code> to a positive value.
     *
     * @param testWhileIdle
     *            <code>true</code> so objects will be validated by the evictor
     *
     * @see #getTestWhileIdle
     * @see #setTimeBetweenEvictionRunsMillis
     */
    public final void setTestWhileIdle(boolean testWhileIdle) {
        this.testWhileIdle = testWhileIdle;
    }

    /**
     * Returns the number of milliseconds to sleep between runs of the idle
     * object evictor thread. When non-positive, no idle object evictor thread
     * will be run.
     *
     * @return number of milliseconds to sleep between evictor runs
     *
     * @see #setTimeBetweenEvictionRunsMillis
     */
    public final long getTimeBetweenEvictionRunsMillis() {
        return timeBetweenEvictionRunsMillis;
    }

    /**
     * 设置"空闲对象的驱逐者线程"运行调度间隔时间。（同时，会立即启动"驱逐者线程"）
     * <p>
     * 如果该值是非正数，则没有"空闲对象的驱逐者线程"将运行。
     * <p>
     * 默认是 {@code -1}，即没有"空闲对象的驱逐者线程"在后台运行着。
     * <p>
     * 上一层入口：{@link GenericObjectPool#setConfig(GenericObjectPoolConfig)}<br>
     * 顶层入口：{@link GenericObjectPool#GenericObjectPool(PooledObjectFactory, GenericObjectPoolConfig)}，
     * 在最后还会调用{@link #startEvictor(long)}来再次启动"空闲对象的驱逐者线程"。<br>
     * 这样在初始化时，这里创建的"驱逐者线程"就多余了，会立刻被销毁掉。<br>
     * 但这里为什么要这样实现呢？<br>
     * 我的理解是：为了能动态地更新"驱逐者线程"的调度间隔时间。
     * <p>
     * 
     * Sets the number of milliseconds to sleep between runs of the idle
     * object evictor thread. When non-positive, no idle object evictor thread
     * will be run.
     *
     * @param timeBetweenEvictionRunsMillis
     *            number of milliseconds to sleep between evictor runs ("驱逐者线程"运行的间隔毫秒数)
     *
     * @see #getTimeBetweenEvictionRunsMillis
     */
    public final void setTimeBetweenEvictionRunsMillis(
            long timeBetweenEvictionRunsMillis) {
        this.timeBetweenEvictionRunsMillis = timeBetweenEvictionRunsMillis;
        // 启动"驱逐者线程"
        this.startEvictor(timeBetweenEvictionRunsMillis);
    }

    /**
     * 返回空闲对象驱逐者线程在每轮运行期间要检测的对象的最大数量。
     * <p>
     * 如果该值是正数，一轮执行的测试数量将会是配置的值和对象池的空闲实例数量的最低值；
     * 如果该值是负数，一轮执行的测试数量将会是{@code ceil({@link #getNumIdle}/abs({@link #getNumTestsPerEvictionRun}))}，
     * 这意味着，如果该值是{@code -n}，每次运行的测试数量大概是空闲对象的{@code 1/n}。
     * <p>
     * 默认是每轮测试{@code 3}个空闲对象。
     * <p>
     * 
     * Returns the maximum number of objects to examine during each run (if any)
     * of the idle object evictor thread. When positive, the number of tests
     * performed for a run will be the minimum of the configured value and the
     * number of idle instances in the pool. When negative, the number of tests
     * performed will be <code>ceil({@link #getNumIdle}/
     * abs({@link #getNumTestsPerEvictionRun}))</code> which means that when the
     * value is <code>-n</code> roughly one nth of the idle objects will be
     * tested per run.
     *
     * @return max number of objects to examine during each evictor run
     *
     * @see #setNumTestsPerEvictionRun
     * @see #setTimeBetweenEvictionRunsMillis
     */
    public final int getNumTestsPerEvictionRun() {
        return numTestsPerEvictionRun;
    }

    /**
     * Sets the maximum number of objects to examine during each run (if any)
     * of the idle object evictor thread. When positive, the number of tests
     * performed for a run will be the minimum of the configured value and the
     * number of idle instances in the pool. When negative, the number of tests
     * performed will be <code>ceil({@link #getNumIdle}/
     * abs({@link #getNumTestsPerEvictionRun}))</code> which means that when the
     * value is <code>-n</code> roughly one nth of the idle objects will be
     * tested per run.
     *
     * @param numTestsPerEvictionRun
     *            max number of objects to examine during each evictor run
     *
     * @see #getNumTestsPerEvictionRun
     * @see #setTimeBetweenEvictionRunsMillis
     */
    public final void setNumTestsPerEvictionRun(int numTestsPerEvictionRun) {
        this.numTestsPerEvictionRun = numTestsPerEvictionRun;
    }

    /**
     * 返回对象池中一个空闲对象的最大可空闲时间，在被空闲对象驱逐者驱逐之前。
     * <p>
     * 如果该值是非正数，则在空闲期间没有对象会从对象池中被驱逐出去。
     * <p>
     * 默认是 {@code 30}分钟，即只要空闲对象的空闲时间超过30分钟，都会立刻从对象池中被驱逐出去。
     * <p>
     * Returns the maximum amount of time an object may sit idle in the pool
     * before it is eligible for eviction by the idle object evictor (if any -
     * see {@link #setTimeBetweenEvictionRunsMillis(long)}). When non-positive,
     * no objects will be evicted from the pool due to idle time alone.
     *
     * @return minimum amount of time an object may sit idle in the pool before
     *         it is eligible for eviction
     *
     * @see #setMinEvictableIdleTimeMillis
     * @see #setTimeBetweenEvictionRunsMillis
     */
    public final long getMinEvictableIdleTimeMillis() {
        return minEvictableIdleTimeMillis;
    }

    /**
     * Sets the minimum amount of time an object may sit idle in the pool
     * before it is eligible for eviction by the idle object evictor (if any -
     * see {@link #setTimeBetweenEvictionRunsMillis(long)}). When non-positive,
     * no objects will be evicted from the pool due to idle time alone.
     *
     * @param minEvictableIdleTimeMillis
     *            minimum amount of time an object may sit idle in the pool
     *            before it is eligible for eviction
     *
     * @see #getMinEvictableIdleTimeMillis
     * @see #setTimeBetweenEvictionRunsMillis
     */
    public final void setMinEvictableIdleTimeMillis(
            long minEvictableIdleTimeMillis) {
        this.minEvictableIdleTimeMillis = minEvictableIdleTimeMillis;
    }

    /**
     * 返回对象池中一个空闲对象的最小可空闲时间，在被空闲对象驱逐者驱逐之前。
     * <p>
     * 其它额外条件是，对象池中至少保留{@code minIdle}个对象实例。
     * <p>
     * 此设置会被{@link #getMinEvictableIdleTimeMillis()}的返回值覆盖
     * (也就是说，如果{@link #getMinEvictableIdleTimeMillis()}的返回值是正整数，
     * 则{@link #getSoftMinEvictableIdleTimeMillis()}被忽略)。
     * （<font color="red">从{@link GenericObjectPool#evict()}实现看，这里描述的不对！</font>）
     * <p>
     * 默认是 {@code -1}，即对象只要一有空闲，就被纳入到驱逐对象列表中。
     * <p>
     * Returns the minimum amount of time an object may sit idle in the pool
     * before it is eligible for eviction by the idle object evictor (if any -
     * see {@link #setTimeBetweenEvictionRunsMillis(long)}),
     * with the extra condition that at least <code>minIdle</code> object
     * instances remain in the pool. This setting is overridden by
     * {@link #getMinEvictableIdleTimeMillis} (that is, if
     * {@link #getMinEvictableIdleTimeMillis} is positive, then
     * {@link #getSoftMinEvictableIdleTimeMillis} is ignored).
     *
     * @return minimum amount of time an object may sit idle in the pool before
     *         it is eligible for eviction if minIdle instances are available
     *
     * @see #setSoftMinEvictableIdleTimeMillis
     */
    public final long getSoftMinEvictableIdleTimeMillis() {
        return softMinEvictableIdleTimeMillis;
    }

    /**
     * Sets the minimum amount of time an object may sit idle in the pool
     * before it is eligible for eviction by the idle object evictor (if any -
     * see {@link #setTimeBetweenEvictionRunsMillis(long)}),
     * with the extra condition that at least <code>minIdle</code> object
     * instances remain in the pool. This setting is overridden by
     * {@link #getMinEvictableIdleTimeMillis} (that is, if
     * {@link #getMinEvictableIdleTimeMillis} is positive, then
     * {@link #getSoftMinEvictableIdleTimeMillis} is ignored).
     *
     * @param softMinEvictableIdleTimeMillis
     *            minimum amount of time an object may sit idle in the pool
     *            before it is eligible for eviction if minIdle instances are
     *            available
     *
     * @see #getSoftMinEvictableIdleTimeMillis
     */
    public final void setSoftMinEvictableIdleTimeMillis(
            long softMinEvictableIdleTimeMillis) {
        this.softMinEvictableIdleTimeMillis = softMinEvictableIdleTimeMillis;
    }

    /**
     * Returns the name of the {@link EvictionPolicy} implementation that is
     * used by this pool.
     *
     * @return  The fully qualified class name of the {@link EvictionPolicy}
     *
     * @see #setEvictionPolicyClassName(String)
     */
    public final String getEvictionPolicyClassName() {
        return evictionPolicy.getClass().getName();
    }

    /**
     * Sets the name of the {@link EvictionPolicy} implementation that is
     * used by this pool.
     *
     * @param evictionPolicyClassName   the fully qualified class name of the
     *                                  new eviction policy
     *
     * @see #getEvictionPolicyClassName()
     */
    public final void setEvictionPolicyClassName(
            String evictionPolicyClassName) {
        try {
            Class<?> clazz = Class.forName(evictionPolicyClassName);
            Object policy = clazz.newInstance();
            if (policy instanceof EvictionPolicy<?>) {
                @SuppressWarnings("unchecked") // safe, because we just checked the class
                EvictionPolicy<T> evicPolicy = (EvictionPolicy<T>) policy;
                this.evictionPolicy = evicPolicy;
            }
        } catch (ClassNotFoundException e) {
            throw new IllegalArgumentException(
                    "Unable to create EvictionPolicy instance of type " +
                    evictionPolicyClassName, e);
        } catch (InstantiationException e) {
            throw new IllegalArgumentException(
                    "Unable to create EvictionPolicy instance of type " +
                    evictionPolicyClassName, e);
        } catch (IllegalAccessException e) {
            throw new IllegalArgumentException(
                    "Unable to create EvictionPolicy instance of type " +
                    evictionPolicyClassName, e);
        }
    }


    /**
     * Closes the pool, destroys the remaining idle objects and, if registered
     * in JMX, deregisters it.
     */
    public abstract void close();

    /**
     * 对象池实例是否已被关闭。
     * <p>
     * Has this pool instance been closed.
     * @return <code>true</code> when this pool has been closed.
     */
    public final boolean isClosed() {
        return closed;
    }

    /**
     * 执行{@link numTests}个空闲池对象的驱逐测试，驱逐那些符合驱逐条件的被检测对象。
     * <p>
     * 如果{@code testWhileIdle}为{@code true}，则被检测的对象在访问期间是有效的(无效则会被删除)；
     * 否则，仅有那些池对象的空闲时间超过{@code minEvicableIdleTimeMillis}会被删除。
     * <p>
     * Perform <code>numTests</code> idle object eviction tests, evicting
     * examined objects that meet the criteria for eviction. If
     * <code>testWhileIdle</code> is true, examined objects are validated
     * when visited (and removed if invalid); otherwise only objects that
     * have been idle for more than <code>minEvicableIdleTimeMillis</code>
     * are removed.</p>
     *
     * @throws Exception when there is a problem evicting idle objects. (当这是一个有问题的驱逐空闲池对象时，才会抛出Exception异常。)
     */
    public abstract void evict() throws Exception;

    /**
     * Returns the {@link EvictionPolicy} defined for this pool.
     * @return the eviction policy
     */
    final EvictionPolicy<T> getEvictionPolicy() {
        return evictionPolicy;
    }

    /**
     * 校验"对象池"还打开着。
     * <p>
     * Verifies that the pool is open.
     * @throws IllegalStateException if the pool is closed.
     */
    final void assertOpen() throws IllegalStateException {
        if (this.isClosed()) {
            throw new IllegalStateException("Pool not open");
        }
    }

    /**
     * 启动"空闲对象的驱逐者线程"。
     * <p>
     * 如果有一个"驱逐者线程"({@link Evictor})运行着，则会先停止它；
     * 然后创建一个新的"驱逐者线程"，并使用"驱逐者定时器"({@link EvictionTimer})进行调度。
     * <p>
     * Starts the evictor with the given delay. If there is an evictor
     * running when this method is called, it is stopped and replaced with a
     * new evictor with the specified delay.</p>
     *
     * <p>This method needs to be final, since it is called from a constructor. (因为它被一个构造器调用)
     * See POOL-195.</p>
     *
     * @param delay time in milliseconds before start and between eviction runs (驱逐者线程运行的开始和间隔时间 毫秒数)
     */
    final void startEvictor(long delay) {
        synchronized (evictionLock) { // 同步锁
            if (null != evictor) {
            	// 先释放申请的资源
                EvictionTimer.cancel(evictor);
                evictor = null;
                evictionIterator = null;
            }
            if (delay > 0) {
                evictor = new Evictor();
                EvictionTimer.schedule(evictor, delay, delay);
            }
        }
    }

    /**
     * 试图确保配置的对象池中可用"空闲池对象"实例的最小数量。
     * <p>
     * Tries to ensure that the configured minimum number of idle instances are
     * available in the pool.
     * @throws Exception if an error occurs creating idle instances
     */
    abstract void ensureMinIdle() throws Exception;


    // Monitoring (primarily JMX) related methods

    /**
     * Provides the name under which the pool has been registered with the
     * platform MBean server or <code>null</code> if the pool has not been
     * registered.
     * @return the JMX name
     */
    public final ObjectName getJmxName() {
        return oname;
    }

    /**
     * Provides the stack trace for the call that created this pool. JMX
     * registration may trigger a memory leak so it is important that pools are
     * deregistered when no longer used by calling the {@link #close()} method.
     * This method is provided to assist with identifying code that creates but
     * does not close it thereby creating a memory leak.
     * @return pool creation stack trace
     */
    public final String getCreationStackTrace() {
        return creationStackTrace;
    }

    /**
     * The total number of objects successfully borrowed from this pool over the
     * lifetime of the pool.
     * @return the borrowed object count
     */
    public final long getBorrowedCount() {
        return borrowedCount.get();
    }

    /**
     * The total number of objects returned to this pool over the lifetime of
     * the pool. This excludes attempts to return the same object multiple
     * times.
     * @return the returned object count
     */
    public final long getReturnedCount() {
        return returnedCount.get();
    }

    /**
     * The total number of objects created for this pool over the lifetime of
     * the pool.
     * @return the created object count
     */
    public final long getCreatedCount() {
        return createdCount.get();
    }

    /**
     * The total number of objects destroyed by this pool over the lifetime of
     * the pool.
     * @return the destroyed object count
     */
    public final long getDestroyedCount() {
        return destroyedCount.get();
    }

    /**
     * The total number of objects destroyed by the evictor associated with this
     * pool over the lifetime of the pool.
     * @return the evictor destroyed object count
     */
    public final long getDestroyedByEvictorCount() {
        return destroyedByEvictorCount.get();
    }

    /**
     * The total number of objects destroyed by this pool as a result of failing
     * validation during <code>borrowObject()</code> over the lifetime of the
     * pool.
     * @return validation destroyed object count
     */
    public final long getDestroyedByBorrowValidationCount() {
        return destroyedByBorrowValidationCount.get();
    }

    /**
     * The mean time objects are active for based on the last {@link
     * #MEAN_TIMING_STATS_CACHE_SIZE} objects returned to the pool.
     * @return mean time an object has been checked out from the pool among
     * recently returned objects
     */
    public final long getMeanActiveTimeMillis() {
        return getMeanFromStatsCache(activeTimes);
    }

    /**
     * The mean time objects are idle for based on the last {@link
     * #MEAN_TIMING_STATS_CACHE_SIZE} objects borrowed from the pool.
     * @return mean time an object has been idle in the pool among recently
     * borrowed objects
     */
    public final long getMeanIdleTimeMillis() {
        return getMeanFromStatsCache(idleTimes);
    }

    /**
     * The mean time threads wait to borrow an object based on the last {@link
     * #MEAN_TIMING_STATS_CACHE_SIZE} objects borrowed from the pool.
     * @return mean time in milliseconds that a recently served thread has had
     * to wait to borrow an object from the pool
     */
    public final long getMeanBorrowWaitTimeMillis() {
        return getMeanFromStatsCache(waitTimes);
    }

    /**
     * The maximum time a thread has waited to borrow objects from the pool.
     * @return maximum wait time in milliseconds since the pool was created
     */
    public final long getMaxBorrowWaitTimeMillis() {
        return maxBorrowWaitTimeMillis;
    }

    /**
     * The number of instances currently idle in this pool.
     * @return count of instances available for checkout from the pool
     */
    public abstract int getNumIdle();

    /**
     * The listener used (if any) to receive notifications of exceptions
     * unavoidably swallowed by the pool.
     *
     * @return The listener or <code>null</code> for no listener
     */
    public final SwallowedExceptionListener getSwallowedExceptionListener() {
        return swallowedExceptionListener;
    }

    /**
     * The listener used (if any) to receive notifications of exceptions
     * unavoidably swallowed by the pool.
     *
     * @param swallowedExceptionListener    The listener or <code>null</code>
     *                                      for no listener
     */
    public final void setSwallowedExceptionListener(
            SwallowedExceptionListener swallowedExceptionListener) {
        this.swallowedExceptionListener = swallowedExceptionListener;
    }

    /**
     * Swallows an exception and notifies the configured listener for swallowed
     * exceptions queue.
     *
     * @param e exception to be swallowed
     */
    final void swallowException(Exception e) {
        SwallowedExceptionListener listener = getSwallowedExceptionListener();

        if (listener == null) {
            return;
        }

        try {
            listener.onSwallowException(e);
        } catch (OutOfMemoryError oome) {
            throw oome;
        } catch (VirtualMachineError vme) {
            throw vme;
        } catch (Throwable t) {
            // Ignore. Enjoy the irony.
        }
    }

    /**
     * 更新统计数据。
     * <p>
     * 
     * Updates statistics after an object is borrowed from the pool.
     * 
     * @param p object borrowed from the pool
     * @param waitTime time (in milliseconds) that the borrowing thread had to wait
     */
    final void updateStatsBorrow(PooledObject<T> p, long waitTime) {
        borrowedCount.incrementAndGet();
        synchronized (idleTimes) {
            idleTimes.add(Long.valueOf(p.getIdleTimeMillis()));
            idleTimes.poll();
        }
        synchronized (waitTimes) {
            waitTimes.add(Long.valueOf(waitTime));
            waitTimes.poll();
        }
        synchronized (maxBorrowWaitTimeMillisLock) {
            if (waitTime > maxBorrowWaitTimeMillis) {
                maxBorrowWaitTimeMillis = waitTime;
            }
        }
    }

    /**
     * Updates statistics after an object is returned to the pool.
     * @param activeTime the amount of time (in milliseconds) that the returning
     * object was checked out
     */
    final void updateStatsReturn(long activeTime) {
        returnedCount.incrementAndGet();
        synchronized (activeTimes) {
            activeTimes.add(Long.valueOf(activeTime));
            activeTimes.poll();
        }
    }

    /**
     * Unregisters this pool's MBean.
     */
    final void jmxUnregister() {
        if (oname != null) {
            try {
                ManagementFactory.getPlatformMBeanServer().unregisterMBean(
                        oname);
            } catch (MBeanRegistrationException e) {
                swallowException(e);
            } catch (InstanceNotFoundException e) {
                swallowException(e);
            }
        }
    }

    /**
     * Registers the pool with the platform MBean server.
     * The registered name will be
     * <code>jmxNameBase + jmxNamePrefix + i</code> where i is the least
     * integer greater than or equal to 1 such that the name is not already
     * registered. Swallows MBeanRegistrationException, NotCompliantMBeanException
     * returning null.
     *
     * @param config Pool configuration
     * @param jmxNameBase default base JMX name for this pool
     * @param jmxNamePrefix name prefix
     * @return registered ObjectName, null if registration fails
     */
    private ObjectName jmxRegister(BaseObjectPoolConfig config,
            String jmxNameBase, String jmxNamePrefix) {
        ObjectName objectName = null;
        MBeanServer mbs = ManagementFactory.getPlatformMBeanServer();
        int i = 1;
        boolean registered = false;
        String base = config.getJmxNameBase();
        if (base == null) {
            base = jmxNameBase;
        }
        while (!registered) {
            try {
                ObjectName objName;
                // Skip the numeric suffix for the first pool in case there is
                // only one so the names are cleaner.
                if (i == 1) {
                    objName = new ObjectName(base + jmxNamePrefix);
                } else {
                    objName = new ObjectName(base + jmxNamePrefix + i);
                }
                mbs.registerMBean(this, objName);
                objectName = objName;
                registered = true;
            } catch (MalformedObjectNameException e) {
                if (BaseObjectPoolConfig.DEFAULT_JMX_NAME_PREFIX.equals(
                        jmxNamePrefix) && jmxNameBase.equals(base)) {
                    // Shouldn't happen. Skip registration if it does.
                    registered = true;
                } else {
                    // Must be an invalid name. Use the defaults instead.
                    jmxNamePrefix =
                            BaseObjectPoolConfig.DEFAULT_JMX_NAME_PREFIX;
                    base = jmxNameBase;
                }
            } catch (InstanceAlreadyExistsException e) {
                // Increment the index and try again
                i++;
            } catch (MBeanRegistrationException e) {
                // Shouldn't happen. Skip registration if it does.
                registered = true;
            } catch (NotCompliantMBeanException e) {
                // Shouldn't happen. Skip registration if it does.
                registered = true;
            }
        }
        return objectName;
    }

    /**
     * Gets the stack trace of an exception as a string.
     * @param e exception to trace
     * @return exception stack trace as a string
     */
    private String getStackTrace(Exception e) {
        // Need the exception in string form to prevent the retention of
        // references to classes in the stack trace that could trigger a memory
        // leak in a container environment.
        Writer w = new StringWriter();
        PrintWriter pw = new PrintWriter(w);
        e.printStackTrace(pw);
        return w.toString();
    }

    /**
     * Returns the greatest integer less than ore equal to the arithmetic mean
     * of the entries in <code>cache,</code> acquiring and holding the argument's
     * monitor while making a local copy.
     * @param cache list containing entries to analyze
     * @return truncated arithmetic mean
     */
    private long getMeanFromStatsCache(LinkedList<Long> cache) {
        List<Long> times = new ArrayList<Long>(MEAN_TIMING_STATS_CACHE_SIZE);
        synchronized (cache) {
            times.addAll(cache);
        }
        double result = 0;
        int counter = 0;
        Iterator<Long> iter = times.iterator();
        while (iter.hasNext()) {
            Long time = iter.next();
            if (time != null) {
                counter++;
                result = result * ((counter - 1) / (double) counter) +
                        time.longValue()/(double) counter;
            }
        }
        return (long) result;
    }

    /**
     * Initializes pool statistics.
     */
    private void initStats() {
        for (int i = 0; i < MEAN_TIMING_STATS_CACHE_SIZE; i++) {
            activeTimes.add(null);
            idleTimes.add(null);
            waitTimes.add(null);
        }
    }


    // Inner classes

    /**
     * "空闲对象的驱逐者"定时任务，继承自{@link TimerTask}。
     * <p>
     * The idle object evictor {@link TimerTask}.
     *
     * @see GenericObjectPool#GenericObjectPool(PooledObjectFactory, GenericObjectPoolConfig)
     * @see GenericKeyedObjectPool#setTimeBetweenEvictionRunsMillis(long)
     */
    class Evictor extends TimerTask {

        /**
         * 运行对象池维护线程。
         * 驱逐对象具有驱逐者的资格，同时保证空闲实例可用的最小数量。
         * 因为调用"驱逐者线程"的定时器是被所有对象池共享的，
         * 但对象池可能存在不同的类加载器中，所以驱逐者必须确保采取的任何行为
         * 都得在与对象池相关的工厂的类加载器下。
         * <p>
         * Run pool maintenance. Evict objects qualifying for eviction and then
         * ensure that the minimum number of idle instances are available.
         * Since the Timer that invokes Evictors is shared for all Pools but
         * pools may exist in different class loaders, the Evictor ensures that
         * any actions taken are under the class loader of the factory
         * associated with the pool.
         */
        @Override
        public void run() {
            ClassLoader savedClassLoader =
                    Thread.currentThread().getContextClassLoader();
            try {
                // Set the class loader for the factory (设置"工厂的类加载器")
                Thread.currentThread().setContextClassLoader(
                        factoryClassLoader);

                // Evict from the pool (从"对象池"中驱逐)
                try {
                	// 1. 执行numTests个空闲池对象的驱逐测试，驱逐那些符合驱逐条件的被检测对象
                    evict(); // 抽象方法
                } catch(Exception e) {
                    swallowException(e);
                } catch(OutOfMemoryError oome) {
                    // Log problem but give evictor thread a chance to continue
                    // in case error is recoverable
                    oome.printStackTrace(System.err);
                }
                // Re-create idle instances. (重新创建"空闲池对象"实例)
                try {
                	// 2. 试图确保配置的对象池中可用"空闲池对象"实例的最小数量
                    ensureMinIdle(); // 抽象方法
                } catch (Exception e) {
                    swallowException(e);
                }
            } finally {
                // Restore the previous CCL
                Thread.currentThread().setContextClassLoader(savedClassLoader);
            }
        }
    }

}
