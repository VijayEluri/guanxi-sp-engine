//: "The contents of this file are subject to the Mozilla Public License
//: Version 1.1 (the "License"); you may not use this file except in
//: compliance with the License. You may obtain a copy of the License at
//: http://www.mozilla.org/MPL/
//:
//: Software distributed under the License is distributed on an "AS IS"
//: basis, WITHOUT WARRANTY OF ANY KIND, either express or implied. See the
//: License for the specific language governing rights and limitations
//: under the License.
//:
//: The Original Code is Guanxi (http://www.guanxi.uhi.ac.uk).
//:
//: The Initial Developer of the Original Code is Alistair Young alistair@codebrane.com
//: All Rights Reserved.
//:

package org.guanxi.sp.engine.service.saml2;

import org.springframework.web.servlet.mvc.multiaction.MultiActionController;
import org.springframework.web.servlet.ModelAndView;
import org.springframework.web.context.ServletContextAware;
import org.springframework.context.MessageSource;
import org.apache.log4j.Logger;
import org.apache.xmlbeans.XmlOptions;
import org.guanxi.common.entity.EntityFarm;
import org.guanxi.common.entity.EntityManager;
import org.guanxi.common.definitions.Guanxi;
import org.guanxi.common.definitions.SAML;
import org.guanxi.common.metadata.Metadata;
import org.guanxi.common.Utils;
import org.guanxi.common.security.SecUtilsConfig;
import org.guanxi.xal.saml_2_0.metadata.EntityDescriptorType;
import org.guanxi.xal.saml_2_0.metadata.EndpointType;
import org.guanxi.xal.saml_2_0.protocol.AuthnRequestDocument;
import org.guanxi.xal.saml_2_0.protocol.AuthnRequestType;
import org.guanxi.xal.saml_2_0.assertion.NameIDType;
import org.guanxi.xal.saml2.metadata.GuardRoleDescriptorExtensions;
import org.guanxi.sp.Util;
import org.w3c.dom.Document;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.util.HashMap;
import java.util.Calendar;
import java.net.URLEncoder;
import java.io.UnsupportedEncodingException;

/**
 * Initiates a SAML2 Web Browser SSO session with an IdP
 *
 * @author alistair
 */
public class WebBrowserSSOService extends MultiActionController implements ServletContextAware {
  private static final Logger logger = Logger.getLogger(WebBrowserSSOService.class.getName());

  /** The localised messages to use */
  private MessageSource messages = null;
  /** The JSP to use to POST the AuthnRequest to the IdP */
  private String httpPOSTView = null;
  /** The JSP to use to GET the AuthnRequest to the IdP */
  private String httpRedirectView = null;
  /** The JSP to use to display any errors */
  private String errorView = null;
  /** The request attribute that holds the error message for the error view */
  private String errorViewDisplayVar = null;

  public void init() {}

  public ModelAndView wbsso(HttpServletRequest request, HttpServletResponse response) {
    ModelAndView mAndV = new ModelAndView();
    String entityID = request.getParameter("entityID");

    // Guard verification
    String guardID = request.getParameter(Guanxi.WAYF_PARAM_GUARD_ID);
    String guardSessionID = request.getParameter(Guanxi.WAYF_PARAM_SESSION_ID);
    String binding = request.getParameter(Guanxi.WAYF_PARAM_GUARD_BINDING);

    // Get the Guard's metadata, previously loaded by the Bootstrapper
    EntityDescriptorType guardEntityDescriptor = (EntityDescriptorType)getServletContext().getAttribute(guardID);
    if (guardEntityDescriptor == null) {
      logger.error("Guard '" + guardID + "' not found in metadata repository");
      mAndV.getModel().put(errorViewDisplayVar, messages.getMessage("engine.error.no.guard.metadata",
                                                                    null, request.getLocale()));
      return mAndV;
    }

    String relayState = guardSessionID.replaceAll("GUARD", "ENGINE");
    getServletContext().setAttribute(relayState, guardEntityDescriptor);

    if (request.getParameter("entityID") == null) {
      logger.error("no entityID");
      mAndV.setViewName(errorView);
      mAndV.getModel().put(errorViewDisplayVar, messages.getMessage("engine.error.missing.entityid.param",
                                                                    null, request.getLocale()));
      return mAndV;
    }

    // Load the metadata for the IdP
    EntityFarm farm = (EntityFarm)getServletContext().getAttribute(Guanxi.CONTEXT_ATTR_ENGINE_ENTITY_FARM);
    EntityManager manager = farm.getEntityManagerForID(entityID);
    if (manager == null) {
      logger.error("Could not find manager for IdP '" + entityID);
      mAndV.setViewName(errorView);
      mAndV.getModel().put(errorViewDisplayVar, messages.getMessage("engine.error.no.idp.metadata",
                                                                    new Object[]{entityID},
                                                                    request.getLocale()));
      return mAndV;
    }
    Metadata entityMetadata = manager.getMetadata(entityID);
    if (entityMetadata == null) {
      logger.error("Could not find manager for IdP " + entityID);
      mAndV.setViewName(errorView);
      mAndV.getModel().put(errorViewDisplayVar, messages.getMessage("engine.error.no.idp.metadata",
                                                                    new Object[]{entityID},
                                                                    request.getLocale()));
      return mAndV;
    }
    EntityDescriptorType saml2Metadata = (EntityDescriptorType)entityMetadata.getPrivateData();

    // Get the WBSSO profile endpoint at the IdP
    String bindingURN = null;
    if (binding == null) {
      bindingURN = "urn:oasis:names:tc:SAML:2.0:bindings:HTTP-POST";
      binding = "http-post";
    }
    else {
      if (binding.equals("http-post")) {
        bindingURN = "urn:oasis:names:tc:SAML:2.0:bindings:HTTP-POST";
      }
      else if (binding.equals("http-redirect")) {
        bindingURN = "urn:oasis:names:tc:SAML:2.0:bindings:HTTP-Redirect";
      }
    }
    String wbssoURL = null;
    EndpointType[] ssos = saml2Metadata.getIDPSSODescriptorArray(0).getSingleSignOnServiceArray();
    for (EndpointType sso : ssos) {
      if (sso.getBinding().equalsIgnoreCase(bindingURN)) {
        wbssoURL = sso.getLocation();
      }
    }
    if (wbssoURL == null) {
      logger.error("IdP does not support WBSSO" + entityID);
      mAndV.setViewName(errorView);
      mAndV.getModel().put(errorViewDisplayVar, messages.getMessage("engine.error.profile.not.supported",
                                                                    null, request.getLocale()));
      return mAndV;
    }

    // Create an AuthnRequest
    AuthnRequestDocument authnRequestDoc = AuthnRequestDocument.Factory.newInstance();
    AuthnRequestType authnRequest = authnRequestDoc.addNewAuthnRequest();
    authnRequest.setID(Utils.createNCNameID());
    authnRequest.setVersion("2.0");
    authnRequest.setIssueInstant(Calendar.getInstance());
    Utils.zuluXmlObject(authnRequest, 0);
    NameIDType issuer = NameIDType.Factory.newInstance();
    issuer.setStringValue(guardID);
    authnRequest.setIssuer(issuer);
    // Only if signed
    //authnRequest.setDestination("https://sgarbh.smo.uhi.ac.uk:8443/idp/profile/SAML2/POST/SSO");

    // Sort out the namespaces for saving the Response
    HashMap<String, String> namespaces = new HashMap<String, String>();
    namespaces.put(SAML.NS_SAML_20_PROTOCOL, SAML.NS_PREFIX_SAML_20_PROTOCOL);
    namespaces.put(SAML.NS_SAML_20_ASSERTION, SAML.NS_PREFIX_SAML_20_ASSERTION);
    
    XmlOptions xmlOptions = new XmlOptions();
    xmlOptions.setSavePrettyPrint();
    xmlOptions.setSavePrettyPrintIndent(2);
    xmlOptions.setUseDefaultNamespace();
    xmlOptions.setSaveAggressiveNamespaces();
    xmlOptions.setSaveSuggestedPrefixes(namespaces);
    xmlOptions.setSaveNamespacesFirst();

    // Get the Guard's native metadata
    GuardRoleDescriptorExtensions guardNativeMetadata = Util.getGuardNativeMetadata(guardEntityDescriptor);

    // Get the config ready for signing
    SecUtilsConfig secUtilsConfig = new SecUtilsConfig();
    secUtilsConfig.setKeystoreFile(guardNativeMetadata.getKeystore());
    secUtilsConfig.setKeystorePass(guardNativeMetadata.getKeystorePassword());
    secUtilsConfig.setKeystoreType("JKS");
    secUtilsConfig.setPrivateKeyAlias(guardID);
    secUtilsConfig.setPrivateKeyPass(guardNativeMetadata.getKeystorePassword());
    secUtilsConfig.setCertificateAlias(guardID);

    // Break out to DOM land to get the SAML Response signed...
    /*
    Document signedDoc = null;
    try {
      // Need to use newDomNode to preserve namespace information
      signedDoc = SecUtils.getInstance().sign(secUtilsConfig, (Document)authnRequestDoc.newDomNode(xmlOptions), "");
      // ...and go back to XMLBeans land when it's ready
      authnRequestDoc = AuthnRequestDocument.Factory.parse(signedDoc);
    }
    catch(GuanxiException ge) {
      logger.error("Could not sign AuthnRequest", ge);
      mAndV.setViewName(errorView);
      mAndV.getModel().put(errorViewDisplayVar, messages.getMessage("engine.error.could.not.sign.message",
                                                                    null, request.getLocale()));
      return mAndV;
    }
    catch(XmlException xe) {
      logger.error("Couldn't convert signed AuthnRequest back to XMLBeans", xe);
      mAndV.setViewName(errorView);
      mAndV.getModel().put(errorViewDisplayVar, messages.getMessage("engine.error.could.not.sign.message",
                                                                    null, request.getLocale()));
      return mAndV;
    }
    */

    // Base 64 encode the AuthnRequest
    //String authnRequestB64 = Utils.base64(signedDoc);
    //String authnRequestB64 = Utils.base64((Document)authnRequestDoc.newDomNode(xmlOptions));

    // Do the profile quickstep
    String authnRequestForIdP = null;
    String bindingView = null;
    if (binding.equals("http-redirect")) {
      bindingView = httpRedirectView;
      String deflatedRequest = Utils.deflate(authnRequestDoc.toString(), Utils.RFC1951_DEFAULT_COMPRESSION_LEVEL, Utils.RFC1951_NO_WRAP);
      authnRequestForIdP = Utils.base64(deflatedRequest.getBytes());
      authnRequestForIdP = authnRequestForIdP.replaceAll(System.getProperty("line.separator"), "");
      try {
        authnRequestForIdP = URLEncoder.encode(authnRequestForIdP, "UTF-8");
        relayState = URLEncoder.encode(relayState, "UTF-8");
      }
      catch(UnsupportedEncodingException uee) {
        logger.error("couldn't encode SAMLRequest");
        mAndV.setViewName(errorView);
        mAndV.getModel().put(errorViewDisplayVar, messages.getMessage("engine.error.missing.entityid.param",
                                                                      null, request.getLocale()));
        return mAndV;
      }
    }
    else if (binding.equals("http-post")) {
      bindingView = httpPOSTView;
      authnRequestForIdP = Utils.base64(authnRequestDoc.toString().getBytes());
    }

    // Send the AuthnRequest to the IdP
    request.setAttribute("SAMLRequest", authnRequestForIdP);
    request.setAttribute("RelayState", relayState);
    mAndV.setViewName(bindingView);
    mAndV.getModel().put("wbsso_endpoint", wbssoURL);
    return mAndV;
  }

  // Setters
  public void setMessages(MessageSource messages) { this.messages = messages; }
  public void setHttpPOSTView(String httpPOSTView) { this.httpPOSTView = httpPOSTView; }
  public void setHttpRedirectView(String httpRedirectView) { this.httpRedirectView = httpRedirectView; }
  public void setErrorView(String errorView) { this.errorView = errorView; }
  public void setErrorViewDisplayVar(String errorViewDisplayVar) { this.errorViewDisplayVar = errorViewDisplayVar; }
}