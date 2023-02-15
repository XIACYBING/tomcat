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


package org.apache.catalina;

import org.apache.catalina.core.ApplicationFilterFactory;
import org.apache.catalina.core.StandardContext;
import org.apache.catalina.core.StandardEngine;
import org.apache.catalina.core.StandardHost;
import org.apache.catalina.core.StandardWrapper;

import java.util.Set;

/**
 * <p>Interface describing a collection of Valves that should be executed
 * in sequence when the <code>invoke()</code> method is invoked.  It is
 * required that a Valve somewhere in the pipeline (usually the last one)
 * must process the request and create the corresponding response, rather
 * than trying to pass the request on.</p>
 *
 * <p>There is generally a single Pipeline instance associated with each
 * Container.  The container's normal request processing functionality is
 * generally encapsulated in a container-specific Valve, which should always
 * be executed at the end of a pipeline.  To facilitate this, the
 * <code>setBasic()</code> method is provided to set the Valve instance that
 * will always be executed last.  Other Valves will be executed in the order
 * that they were added, before the basic Valve is executed.</p>
 *
 * Pipeline主要用于管理{@link Valve}的执行顺序，要求某一个{@link Valve}生成相应的响应信息；
 *
 * 每个容器{@link Container}都会有一个{@link Pipeline}，用于管理一个集合的{@link Valve}，容器的请求处理能力一般封装在{@link Valve}中
 * 一般是最后一个，通过{@link Pipeline#setBasic}设置到最后一个
 *
 * 容器一般在构造器中将拥有处理能力的{@link Valve}设置到{@link Pipeline}的{@link Pipeline#getBasic()}位置
 *
 * {@link Pipeline}在{@link org.apache.catalina.connector.CoyoteAdapter#service}中被调用，用来处理请求，在各层级（{@link StandardHost}、
 * {@link StandardContext}、{@link StandardWrapper}）容器的{@link Valve}处理完成后，在最后的
 * {@link org.apache.catalina.core.StandardWrapperValve}，会通过{@link ApplicationFilterFactory#createFilterChain}
 * 构造一条过滤器链，过滤完成后进行{@link javax.servlet.Servlet#service}的处理
 *
 * @see StandardEngine#StandardEngine()
 * @see org.apache.catalina.core.StandardEngineValve
 * @see StandardHost#StandardHost() 
 * @see org.apache.catalina.core.StandardHostValve
 * @see StandardContext#StandardContext()
 * @see org.apache.catalina.core.StandardContextValve
 * @see StandardWrapper#StandardWrapper()
 * @see org.apache.catalina.core.StandardWrapperValve
 *
 * @author Craig R. McClanahan
 * @author Peter Donald
 */
public interface Pipeline {


    // ------------------------------------------------------------- Properties


    /**
     * @return the Valve instance that has been distinguished as the basic
     * Valve for this Pipeline (if any).
     */
    public Valve getBasic();


    /**
     * <p>Set the Valve instance that has been distinguished as the basic
     * Valve for this Pipeline (if any).  Prior to setting the basic Valve,
     * the Valve's <code>setContainer()</code> will be called, if it
     * implements <code>Contained</code>, with the owning Container as an
     * argument.  The method may throw an <code>IllegalArgumentException</code>
     * if this Valve chooses not to be associated with this Container, or
     * <code>IllegalStateException</code> if it is already associated with
     * a different Container.</p>
     *
     * @param valve Valve to be distinguished as the basic Valve
     */
    public void setBasic(Valve valve);


    // --------------------------------------------------------- Public Methods


    /**
     * <p>Add a new Valve to the end of the pipeline associated with this
     * Container.  Prior to adding the Valve, the Valve's
     * <code>setContainer()</code> method will be called, if it implements
     * <code>Contained</code>, with the owning Container as an argument.
     * The method may throw an
     * <code>IllegalArgumentException</code> if this Valve chooses not to
     * be associated with this Container, or <code>IllegalStateException</code>
     * if it is already associated with a different Container.</p>
     *
     * <p>Implementation note: Implementations are expected to trigger the
     * {@link Container#ADD_VALVE_EVENT} for the associated container if this
     * call is successful.</p>
     *
     * @param valve Valve to be added
     *
     * @exception IllegalArgumentException if this Container refused to
     *  accept the specified Valve
     * @exception IllegalArgumentException if the specified Valve refuses to be
     *  associated with this Container
     * @exception IllegalStateException if the specified Valve is already
     *  associated with a different Container
     */
    public void addValve(Valve valve);


    /**
     * @return the set of Valves in the pipeline associated with this
     * Container, including the basic Valve (if any).  If there are no
     * such Valves, a zero-length array is returned.
     */
    public Valve[] getValves();


    /**
     * Remove the specified Valve from the pipeline associated with this
     * Container, if it is found; otherwise, do nothing.  If the Valve is
     * found and removed, the Valve's <code>setContainer(null)</code> method
     * will be called if it implements <code>Contained</code>.
     *
     * <p>Implementation note: Implementations are expected to trigger the
     * {@link Container#REMOVE_VALVE_EVENT} for the associated container if this
     * call is successful.</p>
     *
     * @param valve Valve to be removed
     */
    public void removeValve(Valve valve);


    /**
     * @return the Valve instance that has been distinguished as the basic
     * Valve for this Pipeline (if any).
     */
    public Valve getFirst();

    /**
     * Returns true if all the valves in this pipeline support async, false otherwise
     * @return true if all the valves in this pipeline support async, false otherwise
     */
    public boolean isAsyncSupported();


    /**
     * @return the Container with which this Pipeline is associated.
     */
    public Container getContainer();


    /**
     * Set the Container with which this Pipeline is associated.
     *
     * @param container The new associated container
     */
    public void setContainer(Container container);


    /**
     * Identifies the Valves, if any, in this Pipeline that do not support
     * async.
     *
     * @param result The Set to which the fully qualified class names of each
     *               Valve in this Pipeline that does not support async will be
     *               added
     */
    public void findNonAsyncValves(Set<String> result);
}
