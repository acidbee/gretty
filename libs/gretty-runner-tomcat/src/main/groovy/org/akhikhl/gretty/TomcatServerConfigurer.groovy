/*
 * gretty
 *
 * Copyright 2013  Andrey Hihlovskiy.
 *
 * See the file "license.txt" for copying and usage permission.
 */
package org.akhikhl.gretty

import org.apache.catalina.Host
import org.apache.catalina.Lifecycle
import org.apache.catalina.LifecycleEvent
import org.apache.catalina.LifecycleListener
import org.apache.catalina.authenticator.SingleSignOn
import org.apache.catalina.connector.Connector
import org.apache.catalina.core.StandardContext
import org.apache.catalina.loader.WebappLoader
import org.apache.catalina.realm.MemoryRealm
import org.apache.catalina.startup.Catalina
import org.apache.catalina.startup.ContextConfig
import org.apache.catalina.startup.Tomcat
import org.apache.catalina.startup.Tomcat.DefaultWebXmlListener
import org.apache.catalina.startup.Tomcat.FixContextListener
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import org.xml.sax.InputSource

/**
 *
 * @author akhikhl
 */
class TomcatServerConfigurer {

  protected final Logger log

  TomcatServerConfigurer() {
    log = LoggerFactory.getLogger(this.getClass())
  }

  Tomcat createAndConfigureServer(TomcatConfigurer configurer, Map params, Closure configureContext = null) {

    Tomcat tomcat = new Tomcat()

    File baseDir = new File(params.baseDir)
    new File(baseDir, 'webapps').mkdirs()
    tomcat.setBaseDir(baseDir.absolutePath)
    
    def service
    def connectors
    
    if(params.serverConfigFile) {
      def catalina = new Catalina()
      def digester = catalina.createStartDigester()
      new File(params.serverConfigFile).withInputStream {
        def inputSource = new InputSource(params.serverConfigFile)
        inputSource.setByteStream(it)
        digester.push(catalina)
        digester.parse(inputSource)
      }
      def server = tomcat.server = catalina.getServer()
      def services = server.findServices()
      assert services.length == 1
      service = services[0]
      tomcat.service = service
      tomcat.engine = service.getContainer()
      connectors = service.findConnectors()
      tomcat.host = service.getContainer().findChildren().find { it instanceof Host }
      tomcat.port = connectors[0].port
      tomcat.hostname = tomcat.host.name
      server.setCatalina(catalina)
      server.setCatalinaHome(baseDir)
      server.setCatalinaBase(baseDir)
    } else {
      tomcat.engine.backgroundProcessorDelay = -1
      tomcat.host.autoDeploy = true
      service = tomcat.service
      connectors = service.findConnectors()
    }

    if(!tomcat.hostname)
      tomcat.hostname = params.host ?: 'localhost'
    
    Connector httpConn = connectors.find { it.scheme == 'http' }
    
    if(params.httpEnabled) {
      boolean newConnector = false
      if(httpConn) {
        if(params.httpPort)
          httpConn.port = params.httpPort
      } else {
        newConnector = true
        httpConn = new Connector('HTTP/1.1')
        httpConn.scheme = 'http'
        httpConn.setProperty('maxPostSize', '0')  // unlimited
        httpConn.port = params.httpPort ?: 8080
      }
      if(params.httpIdleTimeout)
        httpConn.setProperty('keepAliveTimeout', params.httpIdleTimeout)
      if(newConnector)
        service.addConnector(httpConn)
    }
    
    Connector httpsConn = service.findConnectors().find { it.scheme == 'https' }

    if(params.httpsEnabled) {
      boolean newConnector = false
      if(httpsConn) {
        if(params.httpsPort)
          httpsConn.port = params.httpsPort
      } else {
        newConnector = true
        httpsConn = new Connector('HTTP/1.1')
        httpsConn.scheme = 'https'
        httpsConn.secure = true
        httpsConn.setProperty('SSLEnabled', 'true')
        httpsConn.setProperty('maxPostSize', '0')  // unlimited
        httpsConn.port = params.httpsPort ?: 8443
      }
      if(params.sslKeyManagerPassword)
        httpsConn.setProperty('keyPass', params.sslKeyManagerPassword)
      if(params.sslKeyStorePath)
        httpsConn.setProperty('keystoreFile', params.sslKeyStorePath)
      if(params.sslKeyStorePassword)
        httpsConn.setProperty('keystorePass', params.sslKeyStorePassword)
      if(params.sslTrustStorePath)
        httpsConn.setProperty('truststoreFile', params.sslTrustStorePath)
      if(params.sslTrustStorePassword)
        httpsConn.setProperty('truststorePass', params.sslTrustStorePassword)
      if(params.httpsIdleTimeout)
        httpsConn.setProperty('keepAliveTimeout', params.httpsIdleTimeout)
      if(newConnector)
        service.addConnector(httpsConn)
    }

    if(httpConn && httpsConn)
      httpConn.redirectPort = httpsConn.port
    
    if(httpConn)
      tomcat.setConnector(httpConn)
    else if(httpsConn)
      tomcat.setConnector(httpsConn)
    else if(connectors.length != 0)
      tomcat.setConnector(connectors[0])

    if(params.singleSignOn && !tomcat.host.getValves().find { it instanceof SingleSignOn })
      tomcat.host.addValve(new SingleSignOn())

    for(def webapp in params.webApps) {
      StandardContext context = params.contextClass ? params.contextClass.newInstance() : new StandardContext()
      context.setName(webapp.contextPath)
      context.setPath(webapp.contextPath)
      context.setDocBase(webapp.resourceBase)
      // context.setLogEffectiveWebXml(true) // enable for debugging webxml merge
      ClassLoader parentClassLoader = params.parentClassLoader ?: this.getClass().getClassLoader()
      URL[] classpathUrls = (webapp.webappClassPath ?: []).collect { new URL(it) } as URL[]
      ClassLoader classLoader = new URLClassLoader(classpathUrls, parentClassLoader)
      context.addLifecycleListener(new SpringloadedCleanup())
      context.setParentClassLoader(classLoader)
      context.setJarScanner(configurer.createJarScanner(context.getJarScanner(), new JarSkipPatterns()))
      WebappLoader loader = new WebappLoader(classLoader)
      loader.setLoaderClass(TomcatEmbeddedWebappClassLoader.class.getName())
      loader.setDelegate(true)
      context.setLoader(loader)

      def realmConfigFile = webapp.realmConfigFile ?: params.realmConfigFile
      if(realmConfigFile && new File(realmConfigFile).exists()) {
        log.warn '{} -> realm config {}', webapp.contextPath, realmConfigFile
        def realm = new MemoryRealm()
        realm.setPathname(realmConfigFile)
        context.setRealm(realm)
      } else
        context.addLifecycleListener(new FixContextListener())

      context.addLifecycleListener(configurer.createContextConfig(classpathUrls))

      if(configureContext)
        configureContext(webapp, context)

      if(!context.findChild('default'))
        context.addLifecycleListener(new DefaultWebXmlListener())

      if(log.isDebugEnabled())
        context.addLifecycleListener(new LifecycleListener() {
          @Override
          public void lifecycleEvent(LifecycleEvent event) {
            if (event.type == Lifecycle.CONFIGURE_START_EVENT) {
              def pipeline = context.getPipeline()
              log.debug 'START: context={}, pipeline: {} #{}', context.path, pipeline, System.identityHashCode(pipeline)
              log.debug '  valves:'
              for(def v in pipeline.getValves())
                log.debug '    {} #{}', v, System.identityHashCode(v)
            }
          }
        })

      tomcat.host.addChild(context)
    }

    tomcat
  }
}
