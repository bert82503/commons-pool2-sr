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

/**
 * 驱逐配置，用于将"对象池"实现类的配置信息传递给"驱逐策略({@link EvictionPolicy})"实例。
 * <p>
 * <font color="red">此类是不可变和线程安全的。</font>
 * <p>
 * 
 * This class is used by pool implementations to pass configuration information
 * to {@link EvictionPolicy} instances. The {@link EvictionPolicy} may also have
 * its own specific configuration attributes.
 * <p>
 * This class is immutable and thread-safe.
 *
 * @version $Revision: $
 *
 * @since 2.0
 */
public class EvictionConfig {

	// final 字段修饰保证其不可变性
	/** 池对象的最大空闲驱逐时间（当池对象的空闲时间超过该值时，立马被强制驱逐掉） */
    private final long idleEvictTime;
    /** 池对象的最小空闲驱逐时间（当池对象的空闲时间超过该值时，被纳入驱逐对象列表里） */
    private final long idleSoftEvictTime;
    /** 对象池的最小空闲池对象数量 */
    private final int minIdle;


    /**
     * 创建一个新的"驱逐回收策略"配置实例。
     * <p>
     * <font color="red">实例是不可变的。</font>
     * <p>
     * Create a new eviction configuration with the specified parameters.
     * Instances are immutable.
     *
     * @param poolIdleEvictTime Expected to be provided by (池对象的最大空闲驱逐时间(ms))
     *        {@link BaseGenericObjectPool#getMinEvictableIdleTimeMillis()}
     * @param poolIdleSoftEvictTime Expected to be provided by (池对象的最小空闲驱逐时间(ms))
     *        {@link BaseGenericObjectPool#getSoftMinEvictableIdleTimeMillis()}
     * @param minIdle Expected to be provided by (对象池的最小空闲池对象数量)
     *        {@link GenericObjectPool#getMinIdle()} or
     *        {@link GenericKeyedObjectPool#getMinIdlePerKey()}
     */
    public EvictionConfig(long poolIdleEvictTime, long poolIdleSoftEvictTime,
            int minIdle) {
        if (poolIdleEvictTime > 0) {
            idleEvictTime = poolIdleEvictTime;
        } else {
            idleEvictTime = Long.MAX_VALUE;
        }
        if (poolIdleSoftEvictTime > 0) {
            idleSoftEvictTime = poolIdleSoftEvictTime;
        } else {
            idleSoftEvictTime  = Long.MAX_VALUE;
        }
        this.minIdle = minIdle;
    }

    /**
     * 获取"池对象的最大空闲驱逐时间(ms)"。
     * <p>
     * 当池对象的空闲时间超过该值时，立马被强制驱逐掉。
     * <p>
     * Obtain the {@code idleEvictTime} for this eviction configuration
     * instance.
     * <p>
     * How the evictor behaves based on this value will be determined by the
     * configured {@link EvictionPolicy}.
     *
     * @return The {@code idleEvictTime} in milliseconds
     */
    public long getIdleEvictTime() {
        return idleEvictTime;
    }

    /**
     * 获取"池对象的最小空闲驱逐时间(ms)"。
     * <p>
     * 当池对象的空闲时间超过该值时，被纳入驱逐对象列表里。
     * <p>
     * Obtain the {@code idleSoftEvictTime} for this eviction configuration
     * instance.
     * <p>
     * How the evictor behaves based on this value will be determined by the
     * configured {@link EvictionPolicy}.
     *
     * @return The (@code idleSoftEvictTime} in milliseconds
     */
    public long getIdleSoftEvictTime() {
        return idleSoftEvictTime;
    }

    /**
     * 获取"对象池的最小空闲池对象数量"。
     * <p>
     * Obtain the {@code minIdle} for this eviction configuration instance.
     * <p>
     * How the evictor behaves based on this value will be determined by the
     * configured {@link EvictionPolicy}.
     *
     * @return The {@code minIdle}
     */
    public int getMinIdle() {
        return minIdle;
    }

}
