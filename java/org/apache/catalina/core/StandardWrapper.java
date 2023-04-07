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


package org.apache.catalina.core;

import java.io.PrintStream;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Stack;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.ReentrantReadWriteLock;

import javax.management.ListenerNotFoundException;
import javax.management.MBeanNotificationInfo;
import javax.management.Notification;
import javax.management.NotificationBroadcasterSupport;
import javax.management.NotificationEmitter;
import javax.management.NotificationFilter;
import javax.management.NotificationListener;
import javax.management.ObjectName;
import javax.servlet.MultipartConfigElement;
import javax.servlet.Servlet;
import javax.servlet.ServletConfig;
import javax.servlet.ServletContext;
import javax.servlet.ServletException;
import javax.servlet.ServletSecurityElement;
import javax.servlet.SingleThreadModel;
import javax.servlet.UnavailableException;
import javax.servlet.annotation.MultipartConfig;
import javax.servlet.annotation.ServletSecurity;

import org.apache.catalina.Container;
import org.apache.catalina.ContainerServlet;
import org.apache.catalina.Context;
import org.apache.catalina.Globals;
import org.apache.catalina.LifecycleException;
import org.apache.catalina.LifecycleState;
import org.apache.catalina.Wrapper;
import org.apache.catalina.security.SecurityUtil;
import org.apache.juli.logging.Log;
import org.apache.juli.logging.LogFactory;
import org.apache.tomcat.InstanceManager;
import org.apache.tomcat.PeriodicEventListener;
import org.apache.tomcat.util.ExceptionUtils;
import org.apache.tomcat.util.log.SystemLogHandler;
import org.apache.tomcat.util.modeler.Registry;
import org.apache.tomcat.util.modeler.Util;

/**
 * Standard implementation of the <b>Wrapper</b> interface that represents
 * an individual servlet definition.  No child Containers are allowed, and
 * the parent Container must be a Context.
 *
 * @author Craig R. McClanahan
 * @author Remy Maucherat
 */
@SuppressWarnings("deprecation") // SingleThreadModel
public class StandardWrapper extends ContainerBase
    implements ServletConfig, Wrapper, NotificationEmitter {

    private static final Log log = LogFactory.getLog(StandardWrapper.class);

    protected static final String[] DEFAULT_SERVLET_METHODS = new String[] {
                                                    "GET", "HEAD", "POST" };

    // ----------------------------------------------------------- Constructors


    /**
     * Create a new StandardWrapper component with the default basic Valve.
     */
    public StandardWrapper() {

        super();

        // 设置Valve
        swValve=new StandardWrapperValve();
        pipeline.setBasic(swValve);

        // 设置通知广播器
        broadcaster = new NotificationBroadcasterSupport();

    }


    // ----------------------------------------------------- Instance Variables


    /**
     * The date and time at which this servlet will become available (in
     * milliseconds since the epoch), or zero if the servlet is available.
     * If this value equals Long.MAX_VALUE, the unavailability of this
     * servlet is considered permanent.
     *
     * 当前Wrapper对应的Servlet可用的毫秒数，有以下三种情况：
     * 1、如果available为0，说明当前Servlet有效；
     * 2、如果available等于Long.MAX_VALUE，说明当前Servlet永久不可用
     * 3、如果对应的是一个时间的毫秒数，说明当前Servlet还未生效，要等到对应的时间后才生效，这时候外部的处理是将请求返回，并将当前时间戳设置为响应头Retry-After的值，告诉外部当前Servlet
     * 什么时候有效，外部应该什么时候再来请求
     */
    protected long available = 0L;

    /**
     * The broadcaster that sends j2ee notifications.
     */
    protected final NotificationBroadcasterSupport broadcaster;

    /**
     * The count of allocations that are currently active (even if they
     * are for the same instance, as will be true on a non-STM servlet).
     *
     * Servlet实例被分配过多少次，更准确来说，Servlet被外部调用多少次
     *
     * 对于STM的Servlet，当前参数代表有多少Servlet正在被使用；
     * 对于非STM的Servlet，当前参数可以代表Servlet正在被多少请求并发访问
     */
    protected final AtomicInteger countAllocated = new AtomicInteger(0);


    /**
     * The facade associated with this wrapper.
     */
    protected final StandardWrapperFacade facade = new StandardWrapperFacade(this);


    /**
     * The (single) possibly uninitialized instance of this servlet.
     *
     * Servlet实例：非STM模式下，记录对应的单例Servlet；STM模式下，一般记录的是第一个Servlet
     */
    protected volatile Servlet instance = null;


    /**
     * Flag that indicates if this instance has been initialized
     *
     * Servlet实例是否已经初始化完成
     */
    protected volatile boolean instanceInitialized = false;


    /**
     * The load-on-startup order value (negative value means load on
     * first call) for this servlet.
     */
    protected int loadOnStartup = -1;


    /**
     * Mappings associated with the wrapper.
     *
     * 当前Servlet支持的url集合
     */
    protected final ArrayList<String> mappings = new ArrayList<>();


    /**
     * The initialization parameters for this servlet, keyed by
     * parameter name.
     */
    protected HashMap<String, String> parameters = new HashMap<>();


    /**
     * The security role references for this servlet, keyed by role name
     * used in the servlet.  The corresponding value is the role name of
     * the web application itself.
     */
    protected HashMap<String, String> references = new HashMap<>();


    /**
     * The run-as identity for this servlet.
     */
    protected String runAs = null;

    /**
     * The notification sequence number.
     */
    protected long sequenceNumber = 0;

    /**
     * The fully qualified servlet class name for this servlet.
     */
    protected String servletClass = null;


    /**
     * Does this servlet implement the SingleThreadModel interface?
     *
     * STM：single thread model
     * 代表一个Servlet只能同时被一个线程访问，基于此，需要{@link #instancePool}缓存并提供多个Servlet，对外提供服务
     *
     * 当前属性最开始时为false，只有在加载对应的Servlet时，通过判断Servlet是否{@link SingleThreadModel}的实现，从而设置{@link #singleThreadModel}和
     * {@link #instancePool}
     */
    protected volatile boolean singleThreadModel = false;


    /**
     * Are we unloading our servlet instance at the moment?
     */
    protected volatile boolean unloading = false;


    /**
     * Maximum number of STM instances.
     *
     * STM的Servlet实例的最大数量
     */
    protected int maxInstances = 20;


    /**
     * Number of instances currently loaded for a STM servlet.
     *
     * 当前已加载多少STM的Servlet实例
     */
    protected int nInstances = 0;


    /**
     * Stack containing the STM instances.
     *
     * 存储STM的Servlet实例的集合
     */
    protected Stack<Servlet> instancePool = null;


    /**
     * Wait time for servlet unload in ms.
     *
     * 当进行卸载Servlet时，可能有其他请求正在使用当前Servlet，这时候需要循环等待其他线程使用完成
     *
     * 循环等待时间：单位为毫秒
     */
    protected long unloadDelay = 2000;


    /**
     * True if this StandardWrapper is for the JspServlet
     */
    protected boolean isJspServlet;


    /**
     * The ObjectName of the JSP monitoring mbean
     */
    protected ObjectName jspMonitorON;


    /**
     * Should we swallow System.out
     */
    protected boolean swallowOutput = false;

    // To support jmx attributes
    protected StandardWrapperValve swValve;
    protected long loadTime=0;
    protected int classLoadTime=0;

    /**
     * Multipart config
     */
    protected MultipartConfigElement multipartConfigElement = null;

    /**
     * Async support
     */
    protected boolean asyncSupported = false;

    /**
     * Enabled
     */
    protected boolean enabled = true;

    protected volatile boolean servletSecurityAnnotationScanRequired = false;

    private boolean overridable = false;

    /**
     * Static class array used when the SecurityManager is turned on and
     * <code>Servlet.init</code> is invoked.
     */
    protected static Class<?>[] classType = new Class[]{ServletConfig.class};

    private final ReentrantReadWriteLock parametersLock =
            new ReentrantReadWriteLock();

    private final ReentrantReadWriteLock mappingsLock =
            new ReentrantReadWriteLock();

    private final ReentrantReadWriteLock referencesLock =
            new ReentrantReadWriteLock();


    // ------------------------------------------------------------- Properties

    @Override
    public boolean isOverridable() {
        return overridable;
    }

    @Override
    public void setOverridable(boolean overridable) {
        this.overridable = overridable;
    }

    /**
     * Return the available date/time for this servlet, in milliseconds since
     * the epoch.  If this date/time is Long.MAX_VALUE, it is considered to mean
     * that unavailability is permanent and any request for this servlet will return
     * an SC_NOT_FOUND error.  If this date/time is in the future, any request for
     * this servlet will return an SC_SERVICE_UNAVAILABLE error.  If it is zero,
     * the servlet is currently available.
     */
    @Override
    public long getAvailable() {

        return (this.available);

    }


    /**
     * Set the available date/time for this servlet, in milliseconds since the
     * epoch.  If this date/time is Long.MAX_VALUE, it is considered to mean
     * that unavailability is permanent and any request for this servlet will return
     * an SC_NOT_FOUND error. If this date/time is in the future, any request for
     * this servlet will return an SC_SERVICE_UNAVAILABLE error.
     *
     * @param available The new available date/time
     */
    @Override
    public void setAvailable(long available) {

        long oldAvailable = this.available;

        // 如果当前available已经大于当前时间，说明Servlet还不可用，将入参的available设置到属性上即可
        if (available > System.currentTimeMillis()) {
            this.available = available;
        } else {

            // 否则说明Servlet已经可用，直接设置available属性为0即可，
            this.available = 0L;
        }

        // 发布available属性变化事件
        support.firePropertyChange("available", Long.valueOf(oldAvailable),
                                   Long.valueOf(this.available));
    }


    /**
     * @return the number of active allocations of this servlet, even if they
     * are all for the same instance (as will be true for servlets that do
     * not implement <code>SingleThreadModel</code>.
     */
    public int getCountAllocated() {
        return this.countAllocated.get();
    }


    /**
     * @return the load-on-startup order value (negative value means
     * load on first call).
     */
    @Override
    public int getLoadOnStartup() {

        if (isJspServlet && loadOnStartup < 0) {
            /*
             * JspServlet must always be preloaded, because its instance is
             * used during registerJMX (when registering the JSP
             * monitoring mbean)
             */
             return Integer.MAX_VALUE;
        } else {
            return (this.loadOnStartup);
        }
    }


    /**
     * Set the load-on-startup order value (negative value means
     * load on first call).
     *
     * @param value New load-on-startup value
     */
    @Override
    public void setLoadOnStartup(int value) {

        int oldLoadOnStartup = this.loadOnStartup;
        this.loadOnStartup = value;
        support.firePropertyChange("loadOnStartup",
                                   Integer.valueOf(oldLoadOnStartup),
                                   Integer.valueOf(this.loadOnStartup));

    }



    /**
     * Set the load-on-startup order value from a (possibly null) string.
     * Per the specification, any missing or non-numeric value is converted
     * to a zero, so that this servlet will still be loaded at startup
     * time, but in an arbitrary order.
     *
     * @param value New load-on-startup value
     */
    public void setLoadOnStartupString(String value) {

        try {
            setLoadOnStartup(Integer.parseInt(value));
        } catch (NumberFormatException e) {
            setLoadOnStartup(0);
        }
    }

    /**
     * @return the load-on-startup value that was parsed
     */
    public String getLoadOnStartupString() {
        return Integer.toString( getLoadOnStartup());
    }


    /**
     * @return maximum number of instances that will be allocated when a single
     * thread model servlet is used.
     */
    public int getMaxInstances() {

        return (this.maxInstances);

    }


    /**
     * Set the maximum number of instances that will be allocated when a single
     * thread model servlet is used.
     *
     * @param maxInstances New value of maxInstances
     */
    public void setMaxInstances(int maxInstances) {

        int oldMaxInstances = this.maxInstances;
        this.maxInstances = maxInstances;
        support.firePropertyChange("maxInstances", oldMaxInstances,
                                   this.maxInstances);

    }


    /**
     * Set the parent Container of this Wrapper, but only if it is a Context.
     *
     * @param container Proposed parent Container
     */
    @Override
    public void setParent(Container container) {

        // 父容器要求非空，且必须是Context
        if ((container != null) &&
            !(container instanceof Context)) {
            throw new IllegalArgumentException
                (sm.getString("standardWrapper.notContext"));
        }

        // 如果是StandardContext，继承swallowOutput和unloadDelay属性
        if (container instanceof StandardContext) {
            swallowOutput = ((StandardContext)container).getSwallowOutput();
            unloadDelay = ((StandardContext)container).getUnloadDelay();
        }

        // 调用父类方法，设置父容器
        super.setParent(container);
    }


    /**
     * @return the run-as identity for this servlet.
     */
    @Override
    public String getRunAs() {

        return (this.runAs);

    }


    /**
     * Set the run-as identity for this servlet.
     *
     * @param runAs New run-as identity value
     */
    @Override
    public void setRunAs(String runAs) {

        String oldRunAs = this.runAs;
        this.runAs = runAs;
        support.firePropertyChange("runAs", oldRunAs, this.runAs);

    }


    /**
     * @return the fully qualified servlet class name for this servlet.
     */
    @Override
    public String getServletClass() {

        return (this.servletClass);

    }


    /**
     * Set the fully qualified servlet class name for this servlet.
     *
     * @param servletClass Servlet class name
     */
    @Override
    public void setServletClass(String servletClass) {

        String oldServletClass = this.servletClass;
        this.servletClass = servletClass;
        support.firePropertyChange("servletClass", oldServletClass,
                                   this.servletClass);
        if (Constants.JSP_SERVLET_CLASS.equals(servletClass)) {
            isJspServlet = true;
        }
    }



    /**
     * Set the name of this servlet.  This is an alias for the normal
     * <code>Container.setName()</code> method, and complements the
     * <code>getServletName()</code> method required by the
     * <code>ServletConfig</code> interface.
     *
     * @param name The new name of this servlet
     */
    public void setServletName(String name) {

        setName(name);

    }


    /**
     * Does the servlet class represented by this component implement the
     * <code>SingleThreadModel</code> interface? This can only be determined
     * once the class is loaded. Calling this method will not trigger loading
     * the class since that may cause the application to behave unexpectedly.
     *
     * @return {@code null} if the class has not been loaded, otherwise {@code
     *         true} if the servlet does implement {@code SingleThreadModel} and
     *         {@code false} if it does not.
     */
    public Boolean isSingleThreadModel() {
        // If the servlet has been loaded either singleThreadModel will be true
        // or instance will be non-null
        if (singleThreadModel || instance != null) {
            return Boolean.valueOf(singleThreadModel);
        }
        return null;
    }


    /**
     * @return <code>true</code> if the Servlet has been marked unavailable.
     */
    @Override
    public boolean isUnavailable() {

        if (!isEnabled()) {
            return true;
        } else if (available == 0L) {
            return false;
        }

        // 如果当前available小于等于当前时间戳，说明当前Wrapper应该生效
        else if (available <= System.currentTimeMillis()) {

            // 更新available为0L，代表当前Wrapper开始有效了
            available = 0L;
            return false;
        } else {
            return true;
        }

    }

    /**
     * 获取当前Servlet支持的Http请求方法
     */
    @Override
    public String[] getServletMethods() throws ServletException {

        instance = loadServlet();

        Class<? extends Servlet> servletClazz = instance.getClass();
        if (!javax.servlet.http.HttpServlet.class.isAssignableFrom(
                                                        servletClazz)) {
            return DEFAULT_SERVLET_METHODS;
        }

        HashSet<String> allow = new HashSet<>();
        allow.add("TRACE");
        allow.add("OPTIONS");

        // 通过servletClazz和父容器已声明的方法，判断当前Servlet支持哪几种Http请求方法
        Method[] methods = getAllDeclaredMethods(servletClazz);
        for (int i=0; methods != null && i<methods.length; i++) {
            Method m = methods[i];

            if (m.getName().equals("doGet")) {
                allow.add("GET");
                allow.add("HEAD");
            } else if (m.getName().equals("doPost")) {
                allow.add("POST");
            } else if (m.getName().equals("doPut")) {
                allow.add("PUT");
            } else if (m.getName().equals("doDelete")) {
                allow.add("DELETE");
            }
        }

        String[] methodNames = new String[allow.size()];
        return allow.toArray(methodNames);

    }


    /**
     * @return the associated servlet instance.
     */
    @Override
    public Servlet getServlet() {
        return instance;
    }


    /**
     * Set the associated servlet instance.
     */
    @Override
    public void setServlet(Servlet servlet) {
        instance = servlet;
    }


    /**
     * {@inheritDoc}
     */
    @Override
    public void setServletSecurityAnnotationScanRequired(boolean b) {
        this.servletSecurityAnnotationScanRequired = b;
    }

    // --------------------------------------------------------- Public Methods


    /**
     * Execute a periodic task, such as reloading, etc. This method will be
     * invoked inside the classloading context of this container. Unexpected
     * throwables will be caught and logged.
     *
     * 后台事务处理：一般由{@link StandardEngine}启动一个守护线程，守护线程处理{@link StandardEngine}容器及其子容器的后台事务
     */
    @Override
    public void backgroundProcess() {

        // 调用父类的后台事务处理方法
        super.backgroundProcess();

        if (!getState().isAvailable()) {
            return;
        }

        // 发布周期事件
        if (getServlet() instanceof PeriodicEventListener) {
            ((PeriodicEventListener) getServlet()).periodicEvent();
        }
    }


    /**
     * Extract the root cause from a servlet exception.
     *
     * @param e The servlet exception
     * @return the root cause of the Servlet exception
     */
    public static Throwable getRootCause(ServletException e) {
        Throwable rootCause = e;
        Throwable rootCauseCheck = null;
        // Extra aggressive rootCause finding
        int loops = 0;
        do {
            loops++;
            rootCauseCheck = rootCause.getCause();
            if (rootCauseCheck != null)
                rootCause = rootCauseCheck;
        } while (rootCauseCheck != null && (loops < 20));
        return rootCause;
    }


    /**
     * Refuse to add a child Container, because Wrappers are the lowest level
     * of the Container hierarchy.
     *
     * @param child Child container to be added
     */
    @Override
    public void addChild(Container child) {

        throw new IllegalStateException
            (sm.getString("standardWrapper.notChild"));

    }


    /**
     * Add a new servlet initialization parameter for this servlet.
     *
     * @param name Name of this initialization parameter to add
     * @param value Value of this initialization parameter to add
     */
    @Override
    public void addInitParameter(String name, String value) {

        parametersLock.writeLock().lock();
        try {
            parameters.put(name, value);
        } finally {
            parametersLock.writeLock().unlock();
        }
        fireContainerEvent("addInitParameter", name);

    }


    /**
     * Add a mapping associated with the Wrapper.
     *
     * @param mapping The new wrapper mapping
     */
    @Override
    public void addMapping(String mapping) {

        // 加锁并添加url的映射
        mappingsLock.writeLock().lock();
        try {
            mappings.add(mapping);
        } finally {
            mappingsLock.writeLock().unlock();
        }

        // StandardContext容器状态正常的话，发布ADD_MAPPING_EVENT
        if(parent.getState().equals(LifecycleState.STARTED)) {
            fireContainerEvent(ADD_MAPPING_EVENT, mapping);
        }

    }


    /**
     * Add a new security role reference record to the set of records for
     * this servlet.
     *
     * @param name Role name used within this servlet
     * @param link Role name used within the web application
     */
    @Override
    public void addSecurityReference(String name, String link) {

        referencesLock.writeLock().lock();
        try {
            references.put(name, link);
        } finally {
            referencesLock.writeLock().unlock();
        }
        fireContainerEvent("addSecurityReference", name);

    }


    /**
     * Allocate an initialized instance of this Servlet that is ready to have
     * its <code>service()</code> method called.  If the servlet class does
     * not implement <code>SingleThreadModel</code>, the (only) initialized
     * instance may be returned immediately.  If the servlet class implements
     * <code>SingleThreadModel</code>, the Wrapper implementation must ensure
     * that this instance is not allocated again until it is deallocated by a
     * call to <code>deallocate()</code>.
     *
     * @exception ServletException if the servlet init() method threw
     *  an exception
     * @exception ServletException if a loading error occurs
     */
    @Override
    public Servlet allocate() throws ServletException {

        // 状态检查
        // If we are currently unloading this servlet, throw an exception
        if (unloading) {
            throw new ServletException(sm.getString("standardWrapper.unloading", getName()));
        }

        boolean newInstance = false;

        // 如果不是STM模式，可以直接返回同一个Servlet实例，那么要做的就是检查当前是否有Servlet实例，没有的话加载并返回
        //
        // If not SingleThreadedModel, return the same instance every time
        if (!singleThreadModel) {

            // Double-Check-Lock：避免无效的Servlet初始化
            // Load and initialize our instance if necessary
            if (instance == null || !instanceInitialized) {
                synchronized (this) {
                    if (instance == null) {
                        try {
                            if (log.isDebugEnabled()) {
                                log.debug("Allocating non-STM instance");
                            }

                            // 只有当加载了Servlet后，我们才知道它是否继承了SingleThreadModel
                            // Note: We don't know if the Servlet implements
                            // SingleThreadModel until we have loaded it.

                            // 加载Servlet：实例化并完成相关配置
                            instance = loadServlet();
                            newInstance = true;

                            // 非STM模式，countAllocated自增，避免并发操作导致unload异常
                            if (!singleThreadModel) {
                                // For non-STM, increment here to prevent a race
                                // condition with unload. Bug 43683, test case
                                // #3
                                countAllocated.incrementAndGet();
                            }
                        } catch (ServletException e) {
                            throw e;
                        } catch (Throwable e) {
                            ExceptionUtils.handleThrowable(e);
                            throw new ServletException(sm.getString("standardWrapper.allocate"), e);
                        }
                    }

                    // 初始化Servlet
                    if (!instanceInitialized) {
                        initServlet(instance);
                    }
                }
            }

            // 如果一开始非STM模式，但是在运行过程中发现Servlet为SingleModelServlet，则会修改singleThreadModel
            // 为true，所以此处需要再进行一次判断
            if (singleThreadModel) {
                if (newInstance) {
                    // Have to do this outside of the sync above to prevent a
                    // possible deadlock
                    synchronized (instancePool) {
                        instancePool.push(instance);
                        nInstances++;
                    }
                }
            }

            // 非STM模式，则可以将Servlet实例直接返回给外部使用
            else {
                if (log.isTraceEnabled()) {
                    log.trace("  Returning non-STM instance");
                }
                // For new instances, count will have been incremented at the
                // time of creation
                if (!newInstance) {
                    countAllocated.incrementAndGet();
                }
                return instance;
            }
        }

        // 到此处，一般就代表当前是STM模式

        // 对instancePool加锁，避免多线程冲突
        synchronized (instancePool) {

            // 如果当前正在正在被外部使用的Servlet数量大于已有的Servlet数量，说明当前没有空闲的Servlet可以分配出去，需要进行增加或等待
            while (countAllocated.get() >= nInstances) {

                // 如果当前Servlet数量小于最大可加载的Servlet数量，则代表当前可以再加载Servlet
                // Allocate a new instance if possible, or else wait
                if (nInstances < maxInstances) {
                    try {

                        // 加载Servlet，并自增nInstances
                        instancePool.push(loadServlet());
                        nInstances++;
                    } catch (ServletException e) {
                        throw e;
                    } catch (Throwable e) {
                        ExceptionUtils.handleThrowable(e);
                        throw new ServletException(sm.getString("standardWrapper.allocate"), e);
                    }
                }

                // 如果当前Servlet大于等于最大可加载Servlet数量，则代表当前无可用Servlet，调用wait方法阻塞线程/请求，直到有其他请求完成，释放Servlet
                else {
                    try {
                        instancePool.wait();
                    } catch (InterruptedException e) {
                        // Ignore
                    }
                }
            }
            if (log.isTraceEnabled()) {
                log.trace("  Returning allocated STM instance");
            }

            // 到此处，说明instancePool中有可用的Servlet可以分配出去，所以自增countAllocated
            countAllocated.incrementAndGet();

            // 获取当前有效的Servlet实例并返回
            return instancePool.pop();
        }
    }


    /**
     * Return this previously allocated servlet to the pool of available
     * instances.  If this servlet class does not implement SingleThreadModel,
     * no action is actually required.
     *
     * @param servlet The servlet to be returned
     *
     * @exception ServletException if a deallocation error occurs
     */
    @Override
    public void deallocate(Servlet servlet) throws ServletException {

        // 非STM模式，直接将countAllocated参数减一即可
        // If not SingleThreadModel, no action is required
        if (!singleThreadModel) {
            countAllocated.decrementAndGet();
            return;
        }

        // STM模式，为了避免并发问题，需要先加锁
        // Unlock and free this instance
        synchronized (instancePool) {

            // 减少当前正在使用的Servlet数量计数
            countAllocated.decrementAndGet();

            // 将当前用完的Servlet实例，重新放回instancePool/实例池
            instancePool.push(servlet);

            // notify instancePool，通知那些因为没有足够Servlet实例而进入wait状态的请求/线程苏醒，重新获取Servlet
            instancePool.notify();
        }

    }


    /**
     * Return the value for the specified initialization parameter name,
     * if any; otherwise return <code>null</code>.
     *
     * @param name Name of the requested initialization parameter
     */
    @Override
    public String findInitParameter(String name) {

        parametersLock.readLock().lock();
        try {
            return parameters.get(name);
        } finally {
            parametersLock.readLock().unlock();
        }

    }


    /**
     * Return the names of all defined initialization parameters for this
     * servlet.
     */
    @Override
    public String[] findInitParameters() {

        parametersLock.readLock().lock();
        try {
            String results[] = new String[parameters.size()];
            return parameters.keySet().toArray(results);
        } finally {
            parametersLock.readLock().unlock();
        }

    }


    /**
     * Return the mappings associated with this wrapper.
     */
    @Override
    public String[] findMappings() {

        mappingsLock.readLock().lock();
        try {
            return mappings.toArray(new String[mappings.size()]);
        } finally {
            mappingsLock.readLock().unlock();
        }

    }


    /**
     * Return the security role link for the specified security role
     * reference name, if any; otherwise return <code>null</code>.
     *
     * @param name Security role reference used within this servlet
     */
    @Override
    public String findSecurityReference(String name) {

        referencesLock.readLock().lock();
        try {
            return references.get(name);
        } finally {
            referencesLock.readLock().unlock();
        }

    }


    /**
     * Return the set of security role reference names associated with
     * this servlet, if any; otherwise return a zero-length array.
     */
    @Override
    public String[] findSecurityReferences() {

        referencesLock.readLock().lock();
        try {
            String results[] = new String[references.size()];
            return references.keySet().toArray(results);
        } finally {
            referencesLock.readLock().unlock();
        }

    }


    /**
     * Load and initialize an instance of this servlet, if there is not already
     * at least one initialized instance.  This can be used, for example, to
     * load servlets that are marked in the deployment descriptor to be loaded
     * at server startup time.
     * <p>
     * <b>IMPLEMENTATION NOTE</b>:  Servlets whose classnames begin with
     * <code>org.apache.catalina.</code> (so-called "container" servlets)
     * are loaded by the same classloader that loaded this class, rather than
     * the classloader for the current web application.
     * This gives such classes access to Catalina internals, which are
     * prevented for classes loaded for web applications.
     *
     * @exception ServletException if the servlet init() method threw
     *  an exception
     * @exception ServletException if some other loading problem occurs
     */
    @Override
    public synchronized void load() throws ServletException {

        // 加载Servlet实例
        instance = loadServlet();

        // 初始化Servlet实例
        if (!instanceInitialized) {
            initServlet(instance);
        }

        // JSP的支持
        if (isJspServlet) {
            StringBuilder oname = new StringBuilder(getDomain());

            oname.append(":type=JspMonitor");

            oname.append(getWebModuleKeyProperties());

            oname.append(",name=");
            oname.append(getName());

            oname.append(getJ2EEKeyProperties());

            try {
                jspMonitorON = new ObjectName(oname.toString());
                Registry.getRegistry(null, null)
                    .registerComponent(instance, jspMonitorON, null);
            } catch( Exception ex ) {
                log.info("Error registering JSP monitoring with jmx " +
                         instance);
            }
        }
    }


    /**
     * Load and initialize an instance of this servlet, if there is not already
     * at least one initialized instance.  This can be used, for example, to
     * load servlets that are marked in the deployment descriptor to be loaded
     * at server startup time.
     * @return the loaded Servlet instance
     * @throws ServletException for a Servlet load error
     */
    public synchronized Servlet loadServlet() throws ServletException {

        // 如果非STM模式，且实例当前不为空，直接返回
        // Nothing to do if we already have an instance or an instance pool
        if (!singleThreadModel && (instance != null)) {
            return instance;
        }

        PrintStream out = System.out;
        if (swallowOutput) {
            SystemLogHandler.startCapture();
        }

        Servlet servlet;
        try {
            long t1=System.currentTimeMillis();

            // Servlet的Class为空，当前无法加载，设置当前Wrapper的available为无效
            // Complain if no servlet class has been specified
            if (servletClass == null) {
                unavailable(null);
                throw new ServletException
                    (sm.getString("standardWrapper.notClass", getName()));
            }

            // 获取父容器（StandardContext）的实例管理器
            InstanceManager instanceManager = ((StandardContext)getParent()).getInstanceManager();
            try {

                // 通过InstanceManager，实例化Servlet
                servlet = (Servlet) instanceManager.newInstance(servletClass);
            } catch (ClassCastException e) {
                unavailable(null);
                // Restore the context ClassLoader
                throw new ServletException
                    (sm.getString("standardWrapper.notServlet", servletClass), e);
            } catch (Throwable e) {
                e = ExceptionUtils.unwrapInvocationTargetException(e);
                ExceptionUtils.handleThrowable(e);
                unavailable(null);

                // Added extra log statement for Bugzilla 36630:
                // http://bz.apache.org/bugzilla/show_bug.cgi?id=36630
                if(log.isDebugEnabled()) {
                    log.debug(sm.getString("standardWrapper.instantiate", servletClass), e);
                }

                // Restore the context ClassLoader
                throw new ServletException
                    (sm.getString("standardWrapper.instantiate", servletClass), e);
            }

            // MultipartConfig注解的处理
            if (multipartConfigElement == null) {
                MultipartConfig annotation =
                        servlet.getClass().getAnnotation(MultipartConfig.class);
                if (annotation != null) {
                    multipartConfigElement =
                            new MultipartConfigElement(annotation);
                }
            }

            // 处理Servlet的ServletSecurity注解
            processServletSecurityAnnotation(servlet.getClass());

            // ContainerServlet关联Wrapper容器
            // Special handling for ContainerServlet instances
            // Note: The InstanceManager checks if the application is permitted
            //       to load ContainerServlets
            if (servlet instanceof ContainerServlet) {
                ((ContainerServlet) servlet).setWrapper(this);
            }

            classLoadTime=(int) (System.currentTimeMillis() -t1);

            // 如果当前Servlet是SingleThreadModel的实现，则说明当前Servlet应该属于STM模式
            if (servlet instanceof SingleThreadModel) {

                // 初始化实例池
                if (instancePool == null) {
                    instancePool = new Stack<>();
                }

                // 设置STM的flag为true
                singleThreadModel = true;
            }

            // 初始化Servlet：其实就是调用Servlet的init(ServletConfig)方法
            initServlet(servlet);

            // 发布容器事件：wrapper加载Servlet
            fireContainerEvent("load", this);

            loadTime=System.currentTimeMillis() -t1;
        } finally {
            if (swallowOutput) {
                String log = SystemLogHandler.stopCapture();
                if (log != null && log.length() > 0) {
                    if (getServletContext() != null) {
                        getServletContext().log(log);
                    } else {
                        out.println(log);
                    }
                }
            }
        }
        return servlet;

    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void servletSecurityAnnotationScan() throws ServletException {
        if (getServlet() == null) {
            Class<?> clazz = null;
            try {
                clazz = ((Context) getParent()).getLoader().getClassLoader().loadClass(
                        getServletClass());
                processServletSecurityAnnotation(clazz);
            } catch (ClassNotFoundException e) {
                // Safe to ignore. No class means no annotations to process
            }
        } else {
            if (servletSecurityAnnotationScanRequired) {
                processServletSecurityAnnotation(getServlet().getClass());
            }
        }
    }

    private void processServletSecurityAnnotation(Class<?> clazz) {
        // Calling this twice isn't harmful so no syncs
        servletSecurityAnnotationScanRequired = false;

        Context ctxt = (Context) getParent();

        if (ctxt.getIgnoreAnnotations()) {
            return;
        }

        ServletSecurity secAnnotation =
            clazz.getAnnotation(ServletSecurity.class);
        if (secAnnotation != null) {
            ctxt.addServletSecurity(
                    new ApplicationServletRegistration(this, ctxt),
                    new ServletSecurityElement(secAnnotation));
        }
    }

    private synchronized void initServlet(Servlet servlet)
            throws ServletException {

        // 如果已初始化且非STM模式，不处理
        if (instanceInitialized && !singleThreadModel) {
            return;
        }

        // Call the initialization method of this servlet
        try {

            // 调用Servlet的init方法，设置ServletConfig
            // GenericServlet的init(ServletConfig)方法中，处理设置ServletConfig外，还会调用自身的init无参方法
            if( Globals.IS_SECURITY_ENABLED) {
                boolean success = false;
                try {
                    Object[] args = new Object[] { facade };
                    SecurityUtil.doAsPrivilege("init",
                                               servlet,
                                               classType,
                                               args);
                    success = true;
                } finally {
                    if (!success) {
                        // destroy() will not be called, thus clear the reference now
                        SecurityUtil.remove(servlet);
                    }
                }
            } else {
                servlet.init(facade);
            }

            // 设置instanceInitialized标识，说明实例初始化完成
            instanceInitialized = true;
        } catch (UnavailableException f) {
            unavailable(f);
            throw f;
        } catch (ServletException f) {
            // If the servlet wanted to be unavailable it would have
            // said so, so do not call unavailable(null).
            throw f;
        } catch (Throwable f) {
            ExceptionUtils.handleThrowable(f);
            getServletContext().log("StandardWrapper.Throwable", f );
            // If the servlet wanted to be unavailable it would have
            // said so, so do not call unavailable(null).
            throw new ServletException
                (sm.getString("standardWrapper.initException", getName()), f);
        }
    }

    /**
     * Remove the specified initialization parameter from this servlet.
     *
     * @param name Name of the initialization parameter to remove
     */
    @Override
    public void removeInitParameter(String name) {

        parametersLock.writeLock().lock();
        try {
            parameters.remove(name);
        } finally {
            parametersLock.writeLock().unlock();
        }
        fireContainerEvent("removeInitParameter", name);

    }


    /**
     * Remove a mapping associated with the wrapper.
     *
     * @param mapping The pattern to remove
     */
    @Override
    public void removeMapping(String mapping) {

        mappingsLock.writeLock().lock();
        try {
            mappings.remove(mapping);
        } finally {
            mappingsLock.writeLock().unlock();
        }
        if(parent.getState().equals(LifecycleState.STARTED))
            fireContainerEvent(REMOVE_MAPPING_EVENT, mapping);

    }


    /**
     * Remove any security role reference for the specified role name.
     *
     * @param name Security role used within this servlet to be removed
     */
    @Override
    public void removeSecurityReference(String name) {

        referencesLock.writeLock().lock();
        try {
            references.remove(name);
        } finally {
            referencesLock.writeLock().unlock();
        }
        fireContainerEvent("removeSecurityReference", name);

    }


    /**
     * Process an UnavailableException, marking this servlet as unavailable
     * for the specified amount of time.
     *
     * @param unavailable The exception that occurred, or <code>null</code>
     *  to mark this servlet as permanently unavailable
     */
    @Override
    public void unavailable(UnavailableException unavailable) {
        getServletContext().log(sm.getString("standardWrapper.unavailable", getName()));
        if (unavailable == null) {
            setAvailable(Long.MAX_VALUE);
        } else if (unavailable.isPermanent()) {
            setAvailable(Long.MAX_VALUE);
        } else {
            int unavailableSeconds = unavailable.getUnavailableSeconds();
            if (unavailableSeconds <= 0) {
                unavailableSeconds = 60;        // Arbitrary default
            }
            setAvailable(System.currentTimeMillis() +
                         (unavailableSeconds * 1000L));
        }

    }


    /**
     * 卸载所有已加载的Servlet实例：在整个Servlet引擎关闭前调用，或在重加载Servlet时调用
     *
     * Unload all initialized instances of this servlet, after calling the
     * <code>destroy()</code> method for each instance.  This can be used,
     * for example, prior to shutting down the entire servlet engine, or
     * prior to reloading all of the classes from the Loader associated with
     * our Loader's repository.
     *
     * @exception ServletException if an exception is thrown by the
     *  destroy() method
     */
    @Override
    public synchronized void unload() throws ServletException {

        // 非STM模式，且Servlet实例为空，无需unload，直接返回
        // Nothing to do if we have never loaded the instance
        if (!singleThreadModel && (instance == null)) {
            return;
        }

        // 标记正在unload
        unloading = true;

        // 如果当前正在有其他请求正在使用当前Servlet，则不断循环等待
        // Loaf a while if the current instance is allocated
        // (possibly more than once if non-STM)
        if (countAllocated.get() > 0) {

            // 重试次数
            int nRetries = 0;

            // 卸载每次等待的毫秒数
            // 默认循环20次
            long delay = unloadDelay / 20;

            // 重试
            while ((nRetries < 21) && (countAllocated.get() > 0)) {

                // 每十次输出一条日志
                if ((nRetries % 10) == 0) {
                    log.info(sm.getString("standardWrapper.waiting",
                                          countAllocated.toString(),
                                          getName()));
                }

                // 休眠等待
                try {
                    Thread.sleep(delay);
                } catch (InterruptedException e) {
                    // Ignore
                }

                // 重试次数自增
                nRetries++;
            }
        }

        // 如果初始化完成，相应的，需要调用Servlet的destroy方法
        if (instanceInitialized) {
            PrintStream out = System.out;
            if (swallowOutput) {
                SystemLogHandler.startCapture();
            }

            // Call the servlet destroy() method
            try {
                if( Globals.IS_SECURITY_ENABLED) {
                    try {
                        SecurityUtil.doAsPrivilege("destroy", instance);
                    } finally {
                        SecurityUtil.remove(instance);
                    }
                } else {
                    instance.destroy();
                }

            } catch (Throwable t) {
                t = ExceptionUtils.unwrapInvocationTargetException(t);
                ExceptionUtils.handleThrowable(t);
                instance = null;
                instancePool = null;
                nInstances = 0;
                fireContainerEvent("unload", this);
                unloading = false;
                throw new ServletException
                    (sm.getString("standardWrapper.destroyException", getName()),
                     t);
            } finally {

                // 通过InstanceManager销毁Servlet实例：其实就是处理类似@PreDestroy注解的方法
                // Annotation processing
                if (!((Context) getParent()).getIgnoreAnnotations()) {
                    try {
                        ((Context)getParent()).getInstanceManager().destroyInstance(instance);
                    } catch (Throwable t) {
                        ExceptionUtils.handleThrowable(t);
                        log.error(sm.getString("standardWrapper.destroyInstance", getName()), t);
                    }
                }
                // Write captured output
                if (swallowOutput) {
                    String log = SystemLogHandler.stopCapture();
                    if (log != null && log.length() > 0) {
                        if (getServletContext() != null) {
                            getServletContext().log(log);
                        } else {
                            out.println(log);
                        }
                    }
                }
            }
        }

        // Deregister the destroyed instance
        instance = null;
        instanceInitialized = false;

        if (isJspServlet && jspMonitorON != null ) {
            Registry.getRegistry(null, null).unregisterComponent(jspMonitorON);
        }

        // 如果是STM模式，且instancePool不为空，需要调用instancePool中每个Servlet实例的destroy方法 todo 此处的逻辑不会导致instance的destroy方法被调用两次吗
        if (singleThreadModel && (instancePool != null)) {
            try {
                while (!instancePool.isEmpty()) {
                    Servlet s = instancePool.pop();
                    if (Globals.IS_SECURITY_ENABLED) {
                        try {
                            SecurityUtil.doAsPrivilege("destroy", s);
                        } finally {
                            SecurityUtil.remove(s);
                        }
                    } else {
                        s.destroy();
                    }

                    // 通过InstanceManager销毁Servlet实例：其实就是处理类似@PreDestroy注解的方法
                    // Annotation processing
                    if (!((Context) getParent()).getIgnoreAnnotations()) {
                       ((StandardContext)getParent()).getInstanceManager().destroyInstance(s);
                    }
                }
            } catch (Throwable t) {
                t = ExceptionUtils.unwrapInvocationTargetException(t);
                ExceptionUtils.handleThrowable(t);
                instancePool = null;
                nInstances = 0;
                unloading = false;
                fireContainerEvent("unload", this);
                throw new ServletException
                    (sm.getString("standardWrapper.destroyException",
                                  getName()), t);
            }
            instancePool = null;
            nInstances = 0;
        }

        // 设置STM模式为false
        singleThreadModel = false;

        // 设置unloading为false
        unloading = false;

        // 发布unload事件
        fireContainerEvent("unload", this);

    }


    // -------------------------------------------------- ServletConfig Methods


    /**
     * @return the initialization parameter value for the specified name,
     * if any; otherwise return <code>null</code>.
     *
     * @param name Name of the initialization parameter to retrieve
     */
    @Override
    public String getInitParameter(String name) {

        return (findInitParameter(name));

    }


    /**
     * @return the set of initialization parameter names defined for this
     * servlet.  If none are defined, an empty Enumeration is returned.
     */
    @Override
    public Enumeration<String> getInitParameterNames() {

        parametersLock.readLock().lock();
        try {
            return Collections.enumeration(parameters.keySet());
        } finally {
            parametersLock.readLock().unlock();
        }

    }


    /**
     * @return the servlet context with which this servlet is associated.
     */
    @Override
    public ServletContext getServletContext() {

        if (parent == null)
            return (null);
        else if (!(parent instanceof Context))
            return (null);
        else
            return (((Context) parent).getServletContext());

    }


    /**
     * @return the name of this servlet.
     */
    @Override
    public String getServletName() {

        return (getName());

    }

    public long getProcessingTime() {
        return swValve.getProcessingTime();
    }

    public long getMaxTime() {
        return swValve.getMaxTime();
    }

    public long getMinTime() {
        return swValve.getMinTime();
    }

    public int getRequestCount() {
        return swValve.getRequestCount();
    }

    public int getErrorCount() {
        return swValve.getErrorCount();
    }

    /**
     * Increment the error count used for monitoring.
     */
    @Override
    public void incrementErrorCount(){
        swValve.incrementErrorCount();
    }

    public long getLoadTime() {
        return loadTime;
    }

    public int getClassLoadTime() {
        return classLoadTime;
    }

    @Override
    public MultipartConfigElement getMultipartConfigElement() {
        return multipartConfigElement;
    }

    @Override
    public void setMultipartConfigElement(
            MultipartConfigElement multipartConfigElement) {
        this.multipartConfigElement = multipartConfigElement;
    }

    @Override
    public boolean isAsyncSupported() {
        return asyncSupported;
    }

    @Override
    public void setAsyncSupported(boolean asyncSupported) {
        this.asyncSupported = asyncSupported;
    }

    @Override
    public boolean isEnabled() {
        return enabled;
    }

    @Override
    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    // -------------------------------------------------------- Package Methods


    // -------------------------------------------------------- protected Methods


    /**
     * @return <code>true</code> if the specified class name represents a
     * container provided servlet class that should be loaded by the
     * server class loader.
     *
     * @param classname Name of the class to be checked
     *
     * @deprecated Unused. Will be removed in Tomcat 9
     */
    @Deprecated
    protected boolean isContainerProvidedServlet(String classname) {

        if (classname.startsWith("org.apache.catalina.")) {
            return true;
        }
        try {
            Class<?> clazz =
                this.getClass().getClassLoader().loadClass(classname);
            return (ContainerServlet.class.isAssignableFrom(clazz));
        } catch (Throwable t) {
            ExceptionUtils.handleThrowable(t);
            return false;
        }

    }


    protected Method[] getAllDeclaredMethods(Class<?> c) {

        if (c.equals(javax.servlet.http.HttpServlet.class)) {
            return null;
        }

        Method[] parentMethods = getAllDeclaredMethods(c.getSuperclass());

        Method[] thisMethods = c.getDeclaredMethods();
        if (thisMethods.length == 0) {
            return parentMethods;
        }

        if ((parentMethods != null) && (parentMethods.length > 0)) {
            Method[] allMethods =
                new Method[parentMethods.length + thisMethods.length];
            System.arraycopy(parentMethods, 0, allMethods, 0,
                             parentMethods.length);
            System.arraycopy(thisMethods, 0, allMethods, parentMethods.length,
                             thisMethods.length);

            thisMethods = allMethods;
        }

        return thisMethods;
    }


    // ------------------------------------------------------ Lifecycle Methods


    /**
     * Start this component and implement the requirements
     * of {@link org.apache.catalina.util.LifecycleBase#startInternal()}.
     *
     * @exception LifecycleException if this component detects a fatal error
     *  that prevents this component from being used
     */
    @Override
    protected synchronized void startInternal() throws LifecycleException {

        // Send j2ee.state.starting notification
        if (this.getObjectName() != null) {
            Notification notification = new Notification("j2ee.state.starting",
                                                        this.getObjectName(),
                                                        sequenceNumber++);
            broadcaster.sendNotification(notification);
        }

        // 调用父类的startInternal，进行容器的启动
        // Start up this component
        super.startInternal();

        // 设置available属性为0，表示当前Servlet可用，可以对外提供服务了
        setAvailable(0L);

        // 发布启动事件
        // Send j2ee.state.running notification
        if (this.getObjectName() != null) {
            Notification notification =
                new Notification("j2ee.state.running", this.getObjectName(),
                                sequenceNumber++);
            broadcaster.sendNotification(notification);
        }

    }


    /**
     * Stop this component and implement the requirements
     * of {@link org.apache.catalina.util.LifecycleBase#stopInternal()}.
     *
     * @exception LifecycleException if this component detects a fatal error
     *  that prevents this component from being used
     */
    @Override
    protected synchronized void stopInternal() throws LifecycleException {

        // 设置available属性为Long.MAX_VALUE，表示当前Servlet永久不可用
        setAvailable(Long.MAX_VALUE);

        // Send j2ee.state.stopping notification
        if (this.getObjectName() != null) {
            Notification notification =
                new Notification("j2ee.state.stopping", this.getObjectName(),
                                sequenceNumber++);
            broadcaster.sendNotification(notification);
        }

        // 卸载所有已加载的Servlet实例
        // Shut down our servlet instance (if it has been initialized)
        try {
            unload();
        } catch (ServletException e) {
            getServletContext().log(sm.getString
                      ("standardWrapper.unloadException", getName()), e);
        }

        // 调用父类的stopInternal方法，关闭当前容器
        // Shut down this component
        super.stopInternal();

        // Send j2ee.state.stopped notification
        if (this.getObjectName() != null) {
            Notification notification =
                new Notification("j2ee.state.stopped", this.getObjectName(),
                                sequenceNumber++);
            broadcaster.sendNotification(notification);
        }

        // Send j2ee.object.deleted notification
        Notification notification =
            new Notification("j2ee.object.deleted", this.getObjectName(),
                            sequenceNumber++);
        broadcaster.sendNotification(notification);

    }


    @Override
    protected String getObjectNameKeyProperties() {

        StringBuilder keyProperties =
            new StringBuilder("j2eeType=Servlet");

        keyProperties.append(getWebModuleKeyProperties());

        keyProperties.append(",name=");

        String name = getName();
        if (Util.objectNameValueNeedsQuote(name)) {
            name = ObjectName.quote(name);
        }
        keyProperties.append(name);

        keyProperties.append(getJ2EEKeyProperties());

        return keyProperties.toString();
    }


    private String getWebModuleKeyProperties() {

        StringBuilder keyProperties = new StringBuilder(",WebModule=//");
        String hostName = getParent().getParent().getName();
        if (hostName == null) {
            keyProperties.append("DEFAULT");
        } else {
            keyProperties.append(hostName);
        }

        String contextName = ((Context) getParent()).getName();
        if (!contextName.startsWith("/")) {
            keyProperties.append('/');
        }
        keyProperties.append(contextName);

        return keyProperties.toString();
    }

    private String getJ2EEKeyProperties() {

        StringBuilder keyProperties = new StringBuilder(",J2EEApplication=");

        StandardContext ctx = null;
        if (parent instanceof StandardContext) {
            ctx = (StandardContext) getParent();
        }

        if (ctx == null) {
            keyProperties.append("none");
        } else {
            keyProperties.append(ctx.getJ2EEApplication());
        }
        keyProperties.append(",J2EEServer=");
        if (ctx == null) {
            keyProperties.append("none");
        } else {
            keyProperties.append(ctx.getJ2EEServer());
        }

        return keyProperties.toString();
    }


    /**
     * Remove a JMX notificationListener
     * @see javax.management.NotificationEmitter#removeNotificationListener(javax.management.NotificationListener, javax.management.NotificationFilter, java.lang.Object)
     */
    @Override
    public void removeNotificationListener(NotificationListener listener,
            NotificationFilter filter, Object object) throws ListenerNotFoundException {
        broadcaster.removeNotificationListener(listener,filter,object);
    }

    protected MBeanNotificationInfo[] notificationInfo;

    /**
     * Get JMX Broadcaster Info
     * FIXME: This two events we not send j2ee.state.failed and j2ee.attribute.changed!
     * @see javax.management.NotificationBroadcaster#getNotificationInfo()
     */
    @Override
    public MBeanNotificationInfo[] getNotificationInfo() {

        if(notificationInfo == null) {
            notificationInfo = new MBeanNotificationInfo[]{
                    new MBeanNotificationInfo(new String[] {
                    "j2ee.object.created"},
                    Notification.class.getName(),
                    "servlet is created"
                    ),
                    new MBeanNotificationInfo(new String[] {
                    "j2ee.state.starting"},
                    Notification.class.getName(),
                    "servlet is starting"
                    ),
                    new MBeanNotificationInfo(new String[] {
                    "j2ee.state.running"},
                    Notification.class.getName(),
                    "servlet is running"
                    ),
                    new MBeanNotificationInfo(new String[] {
                    "j2ee.state.stopped"},
                    Notification.class.getName(),
                    "servlet start to stopped"
                    ),
                    new MBeanNotificationInfo(new String[] {
                    "j2ee.object.stopped"},
                    Notification.class.getName(),
                    "servlet is stopped"
                    ),
                    new MBeanNotificationInfo(new String[] {
                    "j2ee.object.deleted"},
                    Notification.class.getName(),
                    "servlet is deleted"
                    )
            };
        }

        return notificationInfo;
    }


    /**
     * Add a JMX-NotificationListener
     * @see javax.management.NotificationBroadcaster#addNotificationListener(javax.management.NotificationListener, javax.management.NotificationFilter, java.lang.Object)
     */
    @Override
    public void addNotificationListener(NotificationListener listener,
            NotificationFilter filter, Object object) throws IllegalArgumentException {
        broadcaster.addNotificationListener(listener,filter,object);
    }


    /**
     * Remove a JMX-NotificationListener
     * @see javax.management.NotificationBroadcaster#removeNotificationListener(javax.management.NotificationListener)
     */
    @Override
    public void removeNotificationListener(NotificationListener listener)
        throws ListenerNotFoundException {
        broadcaster.removeNotificationListener(listener);
    }
}
