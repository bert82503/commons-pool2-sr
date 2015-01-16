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

import org.apache.commons.pool2.PooledObject;

/**
 * 提供用在对象池的"驱逐回收策略"的默认实现，继承自{@link EvictionPolicy}。
 * <p>
 * 如果满足以下条件，对象将被驱逐：
 * <ul>
 * <li>池对象的空闲时间超过{@link GenericObjectPool#getMinEvictableIdleTimeMillis()}
 * <li>对象池中的空闲对象数超过{@link GenericObjectPool#getMinIdle()}，且池对象的空闲时间超过{@link GenericObjectPool#getSoftMinEvictableIdleTimeMillis()}
 * </ul>
 * <font color="red">此类是不可变的，且是线程安全的。</font>
 * <p>
 * 
 * Provides the default implementation of {@link EvictionPolicy} used by the
 * pools. Objects will be evicted if the following conditions are met:
 * <ul>
 * <li>the object has been idle longer than
 *     {@link GenericObjectPool#getMinEvictableIdleTimeMillis()} /
 *     {@link GenericKeyedObjectPool#getMinEvictableIdleTimeMillis()}</li>
 * <li>there are more than {@link GenericObjectPool#getMinIdle()} /
 *     {@link GenericKeyedObjectPoolConfig#getMinIdlePerKey()} idle objects in
 *     the pool and the object has been idle for longer than
 *     {@link GenericObjectPool#getSoftMinEvictableIdleTimeMillis()} /
 *     {@link GenericKeyedObjectPool#getSoftMinEvictableIdleTimeMillis()}
 * </ul>
 * This class is immutable and thread-safe.
 *
 * @param <T> the type of objects in the pool (对象池中对象的类型)
 *
 * @version $Revision: $
 *
 * @since 2.0
 */
public class DefaultEvictionPolicy<T> implements EvictionPolicy<T> {

    /**
     * 如果对象池中的空闲对象是否应该被驱逐，调用此方法来测试。（驱逐行为实现）
     * <p>
     * 如果满足以下条件，对象将被驱逐：
	 * <ul>
	 * <li>池对象的空闲时间超过{@link GenericObjectPool#getMinEvictableIdleTimeMillis()}
	 * <li>对象池中的空闲对象数超过{@link GenericObjectPool#getMinIdle()}，且池对象的空闲时间超过{@link GenericObjectPool#getSoftMinEvictableIdleTimeMillis()}
	 * </ul>
     */
    @Override
    public boolean evict(EvictionConfig config, PooledObject<T> underTest,
            int idleCount) {

    	if ((idleCount > config.getMinIdle() && 
    			underTest.getIdleTimeMillis() > config.getIdleSoftEvictTime()) 
    			|| underTest.getIdleTimeMillis() > config.getIdleEvictTime()) {
            return true;
        }
        return false;
    }

}
