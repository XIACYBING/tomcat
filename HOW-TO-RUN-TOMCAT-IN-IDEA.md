# 如何改造Tomcat项目，并在IDEA上运行

## 第一步
从`github`上`clone` `tomcat`项目，项目文件夹名默认为`tomcat`

## 第二步
执行命令`git checkout -b 8.5.20 8.5.20`，从`8.5.20`的`tag`上切出一条分支来，也命名为`8.5.20`

## 第三步
先不在IDEA中打开`tomcat`，在项目目录`tomcat`下添加`pom`文件，文件内容为：
```XML
<?xml version="1.0" encoding="UTF-8"?>
<project xmlns="http://maven.apache.org/POM/4.0.0"
         xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
         xsi:schemaLocation="http://maven.apache.org/POM/4.0.0 http://maven.apache.org/xsd/maven-4.0.0.xsd">

    <modelVersion>4.0.0</modelVersion>
    <groupId>org.apache</groupId>
    <artifactId>tomcat</artifactId>
    <name>tomcat</name>
    <version>8.0</version>

    <modules>
        <!--<module>web-module-one</module>-->
    </modules>

    <build>
        <finalName>Tomcat8.0</finalName>
        <sourceDirectory>java</sourceDirectory>
        <testSourceDirectory>test</testSourceDirectory>
        <resources>
            <resource>
                <directory>java</directory>
            </resource>
        </resources>
        <testResources>
            <testResource>
                <directory>test</directory>
            </testResource>
        </testResources>
        <plugins>
            <plugin>
                <groupId>org.apache.maven.plugins</groupId>
                <artifactId>maven-compiler-plugin</artifactId>
                <version>2.0.2</version>

                <configuration>
                    <encoding>UTF-8</encoding>
                    <source>1.8</source>
                    <target>1.8</target>
                </configuration>
            </plugin>
        </plugins>
    </build>

    <dependencies>
        <dependency>
            <groupId>org.easymock</groupId>
            <artifactId>easymock</artifactId>
            <version>3.5</version>
            <scope>test</scope>
        </dependency>

        <dependency>
            <groupId>junit</groupId>
            <artifactId>junit</artifactId>
            <version>4.12</version>
            <scope>test</scope>
        </dependency>
        <dependency>
            <groupId>ant</groupId>
            <artifactId>ant</artifactId>
            <version>1.7.0</version>
        </dependency>
        <dependency>
            <groupId>wsdl4j</groupId>
            <artifactId>wsdl4j</artifactId>
            <version>1.6.2</version>
        </dependency>
        <dependency>
            <groupId>javax.xml</groupId>
            <artifactId>jaxrpc</artifactId>
            <version>1.1</version>
        </dependency>
        <dependency>
            <groupId>org.eclipse.jdt.core.compiler</groupId>
            <artifactId>ecj</artifactId>
            <version>4.6.1</version>
        </dependency>
    </dependencies>

</project>
```
## 第四步
在`org.apache.catalina.startup.ContextConfig.configureStart`方法中，`webConfig();`语句后，添加`context.addServletContainerInitializer(new JasperInitializer(), null);`语句

## 第五步
找到`org.apache.catalina.startup.Bootstrap.main`，编辑运行配置，在`VM option`中添加如下参数：
```
-Dcatalina.home=
-Dcatalina.base=
-Djava.endorsed.dirs=endorsed
-Djava.io.tmpdir=temp
-Djava.util.logging.manager=org.apache.juli.ClassLoaderLogManager
-Djava.util.logging.config.file=conf/logging.properties
```

## 第六步
执行`org.apache.catalina.startup.Bootstrap`的`main`函数，查看控制台输出，等待`[main] org.apache.catalina.startup.Catalina.start Server startup in 683 ms`输出后，即可访问`localhost:8080`，然后就会出现`tomcat`的主页