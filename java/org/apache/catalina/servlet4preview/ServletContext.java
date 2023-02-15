/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.catalina.servlet4preview;

import org.apache.catalina.core.StandardContext;

import javax.servlet.ServletContextEvent;
import javax.servlet.ServletContextListener;
import javax.servlet.ServletRegistration;

/**
 * {@link javax.servlet.ServletContext}代表Web应用的上下文环境，而Tomcat的{@link org.apache.catalina.Context}容器则是代表一个Web
 * 应用程序，所以前者是后者的一个成员变量；
 *
 * 对于Spring框架的ApplicationContext来说，Spring是通过增加感知Tomcat上下文加载的监听器{@code org.springframework.web.context
 * .ContextLoaderListener}来初始化Spring的ApplicationContext，并将ApplicationContext存储到{@link javax.servlet.ServletContext}
 *
 * {@link javax.servlet.ServletContext}存储{@code org.springframework.context.ApplicationContext}的部分：
 * {@code {@code servletContext.setAttribute(WebApplicationContext.ROOT_WEB_APPLICATION_CONTEXT_ATTRIBUTE, this.context);}}
 *
 * {@link org.apache.catalina.Context}存储{@link ServletContext}的部分：{@link StandardContext#context}
 */
public interface ServletContext extends javax.servlet.ServletContext {

    /**
     * Get the default session timeout.
     *
     * @throws UnsupportedOperationException    If called from a
     *    {@link ServletContextListener#contextInitialized(ServletContextEvent)}
     *    method of a {@link ServletContextListener} that was not defined in a
     *    web.xml file, a web-fragment.xml file nor annotated with
     *    {@link javax.servlet.annotation.WebListener}. For example, a
     *    {@link ServletContextListener} defined in a TLD would not be able to
     *    use this method.
     *
     * @return The current default session timeout in minutes
     *
     * @since Servlet 4.0
     */
    public int getSessionTimeout();

    /**
     * Set the default session timeout. This method may only be called before
     * the ServletContext is initialised.
     *
     * @param sessionTimeout The new default session timeout in minutes.
     *
     * @throws UnsupportedOperationException    If called from a
     *    {@link ServletContextListener#contextInitialized(ServletContextEvent)}
     *    method of a {@link ServletContextListener} that was not defined in a
     *    web.xml file, a web-fragment.xml file nor annotated with
     *    {@link javax.servlet.annotation.WebListener}. For example, a
     *    {@link ServletContextListener} defined in a TLD would not be able to
     *    use this method.
     * @throws IllegalStateException If the ServletContext has already been
     *         initialised
     *
     * @since Servlet 4.0
     */
    public void setSessionTimeout(int sessionTimeout);

    /**
     *
     * @param jspName   The servlet name under which this JSP file should be
     *                  registered
     * @param jspFile   The path, relative to the web application root, for the
     *                  JSP file to be used for this servlet
     *
     * @return  a {@link javax.servlet.ServletRegistration.Dynamic} object
     *          that can be used to further configure the servlet
     *
     * @since Servlet 4.0
     */
    public ServletRegistration.Dynamic addJspFile(String jspName, String jspFile);

    /**
     * Get the default character encoding for reading request bodies.
     *
     * @return The character encoding name or {@code null} if no default has
     *         been specified
     *
     * @throws UnsupportedOperationException    If called from a
     *    {@link ServletContextListener#contextInitialized(ServletContextEvent)}
     *    method of a {@link ServletContextListener} that was not defined in a
     *    web.xml file, a web-fragment.xml file nor annotated with
     *    {@link javax.servlet.annotation.WebListener}. For example, a
     *    {@link ServletContextListener} defined in a TLD would not be able to
     *    use this method.
     *
     * @since Servlet 4.0
     */
    public String getRequestCharacterEncoding();

    /**
     * Set the default character encoding to use for reading request bodies.
     * Calling this method will over-ride any value set in the deployment
     * descriptor.
     *
     * @param encoding The name of the character encoding to use
     *
     * @throws UnsupportedOperationException    If called from a
     *    {@link ServletContextListener#contextInitialized(ServletContextEvent)}
     *    method of a {@link ServletContextListener} that was not defined in a
     *    web.xml file, a web-fragment.xml file nor annotated with
     *    {@link javax.servlet.annotation.WebListener}. For example, a
     *    {@link ServletContextListener} defined in a TLD would not be able to
     *    use this method.
     * @throws IllegalStateException If the ServletContext has already been
     *         initialised
     *
     * @since Servlet 4.0
     */
    public void setRequestCharacterEncoding(String encoding);

    /**
     * Get the default character encoding for writing response bodies.
     *
     * @return The character encoding name or {@code null} if no default has
     *         been specified
     *
     * @throws UnsupportedOperationException    If called from a
     *    {@link ServletContextListener#contextInitialized(ServletContextEvent)}
     *    method of a {@link ServletContextListener} that was not defined in a
     *    web.xml file, a web-fragment.xml file nor annotated with
     *    {@link javax.servlet.annotation.WebListener}. For example, a
     *    {@link ServletContextListener} defined in a TLD would not be able to
     *    use this method.
     *
     * @since Servlet 4.0
     */
    public String getResponseCharacterEncoding();

    /**
     * Set the default character encoding to use for writing response bodies.
     * Calling this method will over-ride any value set in the deployment
     * descriptor.
     *
     * @param encoding The name of the character encoding to use
     *
     * @throws UnsupportedOperationException    If called from a
     *    {@link ServletContextListener#contextInitialized(ServletContextEvent)}
     *    method of a {@link ServletContextListener} that was not defined in a
     *    web.xml file, a web-fragment.xml file nor annotated with
     *    {@link javax.servlet.annotation.WebListener}. For example, a
     *    {@link ServletContextListener} defined in a TLD would not be able to
     *    use this method.
     * @throws IllegalStateException If the ServletContext has already been
     *         initialised
     *
     * @since Servlet 4.0
     */
    public void setResponseCharacterEncoding(String encoding);
}
