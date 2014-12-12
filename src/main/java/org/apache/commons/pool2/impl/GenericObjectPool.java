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

import java.util.ArrayList;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import org.apache.commons.pool2.ObjectPool;
import org.apache.commons.pool2.PoolUtils;
import org.apache.commons.pool2.PooledObject;
import org.apache.commons.pool2.PooledObjectFactory;
import org.apache.commons.pool2.PooledObjectState;
import org.apache.commons.pool2.SwallowedExceptionListener;
import org.apache.commons.pool2.TrackedUse;
import org.apache.commons.pool2.UsageTracking;

/**
 * "一个可配置的对象池({@link ObjectPool})"实现。
 * <p>
 * 
 * A configurable {@link ObjectPool} implementation.
 * <p>
 * When coupled with the appropriate {@link PooledObjectFactory},
 * <code>GenericObjectPool</code> provides robust pooling functionality for
 * arbitrary objects.</p>
 * <p>
 * Optionally, one may configure the pool to examine and possibly evict objects
 * as they sit idle in the pool and to ensure that a minimum number of idle
 * objects are available. This is performed by an "idle object eviction" thread,
 * which runs asynchronously. Caution should be used when configuring this
 * optional feature. Eviction runs contend with client threads for access to
 * objects in the pool, so if they run too frequently performance issues may
 * result.</p>
 * <p>
 * The pool can also be configured to detect and remove "abandoned" objects,
 * i.e. objects that have been checked out of the pool but neither used nor
 * returned before the configured
 * {@link AbandonedConfig#getRemoveAbandonedTimeout() removeAbandonedTimeout}.
 * Abandoned object removal can be configured to happen when
 * <code>borrowObject</code> is invoked and the pool is close to starvation, or
 * it can be executed by the idle object evictor, or both. If pooled objects
 * implement the {@link TrackedUse} interface, their last use will be queried
 * using the <code>getLastUsed</code> method on that interface; otherwise
 * abandonment is determined by how long an object has been checked out from
 * the pool.</p>
 * <p>
 * Implementation note: To prevent possible deadlocks, care has been taken to
 * ensure that no call to a factory method will occur within a synchronization
 * block. See POOL-125 and DBCP-44 for more information.</p>
 * <p>
 * This class is intended to be thread-safe.</p>
 *
 * @see GenericKeyedObjectPool
 *
 * @param <T> Type of element pooled in this pool.
 *
 * @version $Revision: 1569016 $
 *
 * @since 2.0
 */
public class GenericObjectPool<T> extends BaseGenericObjectPool<T>
        implements ObjectPool<T>, GenericObjectPoolMXBean, UsageTracking<T> {

    /**
     * Create a new <code>GenericObjectPool</code> using defaults from
     * {@link GenericObjectPoolConfig}.
     *
     * @param factory The object factory to be used to create object instances
     *                used by this pool
     */
    public GenericObjectPool(PooledObjectFactory<T> factory) {
        this(factory, new GenericObjectPoolConfig());
    }

    /**
     * 使用特定配置来创建一个新的"通用对象池"实例。
     * <p>
     * Create a new <code>GenericObjectPool</code> using a specific
     * configuration.
     *
     * @param factory   The object factory to be used to create object instances
     *                  used by this pool
     * @param config    The configuration to use for this pool instance. The
     *                  configuration is used by value. Subsequent changes to
     *                  the configuration object will not be reflected in the
     *                  pool.
     */
    public GenericObjectPool(PooledObjectFactory<T> factory,
            GenericObjectPoolConfig config) {

        super(config, ONAME_BASE, config.getJmxNamePrefix());

        if (factory == null) {
            jmxUnregister(); // tidy up
            throw new IllegalArgumentException("factory may not be null");
        }
        this.factory = factory;

        setConfig(config);

        startEvictor(getTimeBetweenEvictionRunsMillis());
    }

    /**
     * Create a new <code>GenericObjectPool</code> that tracks and destroys
     * objects that are checked out, but never returned to the pool.
     *
     * @param factory   The object factory to be used to create object instances
     *                  used by this pool
     * @param config    The base pool configuration to use for this pool instance.
     *                  The configuration is used by value. Subsequent changes to
     *                  the configuration object will not be reflected in the
     *                  pool.
     * @param abandonedConfig  Configuration for abandoned object identification
     *                         and removal.  The configuration is used by value.
     */
    public GenericObjectPool(PooledObjectFactory<T> factory,
            GenericObjectPoolConfig config, AbandonedConfig abandonedConfig) {
        this(factory, config);
        setAbandonedConfig(abandonedConfig);
    }

    /**
     * Returns the cap on the number of "idle" instances in the pool. If maxIdle
     * is set too low on heavily loaded systems it is possible you will see
     * objects being destroyed and almost immediately new objects being created.
     * This is a result of the active threads momentarily returning objects
     * faster than they are requesting them them, causing the number of idle
     * objects to rise above maxIdle. The best value for maxIdle for heavily
     * loaded system will vary but the default is a good starting point.
     *
     * @return the maximum number of "idle" instances that can be held in the
     *         pool or a negative value if there is no limit
     *
     * @see #setMaxIdle
     */
    @Override
    public int getMaxIdle() {
        return maxIdle;
    }

    /**
     * Returns the cap on the number of "idle" instances in the pool. If maxIdle
     * is set too low on heavily loaded systems it is possible you will see
     * objects being destroyed and almost immediately new objects being created.
     * This is a result of the active threads momentarily returning objects
     * faster than they are requesting them them, causing the number of idle
     * objects to rise above maxIdle. The best value for maxIdle for heavily
     * loaded system will vary but the default is a good starting point.
     *
     * @param maxIdle
     *            The cap on the number of "idle" instances in the pool. Use a
     *            negative value to indicate an unlimited number of idle
     *            instances
     *
     * @see #getMaxIdle
     */
    public void setMaxIdle(int maxIdle) {
        this.maxIdle = maxIdle;
    }

    /**
     * Sets the target for the minimum number of idle objects to maintain in
     * the pool. This setting only has an effect if it is positive and
     * {@link #getTimeBetweenEvictionRunsMillis()} is greater than zero. If this
     * is the case, an attempt is made to ensure that the pool has the required
     * minimum number of instances during idle object eviction runs.
     * <p>
     * If the configured value of minIdle is greater than the configured value
     * for maxIdle then the value of maxIdle will be used instead.
     *
     * @param minIdle
     *            The minimum number of objects.
     *
     * @see #getMinIdle()
     * @see #getMaxIdle()
     * @see #getTimeBetweenEvictionRunsMillis()
     */
    public void setMinIdle(int minIdle) {
        this.minIdle = minIdle;
    }

    /**
     * 返回对象池中维护的空闲对象的最小数量目标。
     * <p>
     * 此设置仅会在{@link #getTimeBetweenEvictionRunsMillis()}的返回值大于0时，
     * 且该值是正整数时才会生效。
     * <p>
     * 默认是 {@code 0}，即对象池不维护空闲的池对象。
     * <p>
     * 
     * Returns the target for the minimum number of idle objects to maintain in
     * the pool. This setting only has an effect if it is positive and
     * {@link #getTimeBetweenEvictionRunsMillis()} is greater than zero. If this
     * is the case, an attempt is made to ensure that the pool has the required
     * minimum number of instances during idle object eviction runs.
     * <p>
     * If the configured value of minIdle is greater than the configured value
     * for maxIdle then the value of maxIdle will be used instead.
     *
     * @return The minimum number of objects. (空闲对象的最小数量)
     *
     * @see #setMinIdle(int)
     * @see #setMaxIdle(int)
     * @see #setTimeBetweenEvictionRunsMillis(long)
     */
    @Override
    public int getMinIdle() {
        int maxIdleSave = this.getMaxIdle();
        if (this.minIdle > maxIdleSave) {
            return maxIdleSave;
        } else {
            return minIdle;
        }
    }

    /**
     * Whether or not abandoned object removal is configured for this pool.
     *
     * @return true if this pool is configured to detect and remove
     * abandoned objects
     */
    @Override
    public boolean isAbandonedConfig() {
        return abandonedConfig != null;
    }

    /**
     * Will this pool identify and log any abandoned objects?
     *
     * @return {@code true} if abandoned object removal is configured for this
     *         pool and removal events are to be logged otherwise {@code false}
     *
     * @see AbandonedConfig#getLogAbandoned()
     */
    @Override
    public boolean getLogAbandoned() {
        AbandonedConfig ac = this.abandonedConfig;
        return ac != null && ac.getLogAbandoned();
    }

    /**
     * Will a check be made for abandoned objects when an object is borrowed
     * from this pool?
     *
     * @return {@code true} if abandoned object removal is configured to be
     *         activated by borrowObject otherwise {@code false}
     *
     * @see AbandonedConfig#getRemoveAbandonedOnBorrow()
     */
    @Override
    public boolean getRemoveAbandonedOnBorrow() {
        AbandonedConfig ac = this.abandonedConfig;
        return ac != null && ac.getRemoveAbandonedOnBorrow();
    }

    /**
     * Will a check be made for abandoned objects when the evictor runs?
     *
     * @return {@code true} if abandoned object removal is configured to be
     *         activated when the evictor runs otherwise {@code false}
     *
     * @see AbandonedConfig#getRemoveAbandonedOnMaintenance()
     */
    @Override
    public boolean getRemoveAbandonedOnMaintenance() {
        AbandonedConfig ac = this.abandonedConfig;
        return ac != null && ac.getRemoveAbandonedOnMaintenance();
    }

    /**
     * Obtain the timeout before which an object will be considered to be
     * abandoned by this pool.
     *
     * @return The abandoned object timeout in seconds if abandoned object
     *         removal is configured for this pool; Integer.MAX_VALUE otherwise.
     *
     * @see AbandonedConfig#getRemoveAbandonedTimeout()
     */
    @Override
    public int getRemoveAbandonedTimeout() {
        AbandonedConfig ac = this.abandonedConfig;
        return ac != null ? ac.getRemoveAbandonedTimeout() : Integer.MAX_VALUE;
    }


    /**
     * 设置基池配置。
     * <p>
     * Sets the base pool configuration.
     *
     * @param conf the new configuration to use. This is used by value.
     *
     * @see GenericObjectPoolConfig
     */
    public void setConfig(GenericObjectPoolConfig conf) {
        setLifo(conf.getLifo());
        setMaxIdle(conf.getMaxIdle());
        setMinIdle(conf.getMinIdle());
        setMaxTotal(conf.getMaxTotal());
        setMaxWaitMillis(conf.getMaxWaitMillis());
        setBlockWhenExhausted(conf.getBlockWhenExhausted());
        setTestOnCreate(conf.getTestOnCreate());
        setTestOnBorrow(conf.getTestOnBorrow());
        setTestOnReturn(conf.getTestOnReturn());
        setTestWhileIdle(conf.getTestWhileIdle());
        setNumTestsPerEvictionRun(conf.getNumTestsPerEvictionRun());
        setMinEvictableIdleTimeMillis(conf.getMinEvictableIdleTimeMillis());
        setTimeBetweenEvictionRunsMillis(
                conf.getTimeBetweenEvictionRunsMillis());
        setSoftMinEvictableIdleTimeMillis(
                conf.getSoftMinEvictableIdleTimeMillis());
        setEvictionPolicyClassName(conf.getEvictionPolicyClassName());
    }

    /**
     * 设置"被遗弃的对象移除"配置。
     * <p>
     * Sets the abandoned object removal configuration.
     *
     * @param abandonedConfig the new configuration to use. This is used by value.
     *
     * @see AbandonedConfig
     */
    public void setAbandonedConfig(AbandonedConfig abandonedConfig) throws IllegalArgumentException {
        if (abandonedConfig == null) {
            this.abandonedConfig = null;
        } else {
            this.abandonedConfig = new AbandonedConfig();
            this.abandonedConfig.setLogAbandoned(abandonedConfig.getLogAbandoned());
            this.abandonedConfig.setLogWriter(abandonedConfig.getLogWriter());
            this.abandonedConfig.setRemoveAbandonedOnBorrow(abandonedConfig.getRemoveAbandonedOnBorrow());
            this.abandonedConfig.setRemoveAbandonedOnMaintenance(abandonedConfig.getRemoveAbandonedOnMaintenance());
            this.abandonedConfig.setRemoveAbandonedTimeout(abandonedConfig.getRemoveAbandonedTimeout());
            this.abandonedConfig.setUseUsageTracking(abandonedConfig.getUseUsageTracking());
        }
    }

    /**
     * Obtain a reference to the factory used to create, destroy and validate
     * the objects used by this pool.
     *
     * @return the factory
     */
    public PooledObjectFactory<T> getFactory() {
        return factory;
    }

    /**
     * Equivalent to <code>{@link #borrowObject(long)
     * borrowObject}({@link #getMaxWaitMillis()})</code>.
     * <p>
     * {@inheritDoc}
     */
    @Override
    public T borrowObject() throws Exception {
        return borrowObject(getMaxWaitMillis());
    }

    /**
     * 使用指定的等待时间来向池借用一个对象。
     * <p>
     * 
     * Borrow an object from the pool using the specific waiting time which only
     * applies if {@link #getBlockWhenExhausted()} is true.
     * <p>
     * If there is one or more idle instance available in the pool, then an
     * idle instance will be selected based on the value of {@link #getLifo()},
     * activated and returned. If activation fails, or {@link #getTestOnBorrow()
     * testOnBorrow} is set to <code>true</code> and validation fails, the
     * instance is destroyed and the next available instance is examined. This
     * continues until either a valid instance is returned or there are no more
     * idle instances available.
     * <p>
     * If there are no idle instances available in the pool, behavior depends on
     * the {@link #getMaxTotal() maxTotal}, (if applicable)
     * {@link #getBlockWhenExhausted()} and the value passed in to the
     * <code>borrowMaxWaitMillis</code> parameter. If the number of instances
     * checked out from the pool is less than <code>maxTotal,</code> a new
     * instance is created, activated and (if applicable) validated and returned
     * to the caller. If validation fails, a <code>NoSuchElementException</code>
     * is thrown.
     * <p>
     * If the pool is exhausted (no available idle instances and no capacity to
     * create new ones), this method will either block (if
     * {@link #getBlockWhenExhausted()} is true) or throw a
     * <code>NoSuchElementException</code> (if
     * {@link #getBlockWhenExhausted()} is false). The length of time that this
     * method will block when {@link #getBlockWhenExhausted()} is true is
     * determined by the value passed in to the <code>borrowMaxWaitMillis</code>
     * parameter.
     * <p>
     * When the pool is exhausted, multiple calling threads may be
     * simultaneously blocked waiting for instances to become available. A
     * "fairness" algorithm has been implemented to ensure that threads receive
     * available instances in request arrival order.
     *
     * @param borrowMaxWaitMillis The time to wait in milliseconds for an object
     *                            to become available
     *
     * @return object instance from the pool
     *
     * @throws NoSuchElementException if an instance cannot be returned
     *
     * @throws Exception if an object instance cannot be returned due to an
     *                   error
     */
    public T borrowObject(long borrowMaxWaitMillis) throws Exception {
    	// 检验池打开着
        assertOpen();

        AbandonedConfig ac = this.abandonedConfig;
        if (ac != null && ac.getRemoveAbandonedOnBorrow() &&
                (getNumIdle() < 2) &&
                (getNumActive() > getMaxTotal() - 3) ) {
            removeAbandoned(ac);
        }

        PooledObject<T> p = null;

        // Get local copy of current config so it is consistent for entire
        // method execution
        boolean blockWhenExhausted = getBlockWhenExhausted();

        boolean create;
        long waitTime = 0;

        while (p == null) {
            create = false;
            if (blockWhenExhausted) { // 阻塞
            	// 1. 有空闲池对象吗
                p = idleObjects.pollFirst();
                if (p == null) {
                	// 2. 没有空闲池对象，创建一个新的池对象
                    create = true;
                    p = create();
                }
                if (p == null) { // 3. 无法创建一个新的池对象
                    if (borrowMaxWaitMillis < 0) { // 3.1 等待空闲池对象返回
                        p = idleObjects.takeFirst();
                    } else { // 3.2 在借用的最大等待时间里，是否有新的池对象被返回
                        waitTime = System.currentTimeMillis();
                        p = idleObjects.pollFirst(borrowMaxWaitMillis,
                                TimeUnit.MILLISECONDS);
                        waitTime = System.currentTimeMillis() - waitTime;
                    }
                }
                if (p == null) { // 4. 等待空闲池对象超时
                    throw new NoSuchElementException(
                            "Timeout waiting for idle object");
                }
                
                if (!p.allocate()) { // 5. 池对象是否处于"空闲(IDLE)"状态
                    p = null;
                }
            } else { // 非阻塞
            	// 1. 有空闲池对象吗
                p = idleObjects.pollFirst();
                if (p == null) {
                	// 2. 没有空闲池对象，创建一个新的池对象
                    create = true;
                    p = create();
                }
                if (p == null) { // 3. 池对象已被耗尽
                    throw new NoSuchElementException("Pool exhausted");
                }
                if (!p.allocate()) { // 4. 池对象是否处于"空闲(IDLE)"状态
                    p = null;
                }
            }

            if (p != null) { // 有可用的池对象
                try {
                	// 1. 重新初始化这个池对象
                    factory.activateObject(p);
                } catch (Exception e) {
                    try {
                    	// 初始化发生异常，销毁它
                        destroy(p);
                    } catch (Exception e1) {
                        // Ignore - activation failure is more important
                    }
                    p = null;
                    if (create) {
                    	// 无法激活这个新创建的池对象(当节点宕机时)
                        NoSuchElementException nsee = new NoSuchElementException(
                                "Unable to activate object");
                        nsee.initCause(e);
                        throw nsee;
                    }
                }
                if (p != null && (getTestOnBorrow() || create && getTestOnCreate())) { // 在借用、创建时校验池对象的有效性
                    boolean validate = false;
                    Throwable validationThrowable = null;
                    try {
                    	// 使用PooledObjectFactory.validateObject(PooledObject<T> p)来校验池对象的有效性
                        validate = factory.validateObject(p);
                    } catch (Throwable t) {
                        PoolUtils.checkRethrow(t);
                        validationThrowable = t;
                    }
                    if (!validate) { // 池对象有异常
                        try {
                        	// 校验发生异常，销毁它
                            destroy(p);
                            destroyedByBorrowValidationCount.incrementAndGet();
                        } catch (Exception e) {
                            // Ignore - validation failure is more important
                        }
                        p = null;
                        if (create) {
                        	// 无法激活这个新创建的池对象(当节点宕机时)
                            NoSuchElementException nsee = new NoSuchElementException(
                                    "Unable to validate object");
                            nsee.initCause(validationThrowable);
                            throw nsee;
                        }
                    }
                }
            }
        }

        updateStatsBorrow(p, waitTime);

        return p.getObject();
    }

    /**
     * {@inheritDoc}
     * <p>
     * If {@link #getMaxIdle() maxIdle} is set to a positive value and the
     * number of idle instances has reached this value, the returning instance
     * is destroyed.
     * <p>
     * If {@link #getTestOnReturn() testOnReturn} == true, the returning
     * instance is validated before being returned to the idle instance pool. In
     * this case, if validation fails, the instance is destroyed.
     * <p>
     * Exceptions encountered destroying objects for any reason are swallowed
     * but notified via a {@link SwallowedExceptionListener}.
     */
    @Override
    public void returnObject(T obj) {
        PooledObject<T> p = allObjects.get(obj);

        if (!isAbandonedConfig()) {
            if (p == null) {
                throw new IllegalStateException(
                        "Returned object not currently part of this pool");
            }
        } else {
            if (p == null) {
                return;  // Object was abandoned and removed
            } else {
                // Make sure object is not being reclaimed
                synchronized(p) {
                    final PooledObjectState state = p.getState();
                    if (state != PooledObjectState.ALLOCATED) {
                        throw new IllegalStateException(
                                "Object has already been retured to this pool or is invalid");
                    } else {
                        p.markReturning(); // Keep from being marked abandoned
                    }
                }
            }
        }

        long activeTime = p.getActiveTimeMillis();

        if (getTestOnReturn()) {
            if (!factory.validateObject(p)) {
                try {
                    destroy(p);
                } catch (Exception e) {
                    swallowException(e);
                }
                try {
                    ensureIdle(1, false);
                } catch (Exception e) {
                    swallowException(e);
                }
                updateStatsReturn(activeTime);
                return;
            }
        }

        try {
            factory.passivateObject(p);
        } catch (Exception e1) {
            swallowException(e1);
            try {
                destroy(p);
            } catch (Exception e) {
                swallowException(e);
            }
            try {
                ensureIdle(1, false);
            } catch (Exception e) {
                swallowException(e);
            }
            updateStatsReturn(activeTime);
            return;
        }

        if (!p.deallocate()) {
            throw new IllegalStateException(
                    "Object has already been retured to this pool or is invalid");
        }

        int maxIdleSave = getMaxIdle();
        if (isClosed() || maxIdleSave > -1 && maxIdleSave <= idleObjects.size()) {
            try {
                destroy(p);
            } catch (Exception e) {
                swallowException(e);
            }
        } else {
            if (getLifo()) {
                idleObjects.addFirst(p);
            } else {
                idleObjects.addLast(p);
            }
        }
        updateStatsReturn(activeTime);
    }

    /**
     * {@inheritDoc}
     * <p>
     * Activation of this method decrements the active count and attempts to
     * destroy the instance.
     *
     * @throws Exception             if an exception occurs destroying the
     *                               object
     * @throws IllegalStateException if obj does not belong to this pool
     */
    @Override
    public void invalidateObject(T obj) throws Exception {
        PooledObject<T> p = allObjects.get(obj);
        if (p == null) {
            if (this.isAbandonedConfig()) {
                return;
            } else {
                throw new IllegalStateException(
                        "Invalidated object not currently part of this pool");
            }
        }
        // 如果池对象不是无效的，则销毁它
        synchronized (p) {
            if (p.getState() != PooledObjectState.INVALID) {
                this.destroy(p);
            }
        }
        // 确保至少有一个空闲池对象
        ensureIdle(1, false);
    }

    /**
     * Clears any objects sitting idle in the pool by removing them from the
     * idle instance pool and then invoking the configured
     * {@link PooledObjectFactory#destroyObject(PooledObject)} method on each
     * idle instance.
     * <p>
     * Implementation notes:
     * <ul>
     * <li>This method does not destroy or effect in any way instances that are
     * checked out of the pool when it is invoked.</li>
     * <li>Invoking this method does not prevent objects being returned to the
     * idle instance pool, even during its execution. Additional instances may
     * be returned while removed items are being destroyed.</li>
     * <li>Exceptions encountered destroying idle instances are swallowed
     * but notified via a {@link SwallowedExceptionListener}.</li>
     * </ul>
     */
    @Override
    public void clear() {
        PooledObject<T> p = idleObjects.poll();

        while (p != null) {
            try {
                destroy(p);
            } catch (Exception e) {
                swallowException(e);
            }
            p = idleObjects.poll();
        }
    }

    @Override
    public int getNumActive() {
        return allObjects.size() - idleObjects.size();
    }

    @Override
    public int getNumIdle() {
        return idleObjects.size();
    }

    /**
     * Closes the pool. Once the pool is closed, {@link #borrowObject()} will
     * fail with IllegalStateException, but {@link #returnObject(Object)} and
     * {@link #invalidateObject(Object)} will continue to work, with returned
     * objects destroyed on return.
     * <p>
     * Destroys idle instances in the pool by invoking {@link #clear()}.
     */
    @Override
    public void close() {
        if (isClosed()) {
            return;
        }

        synchronized (closeLock) {
            if (isClosed()) {
                return;
            }

            // Stop the evictor before the pool is closed since evict() calls
            // assertOpen()
            startEvictor(-1L);

            closed = true;
            // This clear removes any idle objects
            clear();

            jmxUnregister();

            // Release any threads that were waiting for an object
            idleObjects.interuptTakeWaiters();
        }
    }

    /**
     * {@inheritDoc}
     * <p>
     * 按顺序对被审查的对象进行连续驱逐检测，对象是以"从最老到最年轻"的顺序循环。
     * Successive activations of this method examine objects in sequence,
     * cycling through objects in oldest-to-youngest order.
     */
    @Override
    public void evict() throws Exception {
    	// 1. 确保"对象池"还打开着
        assertOpen();

        // 2. 对所有空闲对象进行驱逐检测
        if (idleObjects.size() > 0) {

            PooledObject<T> underTest = null; // 测试中的池对象
            EvictionPolicy<T> evictionPolicy = this.getEvictionPolicy(); // 驱逐回收策略

            synchronized (evictionLock) { // 驱逐锁定
                EvictionConfig evictionConfig = new EvictionConfig(
                		this.getMinEvictableIdleTimeMillis(),
                		this.getSoftMinEvictableIdleTimeMillis(),
                		this.getMinIdle()
                		); // 驱逐配置

                boolean testWhileIdle = this.getTestWhileIdle(); // 是否要在空闲时测试有效性

                for (int i = 0, m = this.getNumTests(); i < m; i++) {
                    if (evictionIterator == null || !evictionIterator.hasNext()) { // 已对所有空闲对象完成一次遍历
                    	// 根据"对象池使用行为"赋值驱逐迭代器
                        if (this.getLifo()) {
                            evictionIterator = idleObjects.descendingIterator();
                        } else {
                            evictionIterator = idleObjects.iterator();
                        }
                    }
                    if (!evictionIterator.hasNext()) {
                        // Pool exhausted, nothing to do here (对象池被耗尽，无可用池对象)
                        return;
                    }

                    try {
                        underTest = evictionIterator.next();
                    } catch (NoSuchElementException nsee) {
                        // Object was borrowed in another thread (池对象被其它请求线程借用了)
                        // Don't count this as an eviction test so reduce i;
                        i--;
                        evictionIterator = null;
                        continue;
                    }

                    // 3. 将池对象标记为开始"驱逐"状态
                    if (!underTest.startEvictionTest()) {
                        // Object was borrowed in another thread
                        // Don't count this as an eviction test so reduce i;
                        i--;
                        continue;
                    }

                    // 4. 进行真正的驱逐校验操作
                    if (evictionPolicy.evict(evictionConfig, underTest,
                            idleObjects.size())) {
                    	// 如果池对象是可驱逐的，则销毁它
                    	this.destroy(underTest);
                        destroyedByEvictorCount.incrementAndGet();
                    } else {
                        if (testWhileIdle) { // 允许空闲时进行有效性测试
                        	// 5.1 先激活池对象
                            boolean active = false;
                            try {
                                factory.activateObject(underTest);
                                active = true;
                            } catch (Exception e) {
                            	this.destroy(underTest);
                                destroyedByEvictorCount.incrementAndGet();
                            }
                            // 5.2 使用PooledObjectFactory.validateObject(PooledObject)进行池对象的有效性校验
                            if (active) {
                                if (!factory.validateObject(underTest)) {
                                	// 如果池对象不是有效的，则销毁它
                                	this.destroy(underTest);
                                    destroyedByEvictorCount.incrementAndGet();
                                } else {
                                    try {
                                    	// 如果池对象有效，则还原状态
                                        factory.passivateObject(underTest);
                                    } catch (Exception e) {
                                    	this.destroy(underTest);
                                        destroyedByEvictorCount.incrementAndGet();
                                    }
                                }
                            }
                        }
                        // 6. 通知空闲对象队列，驱逐测试已经结束
                        if (!underTest.endEvictionTest(idleObjects)) {
                            // TODO - May need to add code here once additional
                            // states are used
                        }
                    }
                }
            }
        }
        // 7. 是否要"移除被废弃的池对象"
        AbandonedConfig ac = this.abandonedConfig;
        if (ac != null && ac.getRemoveAbandonedOnMaintenance()) {
        	this.removeAbandoned(ac);
        }
    }

    /**
     * 尝试着创建一个新的包装的池对象。
     * <p>
     * 
     * Attempts to create a new wrapped pooled object.
     * <p>
     * If there are {@link #getMaxTotal()} objects already in circulation
     * or in process of being created, this method returns null.
     *
     * @return The new wrapped pooled object
     *
     * @throws Exception if the object factory's {@code makeObject} fails
     */
    private PooledObject<T> create() throws Exception {
    	// 1. 对象池是否被耗尽判断
        int localMaxTotal = getMaxTotal();
        long newCreateCount = createCount.incrementAndGet();
        if (localMaxTotal > -1 && newCreateCount > localMaxTotal ||
                newCreateCount > Integer.MAX_VALUE) {
            createCount.decrementAndGet();
            return null; // 没有池对象可创建
        }

        final PooledObject<T> p;
        try {
        	// 2. 使用PooledObjectFactory.makeObject()来制造一个新的池对象
            p = factory.makeObject();
        } catch (Exception e) {
            createCount.decrementAndGet();
            throw e;
        }

        AbandonedConfig ac = this.abandonedConfig;
        if (ac != null && ac.getLogAbandoned()) {
            p.setLogAbandoned(true);
        }

        // Bug 创建计数器多加了1次吧？（待确认）
        createdCount.incrementAndGet();
        // 3. 将新创建的池对象追加到"池的所有对象映射表"中
        allObjects.put(p.getObject(), p);
        return p;
    }

    /**
     * 销毁一个包装的池对象。
     * <p>
     * 
     * Destroys a wrapped pooled object.
     *
     * @param toDestory The wrapped pooled object to destroy
     *
     * @throws Exception If the factory fails to destroy the pooled object
     *                   cleanly
     */
    private void destroy(PooledObject<T> toDestory) throws Exception {
    	// 1. 设置这个池对象的状态为"无效(INVALID)"
        toDestory.invalidate();
        // 2. 将这个池对象从空闲对象列表和所有对象列表中移除掉
        idleObjects.remove(toDestory);
        allObjects.remove(toDestory.getObject());
        try {
        	// 3. 使用PooledObjectFactory.destroyObject(PooledObject<T> p)来销毁这个不再需要的池对象
            factory.destroyObject(toDestory);
        } finally {
            destroyedCount.incrementAndGet();
            createCount.decrementAndGet();
        }
    }

    @Override
    void ensureMinIdle() throws Exception {
        ensureIdle(getMinIdle(), true);
    }

    /**
     * Tries to ensure that {@code idleCount} idle instances exist in the pool.
     * <p>
     * Creates and adds idle instances until either {@link #getNumIdle()} reaches {@code idleCount}
     * or the total number of objects (idle, checked out, or being created) reaches
     * {@link #getMaxTotal()}. If {@code always} is false, no instances are created unless
     * there are threads waiting to check out instances from the pool.
     *
     * @param idleCount the number of idle instances desired
     * @param always true means create instances even if the pool has no threads waiting
     * @throws Exception if the factory's makeObject throws
     */
    private void ensureIdle(int idleCount, boolean always) throws Exception {
        if (idleCount < 1 || this.isClosed() || (!always && !idleObjects.hasTakeWaiters())) {
            return;
        }

        while (idleObjects.size() < idleCount) {
            PooledObject<T> p = this.create();
            if (p == null) {
                // Can't create objects, no reason to think another call to
                // create will work. Give up.
                break;
            }
            if (getLifo()) {
                idleObjects.addFirst(p);
            } else {
                idleObjects.addLast(p);
            }
        }
    }

    /**
     * Create an object, and place it into the pool. addObject() is useful for
     * "pre-loading" a pool with idle objects.
     */
    @Override
    public void addObject() throws Exception {
        assertOpen();
        if (factory == null) {
            throw new IllegalStateException(
                    "Cannot add objects without a factory.");
        }
        PooledObject<T> p = create();
        addIdleObject(p);
    }

    /**
     * Add the provided wrapped pooled object to the set of idle objects for
     * this pool. The object must already be part of the pool.
     *
     * @param p The object to make idle
     *
     * @throws Exception If the factory fails to passivate the object
     */
    private void addIdleObject(PooledObject<T> p) throws Exception {
        if (p != null) {
            factory.passivateObject(p);
            if (getLifo()) {
                idleObjects.addFirst(p);
            } else {
                idleObjects.addLast(p);
            }
        }
    }

    /**
     * 计算空闲对象驱逐者一轮测试的对象数量。
     * <p>
     * Calculate the number of objects to test in a run of the idle object
     * evictor.
     *
     * @return The number of objects to test for validity (要测试其有效性的对象数量)
     */
    private int getNumTests() {
        int numTestsPerEvictionRun = this.getNumTestsPerEvictionRun();
        if (numTestsPerEvictionRun >= 0) {
            return Math.min(numTestsPerEvictionRun, idleObjects.size());
        } else {
            return (int) (Math.ceil(idleObjects.size() /
                    Math.abs((double) numTestsPerEvictionRun)));
        }
    }

    /**
     * 恢复被废弃的对象，它已被检测出超过{@code AbandonedConfig#getRemoveAbandonedTimeout() 
     * removeAbandonedTimeout}未被使用。
     * <p>
     * <font color="red">注意：需要考虑性能损耗，因为它会对对象池中的所有池对象进行检测！</font>
     * <p>
     * 
     * Recover abandoned objects which have been checked out but
     * not used since longer than the removeAbandonedTimeout.
     *
     * @param ac The configuration to use to identify abandoned objects
     */
    private void removeAbandoned(AbandonedConfig ac) {
        // 1. Generate a list of abandoned objects to remove (生成一个要被删除的被废弃的对象列表)
        final long now = System.currentTimeMillis();
        final long timeout =
                now - (ac.getRemoveAbandonedTimeout() * 1000L);
        List<PooledObject<T>> remove = new ArrayList<PooledObject<T>>();
        Iterator<PooledObject<T>> it = allObjects.values().iterator();
        while (it.hasNext()) {
            PooledObject<T> pooledObject = it.next();
            synchronized (pooledObject) {
            	// 从"所有池对象"中挑选出状态为"使用中"的池对象，且空闲时间已超过了"对象的移除超时时间"
                if (pooledObject.getState() == PooledObjectState.ALLOCATED &&
                        pooledObject.getLastUsedTime() <= timeout) {
                	// 标记池对象为"被废弃"状态，并添加到删除列表中
                    pooledObject.markAbandoned();
                    remove.add(pooledObject);
                }
            }
        }

        // 2. Now remove the abandoned objects (移除所有被废弃的对象)
        Iterator<PooledObject<T>> itr = remove.iterator();
        while (itr.hasNext()) {
            PooledObject<T> pooledObject = itr.next();
            if (ac.getLogAbandoned()) {
                pooledObject.printStackTrace(ac.getLogWriter());
            }
            try {
                this.invalidateObject(pooledObject.getObject());
            } catch (Exception e) {
                e.printStackTrace();
            }
        }
    }


    //--- Usage tracking support -----------------------------------------------

    @Override
    public void use(T pooledObject) {
        AbandonedConfig ac = this.abandonedConfig;
        if (ac != null && ac.getUseUsageTracking()) {
            PooledObject<T> wrapper = allObjects.get(pooledObject);
            wrapper.use();
        }
    }


    //--- JMX support ----------------------------------------------------------

    private volatile String factoryType = null;

    /**
     * Return an estimate of the number of threads currently blocked waiting for
     * an object from the pool. This is intended for monitoring only, not for
     * synchronization control.
     *
     * @return The estimate of the number of threads currently blocked waiting
     *         for an object from the pool
     */
    @Override
    public int getNumWaiters() {
        if (getBlockWhenExhausted()) {
            return idleObjects.getTakeQueueLength();
        } else {
            return 0;
        }
    }

    /**
     * Return the type - including the specific type rather than the generic -
     * of the factory.
     *
     * @return A string representation of the factory type
     */
    @Override
    public String getFactoryType() {
        // Not thread safe. Accept that there may be multiple evaluations.
        if (factoryType == null) {
            StringBuilder result = new StringBuilder();
            result.append(factory.getClass().getName());
            result.append('<');
            Class<?> pooledObjectType =
                    PoolImplUtils.getFactoryType(factory.getClass());
            result.append(pooledObjectType.getName());
            result.append('>');
            factoryType = result.toString();
        }
        return factoryType;
    }

    /**
     * Provides information on all the objects in the pool, both idle (waiting
     * to be borrowed) and active (currently borrowed).
     * <p>
     * Note: This is named listAllObjects so it is presented as an operation via
     * JMX. That means it won't be invoked unless the explicitly requested
     * whereas all attributes will be automatically requested when viewing the
     * attributes for an object in a tool like JConsole.
     *
     * @return Information grouped on all the objects in the pool
     */
    @Override
    public Set<DefaultPooledObjectInfo> listAllObjects() {
        Set<DefaultPooledObjectInfo> result =
                new HashSet<DefaultPooledObjectInfo>(allObjects.size());
        for (PooledObject<T> p : allObjects.values()) {
            result.add(new DefaultPooledObjectInfo(p));
        }
        return result;
    }

    // --- configuration attributes --------------------------------------------

    private volatile int maxIdle = GenericObjectPoolConfig.DEFAULT_MAX_IDLE;
    private volatile int minIdle = GenericObjectPoolConfig.DEFAULT_MIN_IDLE;
    private final PooledObjectFactory<T> factory;


    // --- internal attributes (内部属性) -------------------------------------------------

    /*
     * All of the objects currently associated with this pool in any state. It
     * excludes objects that have been destroyed. The size of
     * {@link #allObjects} will always be less than or equal to {@link
     * #_maxActive}. Map keys are pooled objects, values are the PooledObject
     * wrappers used internally by the pool.
     */
    private final ConcurrentMap<T, PooledObject<T>> allObjects =
        new ConcurrentHashMap<T, PooledObject<T>>();
    /*
     * The combined count of the currently created objects and those in the
     * process of being created. Under load, it may exceed {@link #_maxActive}
     * if multiple threads try and create a new object at the same time but
     * {@link #create()} will ensure that there are never more than
     * {@link #_maxActive} objects created at any one time.
     */
    private final AtomicLong createCount = new AtomicLong(0);
    /** 池的空闲池对象列表 */
    private final LinkedBlockingDeque<PooledObject<T>> idleObjects =
        new LinkedBlockingDeque<PooledObject<T>>();

    // JMX specific attributes
    private static final String ONAME_BASE =
        "org.apache.commons.pool2:type=GenericObjectPool,name=";

    // Additional configuration properties for abandoned object tracking
    private volatile AbandonedConfig abandonedConfig = null;
}
