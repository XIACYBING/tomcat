/*
 *  Licensed to the Apache Software Foundation (ASF) under one or more
 *  contributor license agreements.  See the NOTICE file distributed with
 *  this work for additional information regarding copyright ownership.
 *  The ASF licenses this file to You under the Apache License, Version 2.0
 *  (the "License"); you may not use this file except in compliance with
 *  the License.  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 */
package org.apache.coyote;

import org.apache.catalina.Context;
import org.apache.catalina.Host;
import org.apache.catalina.Pipeline;
import org.apache.catalina.Valve;
import org.apache.catalina.Wrapper;
import org.apache.catalina.connector.CoyoteAdapter;
import org.apache.catalina.mapper.Mapper;
import org.apache.coyote.http11.Http11InputBuffer;
import org.apache.tomcat.util.net.SocketEvent;

import javax.servlet.Servlet;

/**
 * Adapter. This represents the entry point in a coyote-based servlet container.
 *
 * 适配器Adapter的主要作用：是接收Tomcat的请求{@link Request}，转换成标准的Servlet请求{@link javax.servlet.ServletRequest}
 *
 * 1、映射{@link Host}、{@link Context}、{@link Wrapper}和{@link Servlet}：{@link CoyoteAdapter#service} ->
 * {@link CoyoteAdapter#postParseRequest} ->
 * {@link Mapper#map(org.apache.tomcat.util.buf.MessageBytes, org.apache.tomcat.util.buf.MessageBytes, java.lang.String, org.apache.catalina.mapper.MappingData)}
 *
 * 2、转换请求：{@link CoyoteAdapter#service}
 *  2.1、请求头的转换：请求头实际存储在{@link Request#headers}中，但是会在{@link Http11InputBuffer#Http11InputBuffer}中
 *  通过{@code headers = request.getMimeHeaders();}赋值给{@link Http11InputBuffer#headers}，最后在{@link Http11InputBuffer#parseHeader()}进行请求头的转换和赋值
 *
 * 3、处理请求：在{@link CoyoteAdapter#service}中会转换请求，并映射容器（{@link Host}、{@link Context}、{@link Wrapper}
 * ），容器映射完成后，会获取容器内的{@link Pipeline}进行处理，容器对请求的处理一般都是在{@link Pipeline}的{@link Valve}中处理的，比如
 * {@link org.apache.catalina.core.StandardHostValve}、{@link org.apache.catalina.core.StandardContextValve}、{@link org.apache.catalina.core.StandardWrapperValve}
 *
 * @author Remy Maucherat
 * @see ProtocolHandler
 * @see CoyoteAdapter
 */
public interface Adapter {

    /**
     * Call the service method, and notify all listeners
     *
     * @param req The request object
     * @param res The response object
     *
     * @exception Exception if an error happens during handling of
     *   the request. Common errors are:
     *   <ul><li>IOException if an input/output error occurs and we are
     *   processing an included servlet (otherwise it is swallowed and
     *   handled by the top level error handler mechanism)
     *       <li>ServletException if a servlet throws an exception and
     *  we are processing an included servlet (otherwise it is swallowed
     *  and handled by the top level error handler mechanism)
     *  </ul>
     *  Tomcat should be able to handle and log any other exception ( including
     *  runtime exceptions )
     */
    public void service(Request req, Response res) throws Exception;

    /**
     * Prepare the given request/response for processing. This method requires
     * that the request object has been populated with the information available
     * from the HTTP headers.
     *
     * @param req The request object
     * @param res The response object
     *
     * @return <code>true</code> if processing can continue, otherwise
     *         <code>false</code> in which case an appropriate error will have
     *         been set on the response
     *
     * @throws Exception If the processing fails unexpectedly
     */
    public boolean prepare(Request req, Response res) throws Exception;

    public boolean asyncDispatch(Request req,Response res, SocketEvent status)
            throws Exception;

    public void log(Request req, Response res, long time);

    /**
     * Assert that request and response have been recycled. If they have not
     * then log a warning and force a recycle. This method is called as a safety
     * check when a processor is being recycled and may be returned to a pool
     * for reuse.
     *
     * @param req
     *            Request
     * @param res
     *            Response
     */
    public void checkRecycled(Request req, Response res);

    /**
     * Provide the name of the domain to use to register MBeans for components
     * associated with the connector.
     *
     * @return  The MBean domain name
     */
    public String getDomain();
}
