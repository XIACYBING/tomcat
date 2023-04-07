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

import java.io.IOException;
import java.io.InputStream;
import java.lang.annotation.Annotation;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.security.AccessController;
import java.security.PrivilegedAction;
import java.security.PrivilegedActionException;
import java.security.PrivilegedExceptionAction;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;

import javax.annotation.PostConstruct;
import javax.annotation.PreDestroy;
import javax.annotation.Resource;
import javax.ejb.EJB;
import javax.naming.Context;
import javax.naming.NamingException;
import javax.persistence.PersistenceContext;
import javax.persistence.PersistenceUnit;
import javax.xml.ws.WebServiceRef;

import org.apache.catalina.ContainerServlet;
import org.apache.catalina.Globals;
import org.apache.catalina.security.SecurityUtil;
import org.apache.catalina.util.Introspection;
import org.apache.juli.logging.Log;
import org.apache.tomcat.InstanceManager;
import org.apache.tomcat.util.ExceptionUtils;
import org.apache.tomcat.util.collections.ManagedConcurrentWeakHashMap;
import org.apache.tomcat.util.res.StringManager;

public class DefaultInstanceManager implements InstanceManager {

    // Used when there are no annotations in a class
    private static final AnnotationCacheEntry[] ANNOTATIONS_EMPTY
        = new AnnotationCacheEntry[0];

    /**
     * The string manager for this package.
     */
    protected static final StringManager sm =
        StringManager.getManager(Constants.Package);

    private final Context context;

    /**
     * 类注入信息Map，<ClassName, <FieldName, AnnotationName>>
     */
    private final Map<String, Map<String, String>> injectionMap;
    protected final ClassLoader classLoader;
    protected final ClassLoader containerClassLoader;
    protected final boolean privileged;

    /**
     * 是否忽略注解信息，即不处理注解信息
     */
    protected final boolean ignoreAnnotations;
    private final Set<String> restrictedClasses;
    private final ManagedConcurrentWeakHashMap<Class<?>, AnnotationCacheEntry[]> annotationCache =
            new ManagedConcurrentWeakHashMap<>();
    private final Map<String, String> postConstructMethods;
    private final Map<String, String> preDestroyMethods;

    public DefaultInstanceManager(Context context,
            Map<String, Map<String, String>> injectionMap,
            org.apache.catalina.Context catalinaContext,
            ClassLoader containerClassLoader) {
        classLoader = catalinaContext.getLoader().getClassLoader();
        privileged = catalinaContext.getPrivileged();
        this.containerClassLoader = containerClassLoader;
        ignoreAnnotations = catalinaContext.getIgnoreAnnotations();
        Log log = catalinaContext.getLogger();
        Set<String> classNames = new HashSet<>();
        loadProperties(classNames,
                "org/apache/catalina/core/RestrictedServlets.properties",
                "defaultInstanceManager.restrictedServletsResource", log);
        loadProperties(classNames,
                "org/apache/catalina/core/RestrictedListeners.properties",
                "defaultInstanceManager.restrictedListenersResource", log);
        loadProperties(classNames,
                "org/apache/catalina/core/RestrictedFilters.properties",
                "defaultInstanceManager.restrictedFiltersResource", log);
        restrictedClasses = Collections.unmodifiableSet(classNames);
        this.context = context;
        this.injectionMap = injectionMap;
        this.postConstructMethods = catalinaContext.findPostConstructMethods();
        this.preDestroyMethods = catalinaContext.findPreDestroyMethods();
    }

    @Override
    public Object newInstance(Class<?> clazz) throws IllegalAccessException,
            InvocationTargetException, NamingException, InstantiationException,
            IllegalArgumentException, NoSuchMethodException, SecurityException {
        return newInstance(clazz.getConstructor().newInstance(), clazz);
    }

    @Override
    public Object newInstance(String className) throws IllegalAccessException,
            InvocationTargetException, NamingException, InstantiationException,
            ClassNotFoundException, IllegalArgumentException, NoSuchMethodException, SecurityException {
        Class<?> clazz = loadClassMaybePrivileged(className, classLoader);
        return newInstance(clazz.getConstructor().newInstance(), clazz);
    }

    @Override
    public Object newInstance(final String className, final ClassLoader classLoader)
            throws IllegalAccessException, NamingException, InvocationTargetException,
            InstantiationException, ClassNotFoundException, IllegalArgumentException,
            NoSuchMethodException, SecurityException {
        Class<?> clazz = classLoader.loadClass(className);
        return newInstance(clazz.getConstructor().newInstance(), clazz);
    }

    @Override
    public void newInstance(Object o)
            throws IllegalAccessException, InvocationTargetException, NamingException {
        newInstance(o, o.getClass());
    }

    private Object newInstance(Object instance, Class<?> clazz)
            throws IllegalAccessException, InvocationTargetException, NamingException {

        // 如果不忽略注解，则需要针对注解内容进行计算和注入处理
        if (!ignoreAnnotations) {

            // 根据当前instanceManager中的injectionMap和clazz类对象信息，计算当前clazz类对象及其父类有在injectionMap中有配置的注解信息
            Map<String, String> injections = assembleInjectionsFromClassHierarchy(clazz);

            // 计算当前类的字段和方法，是否有需要被处理的注解数据，并将结果放入annotationCache中
            populateAnnotationsCache(clazz, injections);

            // 根据annotationCache，获取类中需要被处理的字段和方法注解数据，通过context获取需要被注入的资源，通过反射调用方法或设置字段值
            processAnnotations(instance, injections);

            // 处理当前clazz类对象及其父类的PostConstruct方法
            postConstruct(instance, clazz);
        }

        // 返回类实例
        return instance;
    }

    private Map<String, String> assembleInjectionsFromClassHierarchy(Class<?> clazz) {

        // 承载当前clazz类对象及其父类的注入信息
        Map<String, String> injections = new HashMap<>();
        Map<String, String> currentInjections = null;

        // 循环clazz类对象，获取注入信息
        while (clazz != null) {

            // 获取当前类对象的注入信息，不为空的情况下放入injections
            currentInjections = this.injectionMap.get(clazz.getName());
            if (currentInjections != null) {
                injections.putAll(currentInjections);
            }

            // 获取父类，继续循环
            clazz = clazz.getSuperclass();
        }

        // 返回获取到的注入信息
        return injections;
    }

    @Override
    public void destroyInstance(Object instance) throws IllegalAccessException,
            InvocationTargetException {
        if (!ignoreAnnotations) {
            preDestroy(instance, instance.getClass());
        }
    }

    /**
     * Call postConstruct method on the specified instance recursively from
     * deepest superclass to actual class.
     *
     * @param instance object to call postconstruct methods on
     * @param clazz    (super) class to examine for postConstruct annotation.
     * @throws IllegalAccessException if postConstruct method is inaccessible.
     * @throws java.lang.reflect.InvocationTargetException
     *                                if call fails
     */
    protected void postConstruct(Object instance, final Class<?> clazz)
            throws IllegalAccessException, InvocationTargetException {
        if (context == null) {
            // No resource injection
            return;
        }

        // 先获取父类，对父类的PostConstruct方法进行处理
        Class<?> superClass = clazz.getSuperclass();
        if (superClass != Object.class) {
            postConstruct(instance, superClass);
        }

        // At the end the postconstruct annotated
        // method is invoked

        // 获取当前类对象的注解缓存数据，筛选出POST_CONSTRUCT类型的注解缓存数据
        AnnotationCacheEntry[] annotations = annotationCache.get(clazz);
        for (AnnotationCacheEntry entry : annotations) {
            if (entry.getType() == AnnotationCacheEntryType.POST_CONSTRUCT) {

                // 获取对应的PostConstruct方法，并通过反射调用
                Method postConstruct = getMethod(clazz, entry);
                synchronized (postConstruct) {
                    boolean accessibility = postConstruct.isAccessible();
                    postConstruct.setAccessible(true);
                    postConstruct.invoke(instance);
                    postConstruct.setAccessible(accessibility);
                }
            }
        }
    }


    /**
     * Call preDestroy method on the specified instance recursively from deepest
     * superclass to actual class.
     *
     * @param instance object to call preDestroy methods on
     * @param clazz    (super) class to examine for preDestroy annotation.
     * @throws IllegalAccessException if preDestroy method is inaccessible.
     * @throws java.lang.reflect.InvocationTargetException
     *                                if call fails
     */
    protected void preDestroy(Object instance, final Class<?> clazz)
            throws IllegalAccessException, InvocationTargetException {
        Class<?> superClass = clazz.getSuperclass();
        if (superClass != Object.class) {
            preDestroy(instance, superClass);
        }

        // At the end the postconstruct annotated
        // method is invoked
        AnnotationCacheEntry[] annotations = annotationCache.get(clazz);
        if (annotations == null) {
            // instance not created through the instance manager
            return;
        }
        for (AnnotationCacheEntry entry : annotations) {
            if (entry.getType() == AnnotationCacheEntryType.PRE_DESTROY) {
                Method preDestroy = getMethod(clazz, entry);
                synchronized (preDestroy) {
                    boolean accessibility = preDestroy.isAccessible();
                    preDestroy.setAccessible(true);
                    preDestroy.invoke(instance);
                    preDestroy.setAccessible(accessibility);
                }
            }
        }
    }


    public void backgroundProcess() {
        annotationCache.maintain();
    }



    /**
     * Make sure that the annotations cache has been populated for the provided
     * class.
     *
     * @param clazz         clazz to populate annotations for
     * @param injections    map of injections for this class from xml deployment
     *                      descriptor
     * @throws IllegalAccessException       if injection target is inaccessible
     * @throws javax.naming.NamingException if value cannot be looked up in jndi
     * @throws java.lang.reflect.InvocationTargetException
     *                                      if injection fails
     */
    protected void populateAnnotationsCache(Class<?> clazz,
        Map<String, String> injections) throws IllegalAccessException,
        InvocationTargetException, NamingException {

        List<AnnotationCacheEntry> annotations = null;

        while (clazz != null) {

            // 获取当前类对象的注解缓存数据
            AnnotationCacheEntry[] annotationsArray = annotationCache.get(clazz);

            // 为空说明需要进行注解的计算
            if (annotationsArray == null) {

                // 为空说明第一次循环，否则说明非第一次循环，调用clear清除上一次循环的数据
                if (annotations == null) {
                    annotations = new ArrayList<>();
                } else {
                    annotations.clear();
                }

                // 上下文不为空才计算
                if (context != null) {
                    // Initialize fields annotations for resource injection if
                    // JNDI is enabled

                    // 初始化字段注解缓存
                    // 获取所有声明的字段，并循环处理
                    Field[] fields = Introspection.getDeclaredFields(clazz);
                    for (Field field : fields) {
                        Resource resourceAnnotation;
                        EJB ejbAnnotation;
                        WebServiceRef webServiceRefAnnotation;
                        PersistenceContext persistenceContextAnnotation;
                        PersistenceUnit persistenceUnitAnnotation;

                        if (injections != null && injections.containsKey(field.getName())) {

                            // 当前字段在injections中已有需处理的注解信息，生成注解缓存并放入集合中
                            annotations.add(new AnnotationCacheEntry(
                                field.getName(), null,
                                injections.get(field.getName()),
                                AnnotationCacheEntryType.FIELD));
                        } else if ((resourceAnnotation =
                            field.getAnnotation(Resource.class)) != null) {

                            // 当前字段有Resource注解需要处理，生成注解缓存并放入集合中
                            annotations.add(new AnnotationCacheEntry(field.getName(), null,
                                resourceAnnotation.name(), AnnotationCacheEntryType.FIELD));
                        } else if ((ejbAnnotation =
                            field.getAnnotation(EJB.class)) != null) {

                            // 当前字段有EJB注解需要处理，生成注解缓存并放入集合中
                            annotations.add(new AnnotationCacheEntry(field.getName(), null,
                                ejbAnnotation.name(), AnnotationCacheEntryType.FIELD));
                        } else if ((webServiceRefAnnotation =
                            field.getAnnotation(WebServiceRef.class)) != null) {

                            // 当前字段有WebServiceRef注解需要处理，生成注解缓存并放入集合中
                            annotations.add(new AnnotationCacheEntry(field.getName(), null,
                                webServiceRefAnnotation.name(),
                                AnnotationCacheEntryType.FIELD));
                        } else if ((persistenceContextAnnotation =
                            field.getAnnotation(PersistenceContext.class)) != null) {

                            // 当前字段有PersistenceContext注解需要处理，生成注解缓存并放入集合中
                            annotations.add(new AnnotationCacheEntry(field.getName(), null,
                                persistenceContextAnnotation.name(),
                                AnnotationCacheEntryType.FIELD));
                        } else if ((persistenceUnitAnnotation =
                            field.getAnnotation(PersistenceUnit.class)) != null) {

                            // 当前字段有PersistenceUnit注解需要处理，生成注解缓存并放入集合中
                            annotations.add(new AnnotationCacheEntry(field.getName(), null,
                                persistenceUnitAnnotation.name(),
                                AnnotationCacheEntryType.FIELD));
                        }
                    }
                }

                // 初始化方法注解缓存：主要是标注@PostConstruct和@PreDestroy注解的方法
                // Initialize methods annotations
                Method[] methods = Introspection.getDeclaredMethods(clazz);

                // 如果一个类中声明了多个@PostConstruct/@PreDestroy方法，是非法的，会抛出异常
                // xml声明 > 类中注解，前提是xml文件中声明的方法在clazz中是确实存在的
                // 每个类对象都可以有一个postConstruct和preDestroy方法
                Method postConstruct = null;
                Method preDestroy = null;

                // 获取xml文件中配置的当前类的PostConstruct和PreDestroy方法
                String postConstructFromXml = postConstructMethods.get(clazz.getName());
                String preDestroyFromXml = preDestroyMethods.get(clazz.getName());
                for (Method method : methods) {

                    // context不为空时，需要计算方法上的注解信息
                    if (context != null) {
                        // Resource injection only if JNDI is enabled
                        if (injections != null &&
                            Introspection.isValidSetter(method)) {
                            String fieldName = Introspection.getPropertyName(method);
                            if (injections.containsKey(fieldName)) {
                                annotations.add(new AnnotationCacheEntry(
                                    method.getName(),
                                    method.getParameterTypes(),
                                    injections.get(fieldName),
                                    AnnotationCacheEntryType.SETTER));
                                continue;
                            }
                        }
                        Resource resourceAnnotation;
                        EJB ejbAnnotation;
                        WebServiceRef webServiceRefAnnotation;
                        PersistenceContext persistenceContextAnnotation;
                        PersistenceUnit persistenceUnitAnnotation;
                        if ((resourceAnnotation =
                            method.getAnnotation(Resource.class)) != null) {
                            annotations.add(new AnnotationCacheEntry(
                                method.getName(),
                                method.getParameterTypes(),
                                resourceAnnotation.name(),
                                AnnotationCacheEntryType.SETTER));
                        } else if ((ejbAnnotation =
                            method.getAnnotation(EJB.class)) != null) {
                            annotations.add(new AnnotationCacheEntry(
                                method.getName(),
                                method.getParameterTypes(),
                                ejbAnnotation.name(),
                                AnnotationCacheEntryType.SETTER));
                        } else if ((webServiceRefAnnotation =
                            method.getAnnotation(WebServiceRef.class)) != null) {
                            annotations.add(new AnnotationCacheEntry(
                                method.getName(),
                                method.getParameterTypes(),
                                webServiceRefAnnotation.name(),
                                AnnotationCacheEntryType.SETTER));
                        } else if ((persistenceContextAnnotation =
                            method.getAnnotation(PersistenceContext.class)) != null) {
                            annotations.add(new AnnotationCacheEntry(
                                method.getName(),
                                method.getParameterTypes(),
                                persistenceContextAnnotation.name(),
                                AnnotationCacheEntryType.SETTER));
                        } else if ((persistenceUnitAnnotation = method.getAnnotation(PersistenceUnit.class)) != null) {
                            annotations.add(new AnnotationCacheEntry(
                                method.getName(),
                                method.getParameterTypes(),
                                persistenceUnitAnnotation.name(),
                                AnnotationCacheEntryType.SETTER));
                        }
                    }

                    // 判断当前方法是否声明PostConstruct和PreDestroy注解，如果是，将当前方法作为postConstruct/preDestroy返回

                    // 如果postConstruct不为空，且method也标记了对应的注解，且method是有效的回调方法，则会抛出异常
                    // 除非method的名称是postConstructFromXml，因为xml优先级大于clazz中的注解声明
                    postConstruct = findPostConstruct(postConstruct, postConstructFromXml, method);
                    preDestroy = findPreDestroy(preDestroy, preDestroyFromXml, method);
                }

                // 如果postConstruct方法不为空，则生成注解缓存加入集合中
                if (postConstruct != null) {
                    annotations.add(new AnnotationCacheEntry(
                        postConstruct.getName(),
                        postConstruct.getParameterTypes(), null,
                        AnnotationCacheEntryType.POST_CONSTRUCT));
                }

                // 进入当前判断，说明xml文件中声明的postConstruct方法在clazz中并不存在，需要抛出异常
                else if (postConstructFromXml != null) {
                    throw new IllegalArgumentException("Post construct method "
                        + postConstructFromXml + " for class " + clazz.getName()
                        + " is declared in deployment descriptor but cannot be found.");
                }

                // preDestroy和postConstruct的处理一致
                if (preDestroy != null) {
                    annotations.add(new AnnotationCacheEntry(
                        preDestroy.getName(),
                        preDestroy.getParameterTypes(), null,
                        AnnotationCacheEntryType.PRE_DESTROY));
                } else if (preDestroyFromXml != null) {
                    throw new IllegalArgumentException("Pre destroy method "
                        + preDestroyFromXml + " for class " + clazz.getName()
                        + " is declared in deployment descriptor but cannot be found.");
                }

                // 当前clazz计算出的字段和方法注解数据集合如果为空，使用特殊的对象标记，不为空则将集合转换为数组赋值
                if (annotations.isEmpty()) {
                    // Use common object to save memory
                    annotationsArray = ANNOTATIONS_EMPTY;
                } else {
                    annotationsArray = annotations.toArray(
                        new AnnotationCacheEntry[annotations.size()]);
                }

                // 加锁annotationCache，将clazz和字段方法注解计算结果放入缓存中
                synchronized (annotationCache) {
                    annotationCache.put(clazz, annotationsArray);
                }
            }

            // 获取父类，继续处理
            clazz = clazz.getSuperclass();
        }
    }

    /**
     * Inject resources in specified instance.
     *
     * @param instance   instance to inject into
     * @param injections map of injections for this class from xml deployment descriptor
     * @throws IllegalAccessException       if injection target is inaccessible
     * @throws javax.naming.NamingException if value cannot be looked up in jndi
     * @throws java.lang.reflect.InvocationTargetException
     *                                      if injection fails
     */
    protected void processAnnotations(Object instance, Map<String, String> injections)
            throws IllegalAccessException, InvocationTargetException, NamingException {

        // context为空，无法获取到资源，无需处理
        if (context == null) {
            // No resource injection
            return;
        }

        Class<?> clazz = instance.getClass();

        while (clazz != null) {

            // 获取当前类对象的注解缓存信息
            AnnotationCacheEntry[] annotations = annotationCache.get(clazz);
            for (AnnotationCacheEntry entry : annotations) {

                // 如果是setter方法，通过lookupMethodResource方法调用方法
                if (entry.getType() == AnnotationCacheEntryType.SETTER) {
                    lookupMethodResource(context, instance,
                            getMethod(clazz, entry),
                            entry.getName(), clazz);
                }

                // 如果是字段，通过lookupFieldResource对字段赋值
                else if (entry.getType() == AnnotationCacheEntryType.FIELD) {
                    lookupFieldResource(context, instance,
                            getField(clazz, entry),
                            entry.getName(), clazz);
                }
            }

            // 获取父类，继续处理
            clazz = clazz.getSuperclass();
        }
    }


    /**
     * Makes cache size available to unit tests.
     *
     * @return the cache size
     */
    protected int getAnnotationCacheSize() {
        return annotationCache.size();
    }


    protected Class<?> loadClassMaybePrivileged(final String className,
            final ClassLoader classLoader) throws ClassNotFoundException {
        Class<?> clazz;
        if (SecurityUtil.isPackageProtectionEnabled()) {
            try {
                clazz = AccessController.doPrivileged(new PrivilegedExceptionAction<Class<?>>() {

                    @Override
                    public Class<?> run() throws Exception {
                        return loadClass(className, classLoader);
                    }
                });
            } catch (PrivilegedActionException e) {
                Throwable t = e.getCause();
                if (t instanceof ClassNotFoundException) {
                    throw (ClassNotFoundException) t;
                }
                throw new RuntimeException(t);
            }
        } else {
            clazz = loadClass(className, classLoader);
        }
        checkAccess(clazz);
        return clazz;
    }

    protected Class<?> loadClass(String className, ClassLoader classLoader)
            throws ClassNotFoundException {
        if (className.startsWith("org.apache.catalina")) {
            return containerClassLoader.loadClass(className);
        }
        try {
            Class<?> clazz = containerClassLoader.loadClass(className);
            if (ContainerServlet.class.isAssignableFrom(clazz)) {
                return clazz;
            }
        } catch (Throwable t) {
            ExceptionUtils.handleThrowable(t);
        }
        return classLoader.loadClass(className);
    }

    private void checkAccess(Class<?> clazz) {
        if (privileged) {
            return;
        }
        if (ContainerServlet.class.isAssignableFrom(clazz)) {
            throw new SecurityException(sm.getString(
                    "defaultInstanceManager.restrictedContainerServlet", clazz));
        }
        while (clazz != null) {
            if (restrictedClasses.contains(clazz.getName())) {
                throw new SecurityException(sm.getString(
                        "defaultInstanceManager.restrictedClass", clazz));
            }
            clazz = clazz.getSuperclass();
        }
    }

    /**
     * Inject resources in specified field.
     *
     * @param context  jndi context to extract value from
     * @param instance object to inject into
     * @param field    field target for injection
     * @param name     jndi name value is bound under
     * @param clazz    class annotation is defined in
     * @throws IllegalAccessException       if field is inaccessible
     * @throws javax.naming.NamingException if value is not accessible in naming context
     */
    protected static void lookupFieldResource(Context context,
            Object instance, Field field, String name, Class<?> clazz)
            throws NamingException, IllegalAccessException {

        Object lookedupResource;
        boolean accessibility;

        String normalizedName = normalize(name);

        // 通过Context获取需要被注入的资源
        if ((normalizedName != null) && (normalizedName.length() > 0)) {
            lookedupResource = context.lookup(normalizedName);
        } else {
            lookedupResource =
                context.lookup(clazz.getName() + "/" + field.getName());
        }

        // 字段加锁，通过反射调用
        synchronized (field) {
            accessibility = field.isAccessible();
            field.setAccessible(true);
            field.set(instance, lookedupResource);
            field.setAccessible(accessibility);
        }
    }

    /**
     * Inject resources in specified method.
     *
     * @param context  jndi context to extract value from
     * @param instance object to inject into
     * @param method   field target for injection
     * @param name     jndi name value is bound under
     * @param clazz    class annotation is defined in
     * @throws IllegalAccessException       if method is inaccessible
     * @throws javax.naming.NamingException if value is not accessible in naming context
     * @throws java.lang.reflect.InvocationTargetException
     *                                      if setter call fails
     */
    protected static void lookupMethodResource(Context context,
            Object instance, Method method, String name, Class<?> clazz)
            throws NamingException, IllegalAccessException, InvocationTargetException {

        if (!Introspection.isValidSetter(method)) {
            throw new IllegalArgumentException(
                    sm.getString("defaultInstanceManager.invalidInjection"));
        }

        Object lookedupResource;
        boolean accessibility;

        String normalizedName = normalize(name);

        // 通过context获取资源
        if ((normalizedName != null) && (normalizedName.length() > 0)) {
            lookedupResource = context.lookup(normalizedName);
        } else {
            lookedupResource = context.lookup(
                    clazz.getName() + "/" + Introspection.getPropertyName(method));
        }

        // 方法加锁，通过反射调用
        synchronized (method) {
            accessibility = method.isAccessible();
            method.setAccessible(true);
            method.invoke(instance, lookedupResource);
            method.setAccessible(accessibility);
        }
    }

    private static void loadProperties(Set<String> classNames, String resourceName,
            String messageKey, Log log) {
        Properties properties = new Properties();
        ClassLoader cl = DefaultInstanceManager.class.getClassLoader();
        try (InputStream is = cl.getResourceAsStream(resourceName)) {
            if (is == null) {
                log.error(sm.getString(messageKey, resourceName));
            } else {
                properties.load(is);
            }
        } catch (IOException ioe) {
            log.error(sm.getString(messageKey, resourceName), ioe);
        }
        if (properties.isEmpty()) {
            return;
        }
        for (Map.Entry<Object, Object> e : properties.entrySet()) {
            if ("restricted".equals(e.getValue())) {
                classNames.add(e.getKey().toString());
            } else {
                log.warn(sm.getString(
                        "defaultInstanceManager.restrictedWrongValue",
                        resourceName, e.getKey(), e.getValue()));
            }
        }
    }

    private static String normalize(String jndiName){
        if(jndiName != null && jndiName.startsWith("java:comp/env/")){
            return jndiName.substring(14);
        }
        return jndiName;
    }

    private static Method getMethod(final Class<?> clazz,
            final AnnotationCacheEntry entry) {
        Method result = null;
        if (Globals.IS_SECURITY_ENABLED) {
            result = AccessController.doPrivileged(
                    new PrivilegedAction<Method>() {
                        @Override
                        public Method run() {
                            Method result = null;
                            try {
                                result = clazz.getDeclaredMethod(
                                        entry.getAccessibleObjectName(),
                                        entry.getParamTypes());
                            } catch (NoSuchMethodException e) {
                                // Should never happen. On that basis don't log
                                // it.
                            }
                            return result;
                        }
            });
        } else {
            try {
                result = clazz.getDeclaredMethod(
                        entry.getAccessibleObjectName(), entry.getParamTypes());
            } catch (NoSuchMethodException e) {
                // Should never happen. On that basis don't log it.
            }
        }
        return result;
    }

    private static Field getField(final Class<?> clazz,
            final AnnotationCacheEntry entry) {
        Field result = null;
        if (Globals.IS_SECURITY_ENABLED) {
            result = AccessController.doPrivileged(
                    new PrivilegedAction<Field>() {
                        @Override
                        public Field run() {
                            Field result = null;
                            try {
                                result = clazz.getDeclaredField(
                                        entry.getAccessibleObjectName());
                            } catch (NoSuchFieldException e) {
                                // Should never happen. On that basis don't log
                                // it.
                            }
                            return result;
                        }
            });
        } else {
            try {
                result = clazz.getDeclaredField(
                        entry.getAccessibleObjectName());
            } catch (NoSuchFieldException e) {
                // Should never happen. On that basis don't log it.
            }
        }
        return result;
    }


    private static Method findPostConstruct(Method currentPostConstruct,
            String postConstructFromXml, Method method) {
        return findLifecycleCallback(currentPostConstruct,
            postConstructFromXml, method, PostConstruct.class);
    }

    private static Method findPreDestroy(Method currentPreDestroy,
        String preDestroyFromXml, Method method) {
        return findLifecycleCallback(currentPreDestroy,
            preDestroyFromXml, method, PreDestroy.class);
    }

    private static Method findLifecycleCallback(Method currentMethod,
            String methodNameFromXml, Method method,
            Class<? extends Annotation> annotation) {
        Method result = currentMethod;
        if (methodNameFromXml != null) {

            // 如果当前方法和xml文件中声明的方法名称一致
            if (method.getName().equals(methodNameFromXml)) {

                // 当前method非有效声明周期回调方法（无入参、无出参、非静态方法、方法未声明异常抛出），抛出异常
                if (!Introspection.isValidLifecycleCallback(method)) {
                    throw new IllegalArgumentException(
                            "Invalid " + annotation.getName() + " annotation");
                }

                // 否则将当前方法作为结果方法返回
                result = method;
            }
        } else {

            // 如果当前方法有声明对应注解：PostConstruct/PreDestroy
            if (method.isAnnotationPresent(annotation)) {

                // 当前声明对应注解的方法不为空，或要判断的method非有效声明周期回调方法（无入参、无出参、非静态方法、方法未声明异常抛出），则抛出异常
                if (currentMethod != null || !Introspection.isValidLifecycleCallback(method)) {
                    throw new IllegalArgumentException(
                            "Invalid " + annotation.getName() + " annotation");
                }
                result = method;
            }
        }
        return result;
    }

    private static final class AnnotationCacheEntry {
        private final String accessibleObjectName;
        private final Class<?>[] paramTypes;
        private final String name;
        private final AnnotationCacheEntryType type;

        public AnnotationCacheEntry(String accessibleObjectName,
                Class<?>[] paramTypes, String name,
                AnnotationCacheEntryType type) {
            this.accessibleObjectName = accessibleObjectName;
            this.paramTypes = paramTypes;
            this.name = name;
            this.type = type;
        }

        public String getAccessibleObjectName() {
            return accessibleObjectName;
        }

        public Class<?>[] getParamTypes() {
            return paramTypes;
        }

        public String getName() {
            return name;
        }
        public AnnotationCacheEntryType getType() {
            return type;
        }
    }

    private static enum AnnotationCacheEntryType {
        FIELD, SETTER, POST_CONSTRUCT, PRE_DESTROY
    }
}
