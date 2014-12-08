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
package org.apache.commons.pool2;

/**
 * 一个"定义对象池中实例的生命周期方法"的接口。
 * <p>
 * <pre>
 * 池对象工厂的职责：
 *    1. 每当需要一个新的实例时，{@link #makeObject()}会被调用。
 *    2. 在每个实例从对象池被借走之前，{@link #activateObject(PooledObject)}会被调用。
 *    3. {@link #validateObject(PooledObject)}可能会在激活({@link #activateObject(PooledObject)})的实例上被调用，
 *       以确保他们可以从对象池借用。{@link #validateObject(PooledObject)}也可以用于测试一个返回给对象池的实例。
 *    4. 在每个实例返回到对象池时，{@link #passivateObject(PooledObject)}会被调用。
 *    5. 在每个实例从对象池被移除时，{@link #destroyObject(PooledObject)}会被调用
 *      （是否由于{@link #validateObject(PooledObject)}的响应信息）。
 * </pre>
 * {@link PooledObjectFactory}必须是线程安全的。
 * <font color="red">唯一保证：</font>一个对象池制造的相同的对象实例不会在同一时刻被传入到多个方法中。
 * <p>
 * 
 * An interface defining life-cycle methods for instances to be served by an
 * {@link ObjectPool}.
 * <p>
 * By contract, when an {@link ObjectPool} delegates to a
 * {@link PooledObjectFactory},
 * <ol>
 *  <li>
 *   {@link #makeObject} is called whenever a new instance is needed.
 *  </li>
 *  <li>
 *   {@link #activateObject} is invoked on every instance that has been
 *   {@link #passivateObject passivated} before it is
 *   {@link ObjectPool#borrowObject borrowed} from the pool.
 *  </li>
 *  <li>
 *   {@link #validateObject} may be invoked on {@link #activateObject activated}
 *   instances to make sure they can be {@link ObjectPool#borrowObject borrowed}
 *   from the pool. {@link #validateObject} may also be used to
 *   test an instance being {@link ObjectPool#returnObject returned} to the pool
 *   before it is {@link #passivateObject passivated}. It will only be invoked
 *   on an activated instance.
 *  </li>
 *  <li>
 *   {@link #passivateObject} is invoked on every instance when it is returned
 *   to the pool.
 *  </li>
 *  <li>
 *   {@link #destroyObject} is invoked on every instance when it is being
 *   "dropped" from the pool (whether due to the response from
 *   {@link #validateObject}, or for reasons specific to the pool
 *   implementation.) There is no guarantee that the instance being destroyed
 *   will be considered active, passive or in a generally consistent state.
 *  </li>
 * </ol>
 * {@link PooledObjectFactory} must be thread-safe. The only promise
 * an {@link ObjectPool} makes is that the same instance of an object will not
 * be passed to more than one method of a <code>PoolableObjectFactory</code>
 * at a time.
 * <p>
 * While clients of a {@link KeyedObjectPool} borrow and return instances of
 * the underlying value type {@code V}, the factory methods act on instances of
 * {@link PooledObject PooledObject&lt;V&gt;}.  These are the object wrappers that
 * pools use to track and maintain state information about the objects that
 * they manage.
 *
 * @param <T> Type of element managed in this factory.
 *
 * @see ObjectPool
 *
 * @version $Revision: 1333925 $
 *
 * @since 2.0
 */
public interface PooledObjectFactory<T> {

  /**
   * 创建一个能被保存到对象池的实例，并将它包装成一个池对象({@link PooledObject})，
   * 以便由对象池管理。
   * <p>
   * Create an instance that can be served by the pool and wrap it in a
   * {@link PooledObject} to be managed by the pool.
   *
   * @return a {@code PooledObject} wrapping an instance that can be served by the pool
   *
   * @throws Exception if there is a problem creating a new instance,
   *    this will be propagated to the code requesting an object.
   */
  PooledObject<T> makeObject() throws Exception;

  /**
   * 销毁对象池中不再需要的实例。
   * <p>
   * <font color="red">必须加以考虑：</font>没有被垃圾收集器回收的实例，可能永远不会被销毁！
   * <p>
   * Destroys an instance no longer needed by the pool.
   * <p>
   * It is important for implementations of this method to be aware that there
   * is no guarantee about what state <code>obj</code> will be in and the
   * implementation should be prepared to handle unexpected errors.
   * <p>
   * Also, an implementation must take in to consideration that instances lost
   * to the garbage collector may never be destroyed.
   * </p>
   *
   * @param p a {@code PooledObject} wrapping the instance to be destroyed
   *
   * @throws Exception should be avoided as it may be swallowed by
   *    the pool implementation.
   *
   * @see #validateObject
   * @see ObjectPool#invalidateObject
   */
  void destroyObject(PooledObject<T> p) throws Exception;

  /**
   * 确保可以返回给"对象池"的池对象实例是安全的。
   * <p>
   * Ensures that the instance is safe to be returned by the pool.
   *
   * @param p a {@code PooledObject} wrapping the instance to be validated
   *
   * @return <code>false</code> if <code>obj</code> is not valid and should
   *         be dropped from the pool, <code>true</code> otherwise.
   *         (如果对象不是有效的，同时应该从对象池中驱除，则返回 false；否则，返回 true。)
   */
  boolean validateObject(PooledObject<T> p);

  /**
   * 重新初始化可以返回给"对象池"的池对象。
   * <p>
   * Reinitialize an instance to be returned by the pool.
   *
   * @param p a {@code PooledObject} wrapping the instance to be activated
   *
   * @throws Exception if there is a problem activating <code>obj</code>,
   *    this exception may be swallowed by the pool.
   *
   * @see #destroyObject
   */
  void activateObject(PooledObject<T> p) throws Exception;

  /**
   * 未初始化可以返回给"空闲对象池"的池对象。
   * <p>
   * Uninitialize an instance to be returned to the idle object pool.
   *
   * @param p a {@code PooledObject} wrapping the instance to be passivated
   *
   * @throws Exception if there is a problem passivating <code>obj</code>,
   *    this exception may be swallowed by the pool.
   *
   * @see #destroyObject
   */
  void passivateObject(PooledObject<T> p) throws Exception;

}
