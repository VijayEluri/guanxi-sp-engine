/* CVS Header
   $Id$
   $Log$
   Revision 1.1.1.1  2008/01/23 15:30:57  alistairskye
   Standalone Engine module

*/

package org.guanxi.sp.engine.service;

import org.springframework.web.context.ServletContextAware;
import org.springframework.web.servlet.ModelAndView;
import org.springframework.web.servlet.mvc.AbstractController;
import org.guanxi.common.definitions.Guanxi;
import org.guanxi.common.definitions.Shibboleth;
import org.guanxi.common.GuanxiException;
import org.guanxi.common.Utils;
import org.guanxi.common.EntityConnection;
import org.guanxi.common.log.Log4JLoggerConfig;
import org.guanxi.common.log.Log4JLogger;
import org.guanxi.xal.saml_2_0.metadata.EntityDescriptorType;
import org.guanxi.xal.saml2.metadata.GuardRoleDescriptorExtensions;
import org.guanxi.xal.saml_1_0.protocol.*;
import org.guanxi.xal.saml_1_0.assertion.SubjectType;
import org.guanxi.xal.saml_1_0.assertion.NameIdentifierType;
import org.guanxi.xal.soap.EnvelopeDocument;
import org.guanxi.xal.soap.Envelope;
import org.guanxi.xal.soap.Body;
import org.guanxi.xal.soap.Header;
import org.guanxi.sp.Util;
import org.guanxi.sp.engine.Config;
import org.apache.log4j.Logger;
import org.apache.xmlbeans.XmlException;
import org.apache.xmlbeans.XmlOptions;
import org.w3c.dom.Element;
import org.w3c.dom.Node;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.*;
import java.math.BigInteger;
import java.util.Calendar;
import java.util.TimeZone;
import java.util.HashMap;

public class AuthConsumerService extends AbstractController implements ServletContextAware {
  /** Our logger */
  private Logger log = null;
  /** The logger config */
  private Log4JLoggerConfig loggerConfig = null;
  /** The Logging setup to use */
  private Log4JLogger logger = null;
  /** The full/path name of our log file */
  private String logFile = null;
  /** The view to redirect to if no error occur */
  private String podderView = null;
  /** The view to use to display any errors */
  private String errorView = null;
  /** The variable to use in the error view to display the error */
  private String errorViewDisplayVar = null;

  public void init() {
    try {
      loggerConfig.setClazz(AuthConsumerService.class);

      // Sort out the file paths for logging
      loggerConfig.setLogConfigFile(getServletContext().getRealPath(loggerConfig.getLogConfigFile()));
      loggerConfig.setLogFile(getServletContext().getRealPath(loggerConfig.getLogFile()));

      // Get our logger
      log = logger.initLogger(loggerConfig);
    }
    catch(GuanxiException e) {
    }
  } //init

  /**
   * Cleans up when the system shuts down
   */
  public void destroy() {
  } // destroy

  public ModelAndView handleRequestInternal(HttpServletRequest request, HttpServletResponse response) throws Exception {
    ModelAndView mAndV = new ModelAndView();

    /* When a Guard initially set up a session with the Engine, it passed its session ID to
     * the Engine's WAYF Location web service. The Guard then passed the session ID to the
     * WAYF/IdP via the target parameter. So now it should come back here and we can
     * identify the Guard that we're working on behalf of.
     */
    String guardSession = request.getParameter(Shibboleth.TARGET_FORM_PARAM);

    /* When the Engine received the Guard's session, it munged it to an Engine session and
     * associated the Guard session ID with the Guard's ID. So now dereference the Guard's
     * session ID to get its ID and load it's metadata
     */
    EntityDescriptorType guardEntityDescriptor = (EntityDescriptorType)getServletContext().getAttribute(guardSession.replaceAll("GUARD", "ENGINE"));
    GuardRoleDescriptorExtensions guardNativeMetadata = Util.getGuardNativeMetadata(guardEntityDescriptor);

    // POST INTERCEPTOR

    // Build a SAML Request to get attributes from the IdP
    RequestDocument samlRequestDoc = RequestDocument.Factory.newInstance();
    RequestType samlRequest = samlRequestDoc.addNewRequest();
    samlRequest.setRequestID(Utils.createNCNameID());
    samlRequest.setMajorVersion(new BigInteger("1"));
    samlRequest.setMinorVersion(new BigInteger("1"));
    Calendar now = Calendar.getInstance(TimeZone.getTimeZone("GMT"));
    now.clear(Calendar.MILLISECOND);
    samlRequest.setIssueInstant(now);

    // Add an attribute query to the SAML request
    AttributeQueryType attrQuery = samlRequest.addNewAttributeQuery();
    attrQuery.setResource(guardEntityDescriptor.getEntityID());
    SubjectType subject = attrQuery.addNewSubject();
    NameIdentifierType nameID = subject.addNewNameIdentifier();
    nameID.setFormat(Shibboleth.NS_NAME_IDENTIFIER);
    nameID.setNameQualifier((String)request.getAttribute(Config.REQUEST_ATTRIBUTE_IDP_PROVIDER_ID));
    nameID.setStringValue((String)request.getAttribute(Config.REQUEST_ATTRIBUTE_IDP_NAME_IDENTIFIER));

    // Put the SAML request and attribute query in a SOAP message
    EnvelopeDocument soapEnvelopeDoc = EnvelopeDocument.Factory.newInstance();
    Envelope soapEnvelope = soapEnvelopeDoc.addNewEnvelope();
    Body soapBody = soapEnvelope.addNewBody();

    soapBody.getDomNode().appendChild(soapBody.getDomNode().getOwnerDocument().importNode(samlRequest.getDomNode(), true));

    // Initialise the SAML request to the IdP's AA
    EntityDescriptorType idpMetadata = (EntityDescriptorType)request.getAttribute(Config.REQUEST_ATTRIBUTE_IDP_METADATA);
    EntityConnection aaConnection = null;
    Config config = (Config)getServletContext().getAttribute(Guanxi.CONTEXT_ATTR_ENGINE_CONFIG);
    try {
      aaConnection = new EntityConnection(idpMetadata.getAttributeAuthorityDescriptorArray()[0].getAttributeServiceArray()[0].getLocation(),
                                          guardEntityDescriptor.getEntityID(),
                                          guardNativeMetadata.getKeystore(),
                                          guardNativeMetadata.getKeystorePassword(),
                                          config.getTrustStore(),
                                          config.getTrustStorePassword(),
                                          EntityConnection.PROBING_OFF);
      aaConnection.setDoOutput(true);
      aaConnection.setRequestProperty("Content-type", "text/xml");
      aaConnection.connect();

      // Send the SOAP message to the IdP's AA...
      soapEnvelopeDoc.save(aaConnection.getOutputStream());
      // ...and read the SAML Response. XMLBeans 2.2.0 has problems parsing from an InputStream though
      InputStream in = aaConnection.getInputStream();
      BufferedReader buffer = new BufferedReader(new InputStreamReader(in));
      StringBuffer stringBuffer = new StringBuffer();
      String line = null;
      while ((line = buffer.readLine()) != null) {
        stringBuffer.append(line);
      }
      in.close();

      soapEnvelopeDoc = EnvelopeDocument.Factory.parse(stringBuffer.toString());

      soapEnvelope = soapEnvelopeDoc.getEnvelope();
    }
    catch(GuanxiException ge) {
      log.error("AA connection error", ge);
      mAndV.setViewName(errorView);
      mAndV.getModel().put(errorViewDisplayVar, ge.getMessage());
      return mAndV;
    }
    catch(XmlException xe) {
      log.error("AA SAML Response parse error", xe);
      mAndV.setViewName(errorView);
      mAndV.getModel().put(errorViewDisplayVar, xe.getMessage());
      return mAndV;
    }

    // Before we send the SAML Response from the AA to the Guard, add the Guanxi SOAP header
    Header soapHeader = soapEnvelope.addNewHeader();
    Element gx = soapHeader.getDomNode().getOwnerDocument().createElementNS("urn:guanxi:sp", "GuanxiGuardSessionID");
    Node gxNode = soapHeader.getDomNode().appendChild(gx);
    org.w3c.dom.Text gxTextNode = soapHeader.getDomNode().getOwnerDocument().createTextNode(guardSession);
    gxNode.appendChild(gxTextNode);

    // Add the SAML Response from the IdP to the SOAP headers
    Header authHeader = soapEnvelope.addNewHeader();
    Element auth = authHeader.getDomNode().getOwnerDocument().createElementNS("urn:guanxi:sp", "AuthnFromIdP");
    auth.setAttribute("aa", idpMetadata.getAttributeAuthorityDescriptorArray()[0].getAttributeServiceArray()[0].getLocation());
    Node authNode = authHeader.getDomNode().appendChild(auth);
    authNode.appendChild(authNode.getOwnerDocument().importNode(((ResponseType)request.getAttribute(Config.REQUEST_ATTRIBUTE_SAML_RESPONSE)).getDomNode(), true));

    HashMap namespaces = new HashMap();
    namespaces.put(Shibboleth.NS_SAML_10_PROTOCOL, Shibboleth.NS_PREFIX_SAML_10_PROTOCOL);
    namespaces.put(Shibboleth.NS_SAML_10_ASSERTION, Shibboleth.NS_PREFIX_SAML_10_ASSERTION);
    namespaces.put(Guanxi.NS_SP_NAME_IDENTIFIER, "gxsp");
    XmlOptions xmlOptions = new XmlOptions();
    xmlOptions.setSavePrettyPrint();
    xmlOptions.setSavePrettyPrintIndent(2);
    xmlOptions.setUseDefaultNamespace();
    xmlOptions.setSaveAggressiveNamespaces();
    xmlOptions.setSaveSuggestedPrefixes(namespaces);
    xmlOptions.setSaveNamespacesFirst();

    String soapResponseFromACS = null;
    try {
      // Initialise the connection to the Guard's attribute consumer service
      EntityConnection guardConnection = new EntityConnection(guardNativeMetadata.getAttributeConsumerServiceURL(),
                                                              guardEntityDescriptor.getEntityID(),
                                                              guardNativeMetadata.getKeystore(),
                                                              guardNativeMetadata.getKeystorePassword(),
                                                              config.getTrustStore(),
                                                              config.getTrustStorePassword(),
                                                              EntityConnection.PROBING_OFF);
      guardConnection.setDoOutput(true);
      guardConnection.connect();

      // Send the AA's SAML Response as-is to the Guard's attribute consumer service...
      soapEnvelopeDoc.save(guardConnection.getOutputStream());
      // ...and read the response from the Guard
      BufferedInputStream bin = new BufferedInputStream(guardConnection.getInputStream());
      byte[] bytes = new byte[bin.available()];
      bin.read(bytes);
      bin.close();
      soapResponseFromACS = new String(bytes);
      soapEnvelopeDoc = EnvelopeDocument.Factory.parse(soapResponseFromACS);
    }
    catch(GuanxiException ge) {
      log.error("Guard ACS connection error", ge);
      mAndV.setViewName(errorView);
      mAndV.getModel().put(errorViewDisplayVar, ge.getMessage());
      return mAndV;
    }
    catch(XmlException xe) {
      log.error("Guard ACS response parse error", xe);
      log.error("SOAP response:");
      log.error("------------------------------------");
      log.error(soapResponseFromACS);
      log.error("------------------------------------");
      mAndV.setViewName(errorView);
      mAndV.getModel().put(errorViewDisplayVar, xe.getMessage());
      return mAndV;
    }

    // Engine is now finished so redirect to the Guard's Podder for browser control
    mAndV.setViewName(podderView);
    mAndV.getModel().put("podderURL", guardNativeMetadata.getPodderURL() + "?id=" + guardSession);
    return mAndV;
  } // handleRequestInternal

  public Log4JLoggerConfig getLoggerConfig() {
    return loggerConfig;
  }

  public void setLoggerConfig(Log4JLoggerConfig loggerConfig) {
    this.loggerConfig = loggerConfig;
  }

  public Log4JLogger getLogger() {
    return logger;
  }

  public void setLogger(Log4JLogger logger) {
    this.logger = logger;
  }

  public String getLogFile() {
    return logFile;
  }

  public void setLogFile(String logFile) {
    this.logFile = logFile;
  }

  public String getPodderView() {
    return podderView;
  }

  public void setPodderView(String podderView) {
    this.podderView = podderView;
  }

  public String getErrorView() {
    return errorView;
  }

  public void setErrorView(String errorView) {
    this.errorView = errorView;
  }

  public void setErrorViewDisplayVar(String errorViewDisplayVar) {
    this.errorViewDisplayVar = errorViewDisplayVar;
  }
}