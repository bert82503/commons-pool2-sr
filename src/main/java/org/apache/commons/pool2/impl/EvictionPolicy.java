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
 * 为了提供对象池的一个自定义"驱逐回收策略"，使用者必须提供该接口的一个实现(如{@link DefaultEvictionPolicy})。
 * <p>
 * To provide a custom eviction policy (i.e. something other than {@link
 * DefaultEvictionPolicy} for a pool, users must provide an implementation of
 * this interface that provides the required eviction policy.
 *
 * @param <T> the type of objects in the pool (池对象的类型)
 *  
 * @version $Revision: $
 *
 * @since 2.0
 */
public interface EvictionPolicy<T> {

    /**
     * 如果对象池中的空闲对象是否应该被驱逐，调用此方法来测试。（驱逐行为实现）
     * <p>
     * This method is called to test if an idle object in the pool should be
     * evicted or not.
     *
     * @param config    The pool configuration settings related to eviction (与驱逐相关的对象池配置设置)
     * @param underTest The pooled object being tested for eviction (正在被驱逐测试的池对象)
     * @param idleCount The current number of idle objects in the pool including
     *                      the object under test (当前对象池中的空闲对象数，包括测试中的对象)
     * @return <code>true</code> if the object should be evicted, otherwise
     *             <code>false</code> (如果池对象应该被驱逐掉，就返回{@code true}；否则，返回{@code false}。)
     */
    boolean evict(EvictionConfig config, PooledObject<T> underTest,
            int idleCount);
}
