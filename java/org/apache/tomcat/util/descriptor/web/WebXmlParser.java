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
package org.apache.tomcat.util.descriptor.web;

import java.io.IOException;
import java.net.URL;

import org.apache.juli.logging.Log;
import org.apache.juli.logging.LogFactory;
import org.apache.tomcat.util.descriptor.DigesterFactory;
import org.apache.tomcat.util.descriptor.InputSourceUtil;
import org.apache.tomcat.util.descriptor.XmlErrorHandler;
import org.apache.tomcat.util.digester.Digester;
import org.apache.tomcat.util.res.StringManager;
import org.xml.sax.InputSource;
import org.xml.sax.SAXParseException;

public class WebXmlParser {

    private static final Log log = LogFactory.getLog(WebXmlParser.class);

    /**
     * The string resources for this package.
     */
    private static final StringManager sm =
        StringManager.getManager(Constants.PACKAGE_NAME);

    /**
     * The <code>Digester</code> we will use to process web application
     * deployment descriptor files.
     */
    private final Digester webDigester;
    private final WebRuleSet webRuleSet;

    /**
     * The <code>Digester</code> we will use to process web fragment
     * deployment descriptor files.
     */
    private final Digester webFragmentDigester;
    private final WebRuleSet webFragmentRuleSet;


    public WebXmlParser(boolean namespaceAware, boolean validation,
            boolean blockExternal) {

        // 设置web相关的xml文件的规则集合
        // 构造器入参为false，代表将要使用的规则非web-fragment.xml文件的规则
        // web.xml文件规则：org.apache.tomcat.util.digester.RuleSetBase.addRuleInstances
        webRuleSet = new WebRuleSet(false);

        // 新建web.xml文件转化器，在新建时会通过webRuleSet调用webDigester，将webRule应用到Digester中
        webDigester = DigesterFactory.newDigester(validation,
                namespaceAware, webRuleSet, blockExternal);

        // 生成转化器中的parser
        webDigester.getParser();

        // 生成web-fragment.xml文件相关的规则和转换器
        webFragmentRuleSet = new WebRuleSet(true);
        webFragmentDigester = DigesterFactory.newDigester(validation,
                namespaceAware, webFragmentRuleSet, blockExternal);
        webFragmentDigester.getParser();
    }

    /**
     * Parse a web descriptor at a location.
     *
     * @param url the location; if null no load will be attempted
     * @param dest the instance to be populated by the parse operation
     * @param fragment indicate if the descriptor is a web-app or web-fragment
     * @return true if the descriptor was successfully parsed
     * @throws IOException if there was a problem reading from the URL
     */
    public boolean parseWebXml(URL url, WebXml dest, boolean fragment) throws IOException {
        if (url == null) {
            return true;
        }
        InputSource source = new InputSource(url.toExternalForm());
        source.setByteStream(url.openStream());
        return parseWebXml(source, dest, fragment);
    }


    public boolean parseWebXml(InputSource source, WebXml dest,
            boolean fragment) {

        boolean ok = true;

        if (source == null) {
            return ok;
        }

        XmlErrorHandler handler = new XmlErrorHandler();

        Digester digester;
        WebRuleSet ruleSet;
        if (fragment) {
            digester = webFragmentDigester;
            ruleSet = webFragmentRuleSet;
        } else {
            digester = webDigester;
            ruleSet = webRuleSet;
        }

        // 设置xml解析后需要调用的目标对象（digester中会应用webRule，webRule会指定解析哪个xml文件节点，调用目标对象的哪个方法）
        // 比如web.xml和web-fragment.xml文件的解析规则在：org.apache.tomcat.util.descriptor.web.WebRuleSet.addRuleInstances
        digester.push(dest);
        digester.setErrorHandler(handler);

        if(log.isDebugEnabled()) {
            log.debug(sm.getString("webXmlParser.applicationStart",
                    source.getSystemId()));
        }

        try {

            // 解析web.xml/web-fragment.xml，获取规则
            digester.parse(source);

            if (handler.getWarnings().size() > 0 ||
                    handler.getErrors().size() > 0) {
                ok = false;
                handler.logFindings(log, source.getSystemId());
            }
        } catch (SAXParseException e) {
            log.error(sm.getString("webXmlParser.applicationParse",
                    source.getSystemId()), e);
            log.error(sm.getString("webXmlParser.applicationPosition",
                             "" + e.getLineNumber(),
                             "" + e.getColumnNumber()));
            ok = false;
        } catch (Exception e) {
            log.error(sm.getString("webXmlParser.applicationParse",
                    source.getSystemId()), e);
            ok = false;
        } finally {
            InputSourceUtil.close(source);
            digester.reset();
            ruleSet.recycle();
        }

        return ok;
    }


    /**
     * Sets the ClassLoader to be used for creating descriptor objects.
     * @param classLoader the ClassLoader to be used for creating descriptor objects
     */
    public void setClassLoader(ClassLoader classLoader) {
        webDigester.setClassLoader(classLoader);
        webFragmentDigester.setClassLoader(classLoader);
    }
}