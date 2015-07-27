/**
 * Copyright 2014 Skytap Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 **/
 
package com.skytap.api;

import java.io.FileInputStream;
import java.io.IOException;
import java.io.StringReader;
import java.io.UnsupportedEncodingException;
import java.net.URLEncoder;
import java.nio.charset.Charset;
import java.util.Date;
import java.util.Properties;
import java.util.logging.Level;
import java.util.logging.Logger;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;
import javax.xml.xpath.XPath;
import javax.xml.xpath.XPathConstants;
import javax.xml.xpath.XPathExpression;
import javax.xml.xpath.XPathExpressionException;
import javax.xml.xpath.XPathFactory;

import org.apache.commons.httpclient.Credentials;
import org.apache.commons.httpclient.DefaultHttpMethodRetryHandler;
import org.apache.commons.httpclient.HttpClient;
import org.apache.commons.httpclient.HttpException;
import org.apache.commons.httpclient.HttpMethod;
import org.apache.commons.httpclient.HttpStatus;
import org.apache.commons.httpclient.UsernamePasswordCredentials;
import org.apache.commons.httpclient.auth.AuthScope;
import org.apache.commons.httpclient.methods.DeleteMethod;
import org.apache.commons.httpclient.methods.GetMethod;
import org.apache.commons.httpclient.methods.HeadMethod;
import org.apache.commons.httpclient.methods.PostMethod;
import org.apache.commons.httpclient.methods.PutMethod;
import org.apache.commons.httpclient.methods.RequestEntity;
import org.apache.commons.httpclient.methods.StringRequestEntity;
import org.apache.commons.httpclient.params.HttpMethodParams;

import org.w3c.dom.Document;
import org.w3c.dom.Node;
import org.xml.sax.InputSource;
import org.xml.sax.SAXException;

public class Skytap {	
	private static final Charset UTF8_CHARSET = Charset.forName("UTF-8");
	private static final Logger LOGGER = Logger.getLogger(Thread.currentThread().getStackTrace()[0].getClassName() );
	private static String skytapurl = null;
	private static String skytapuser = null;
	private static String skytappassword = null;

	private static enum HTTP_METHOD {
		GET,
		PUT,
		POST,
		DELETE,
		HEAD
	}
	
	static {
    	Properties prop = new Properties(); 
    	try {
    		prop.load(new FileInputStream("config.properties"));
    		skytapurl = isNullOrEmpty(prop.getProperty("skytapurl")) ? "https://cloud.skytap.com" : prop.getProperty("skytapurl");
    		skytapuser = prop.getProperty("skytapuser");
    		skytappassword = prop.getProperty("skytapapikey");
    	} catch (IOException ex) {
    		LOGGER.log(Level.SEVERE, ex.getMessage(), ex);
        }		
	}
	
	public static String GetVPNs() {
		return httpRequest(skytapurl + "/vpns");
	}
	
    public static void CreateICNRConnection(String sourceNetID, String targetNetID) {
        StringBuilder url = new StringBuilder(skytapurl);
        url.append("/tunnels");
        url.append("?source_network_id=");
        url.append(sourceNetID);
        url.append("&target_network_id=");
        url.append(targetNetID);
//        System.out.println("DEBUG: CreateICNRConnection URL: " + url.toString());
        String content = httpRequest(url.toString(), HTTP_METHOD.POST);
//        System.out.println("DEBUG: CreateICNRConnection Result: " + content);
        LOGGER.finest("CreateICNRConnection - " + content + "\n");
    }
    
    public static void UpdateConfigSubnet(String configId, String subNet) {
        StringBuilder url = new StringBuilder(skytapurl);
        url.append("/configurations/" + configId + "/networks/" + GetNetworkIDInConfiguration(configId));
        url.append("?subnet_addr=");
        url.append(subNet);
//        System.out.println("DEBUG: UpdateConfigSubnet URL: " + url.toString());
        String content = httpRequest(url.toString(), HTTP_METHOD.PUT);
//        System.out.println("DEBUG: UpdateConfigSubnet Result - " + content);
        LOGGER.finest("UpdateConfigSubnet - " + content + "\n");
    }
    
    public static void AttachVPNConnection(String configId, String networkId, String vpnId) {
        StringBuilder url = new StringBuilder(skytapurl);
        url.append("/configurations/");
        url.append(configId);
        url.append("/networks/");
        url.append(networkId);
        url.append("/vpns/");
        
        StringBuilder rb = new StringBuilder();
        rb.append("{\"vpn-id\":\"");
        rb.append(vpnId);
        rb.append("\"}");
        
        String postData = new String(rb.toString().getBytes(UTF8_CHARSET), UTF8_CHARSET);

		try {
			StringRequestEntity requestEntity = new StringRequestEntity(postData, "application/json", "UTF-8");
//			System.out.println("DEBUG: AttachVPNConnection request: " + url.toString() + " Request Body: " + requestEntity.toString());
	        String content = httpRequest(url.toString(), HTTP_METHOD.PUT, "application/json", "application/json", requestEntity);
//	        System.out.println("DEBUG: AttachVPNConnection - " + content);
	        LOGGER.finest("AttachVPNConnection return content - " + content + "\n");
		} catch (Exception e) {
			LOGGER.log(Level.SEVERE, e.getMessage(), e);
		}    
    }
    
    public static void ConnectVPN(String configId, String networkId, String vpnId) {
        StringBuilder url = new StringBuilder(skytapurl);
        url.append("/configurations/");
        url.append(configId);
        url.append("/networks/");
        url.append(networkId);
        url.append("/vpns/vpn-");
        url.append(vpnId);
        url.append(".json");
        
        String putData = new String("{ \"connected\": true }".getBytes(UTF8_CHARSET), UTF8_CHARSET);

		try {
			StringRequestEntity requestEntity = new StringRequestEntity(putData, "application/json", "UTF-8");
//			System.out.println("Debug: ConnectVPN request" + url.toString() + "\nRequest Body: " + putData.toString());
	        String content = httpRequest(url.toString(), HTTP_METHOD.PUT, "application/json", "application/json", requestEntity);
//	        System.out.println("DEBUG: ConnectVPN - " + content);
	        LOGGER.finest("ConnectVPN - " + content + "\n");
		} catch (Exception e) {
			LOGGER.log(Level.SEVERE, e.getMessage(), e);
		}
    }
    
    public static String RunConfiguration(String configId) {
    	String configState = null;
    	
        StringBuilder url = new StringBuilder(skytapurl);
        url.append("/configurations/");
        url.append(configId);
        url.append("?runstate=running");

        String content = httpRequest(url.toString(),HTTP_METHOD.PUT );
        LOGGER.finest("UpdateConfigurationName - " + content + "\n");
        try {
    	    Document xmlDoc = stringToDom(content);
    	    configState = selectSingleNode(xmlDoc, "/configuration/runstate").getTextContent();
//    	    LOGGER.info("RunConfiguration - State: " + configState);
        } catch (Exception e) {
            LOGGER.log(Level.SEVERE, e.getMessage(), e);
        }
        return configState;
    }
    
    public static String CreateConfiguration(String templateId) {
    	String configId = null;
 //   	System.out.println("DEBUG: CreatConfiguration - Template ID: \n" + templateId );
        StringBuilder url = new StringBuilder(skytapurl);
        url.append("/configurations");

        StringBuilder sbContent = new StringBuilder();
        sbContent.append("<template_id>");
        sbContent.append(templateId);
        sbContent.append("</template_id>");

        String postData = new String(sbContent.toString().getBytes(UTF8_CHARSET), UTF8_CHARSET);

		try {
			Document xmlDoc = null;
			StringRequestEntity requestEntity = new StringRequestEntity(postData, "application/xml", "UTF-8");
	        
//			System.out.println("DEBUG: CreatConfiguration - URL: " + url.toString() + " Request: " + postData.toString());
			
			String content = httpRequest(url.toString(), HTTP_METHOD.POST, null, null, requestEntity);

//			System.out.println("DEBUG: CreatConfiguration - content: \n" + content );
			LOGGER.finest("CreateConfiguraion - " + content + "\n");
			xmlDoc = stringToDom(content);
			configId = selectSingleNode(xmlDoc, "/configuration/id").getTextContent();
//			System.out.println("DEBUG: CreatConfiguration - Configuration ID: \n" + configId );
			LOGGER.info("SaveAsSkytapTemplate - ID: " + configId);

	     } catch (Exception e) {
	    	 System.err.println("Error Creating Skytap Configuration: Check Skytap Authentication");
	    	 LOGGER.log(Level.SEVERE, e.getMessage(), e);
	     }
		
		return configId;
    }
    
    public static void UpdateConfigurationName(String configId, String newName) {
        StringBuilder url = new StringBuilder(skytapurl);
        url.append("/configurations/");
        url.append(configId);
        url.append("?name=");
        try {
			url.append(URLEncoder.encode(newName, "UTF-8"));
		} catch (UnsupportedEncodingException e) {
			LOGGER.log(Level.SEVERE, e.getMessage(), e);
		}

        String content = httpRequest(url.toString(), HTTP_METHOD.PUT);
        LOGGER.finest("UpdateConfigurationName - " + content + "\n");
    }
    
    public static boolean DeleteConfiguration(String configId) {
        StringBuilder url = new StringBuilder(skytapurl);
        url.append("/configurations");
        url.append("/");
        url.append(configId);
        
        String content = httpRequest(url.toString(), HTTP_METHOD.DELETE);
        LOGGER.finest("DeleteConfiguration - " + content + "\n");
        
        return isNullOrEmpty(content);
    }
    
    public static String SaveAsSkytapTemplate(String configId) {
    	String templateId = null;
        StringBuilder url = new StringBuilder(skytapurl);
        url.append("/templates/");

        StringBuilder sbContent = new StringBuilder();
        sbContent.append("<configuration_id>");
        sbContent.append(configId);
        sbContent.append("</configuration_id>");

        String postData = new String(sbContent.toString().getBytes(UTF8_CHARSET), UTF8_CHARSET);

		try {
			StringRequestEntity requestEntity = new StringRequestEntity(postData, "application/xml", "UTF-8");

	        String content = httpRequest(url.toString(), HTTP_METHOD.POST, null, null, requestEntity);
//	        LOGGER.info("SaveAsSkytapTemplate - " + content + "\n");
	        Document xmlDoc = stringToDom(content);
	        templateId = selectSingleNode(xmlDoc, "/template/id").getTextContent();
//	        LOGGER.info("SaveAsSkytapTemplate - ID: " + templateId);
		} catch (Exception e) {
			LOGGER.log(Level.SEVERE, e.getMessage(), e);
		}
		
		return templateId;
    }

    public static String GetConfigurationState(String configId) {
    	String configState = null;
        StringBuilder url = new StringBuilder(skytapurl);
        url.append("/configurations");
        url.append("/");
        url.append(configId);
        String content = httpRequest(url.toString());
        LOGGER.finest("GetConfigurationState - " + content + "\n");

        try {
	        Document xmlDoc = stringToDom(content);
	        configState = selectSingleNode(xmlDoc, "/configuration/runstate").getTextContent();
//	        LOGGER.info("GetConfigurationState - State: " + configState);
        } catch (Exception e) {
        	LOGGER.log(Level.SEVERE, e.getMessage(), e);
        }
        
        return configState;
    }

    public static boolean WaitForConfigToReturnToState(String configId, String state) {
        Date startTime = new Date();
        Date currTime = new Date();

        boolean inState = false;

        while (!inState)
        {
            if (GetConfigurationState(configId).equalsIgnoreCase(state))
            {
                inState = true;
            }
            else
            {
            	try {
					Thread.sleep(15000);
				} catch (InterruptedException e) {
					LOGGER.log(Level.WARNING, "WaitForConfigToReturnToState was interupted", e);
				}
            }

            currTime = new Date();
            if ((currTime.getTime() - startTime.getTime()) >= 5*60*1000)
            {
                LOGGER.info("Skytap: Configuration {" + configId + "} never reached state '{" + state + "}' before 5 minutes");
            }
        }
        
        return inState;
    }
    
    public static String GetNetworkIDInConfiguration(String ConfigID) {
    	String configNetID = null;
        StringBuilder url = new StringBuilder(skytapurl);
        url.append("/configurations/");
        url.append(ConfigID);

        String content = httpRequest(url.toString());
        LOGGER.finest("GetNetworkIDInConfiguration - " + content + "\n");

        try {
	        Document xmlDoc = stringToDom(content);
	        configNetID = selectSingleNode(xmlDoc, "//networks/network/id").getTextContent();
//	        LOGGER.info("GetNetworkIDInConfiguration - ID: " + configNetID);
        } catch (Exception e) {
        	LOGGER.log(Level.SEVERE, e.getMessage(), e);
        }
        
        return configNetID;
    }
    
    public static String GetSubnetInConfigNetwork(String ConfigID, String NetID) {
    	String configSubNet = null;
        StringBuilder url = new StringBuilder(skytapurl);
        url.append("/configurations/");
        url.append(ConfigID);
        url.append("/networks/");
        url.append(NetID);

        String content = httpRequest(url.toString());
        LOGGER.finest("GetNetworkIDInConfiguration - " + content + "\n");

        try {
	        Document xmlDoc = stringToDom(content);
	        configSubNet = selectSingleNode(xmlDoc, "//network/subnet_addr").getTextContent();
//	        System.out.println("DEBUG: GetSubnetInConfigNetwork found subnet: " + configSubNet);
//	        LOGGER.info("GetSubnetInNetwork - Config ID: " + configNetID + " Net ID: " + NetID);
        } catch (Exception e) {
        	LOGGER.log(Level.SEVERE, e.getMessage(), e);
        }
        
        return configSubNet;
    }
	
	public static boolean isNullOrEmpty(String str) {
		return str==null || str.trim().length()==0;
	}
	
	private static Node selectSingleNode(Document xmlDoc, String xpathExpression) throws XPathExpressionException {
		XPathFactory factory = XPathFactory.newInstance();
		XPath xPath = factory.newXPath();
		XPathExpression xPathExpression = xPath.compile(xpathExpression);
		return (Node) xPathExpression.evaluate(xmlDoc, XPathConstants.NODE);
	}
	
    private static Document stringToDom(String xmlSource)  throws SAXException, ParserConfigurationException, IOException {
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        DocumentBuilder builder = factory.newDocumentBuilder();
        return builder.parse(new InputSource(new StringReader(xmlSource)));
    }
	
	private static String httpRequest(String url) {
		return httpRequest(url, HTTP_METHOD.GET, null, null, null);
	}
	
	private static String httpRequest(String url, HTTP_METHOD method) {
		return httpRequest(url, method, null, null, null);
	}
	
	private static String httpRequest(String url, HTTP_METHOD verb, String contentType, String accept, RequestEntity requestEntity) {
		HttpClient client = new HttpClient();
		HttpMethod method = null;
		
		if (verb == HTTP_METHOD.GET)
			method = new GetMethod(url);
		else if (verb == HTTP_METHOD.PUT) {
			method = new PutMethod(url);
			if (requestEntity != null) {
				((PutMethod)method).setRequestEntity(requestEntity);
			}
		} else if (verb == HTTP_METHOD.POST) {
			method = new PostMethod(url);
			if (requestEntity != null) {
				((PostMethod)method).setRequestEntity(requestEntity);
			}
		} else if (verb == HTTP_METHOD.DELETE)
			method = new DeleteMethod(url);
		else if (verb == HTTP_METHOD.HEAD)
			method = new HeadMethod(url);

		method.getParams().setParameter(HttpMethodParams.RETRY_HANDLER, 
				new DefaultHttpMethodRetryHandler(3, false));

		Credentials credentials = new UsernamePasswordCredentials(skytapuser, skytappassword);
		client.getState().setCredentials(AuthScope.ANY, credentials);
		client.getParams().setAuthenticationPreemptive(true);

		if (isNullOrEmpty(contentType))
			method.addRequestHeader("Content-Type", "application/xml");
		else
			method.addRequestHeader("Content-Type", contentType);
		if (isNullOrEmpty(accept))
			method.addRequestHeader("Accept", "application/xml");
		else
			method.addRequestHeader("Accept", accept);
		
		try {
			int statusCode = client.executeMethod(method);

			if (statusCode != HttpStatus.SC_OK) {
				LOGGER.log(Level.SEVERE, "URL: " + url + " Method failed: " + method.getStatusLine());
			}

			return new String(method.getResponseBody(), UTF8_CHARSET);
		} catch (HttpException e) {
			LOGGER.log(Level.SEVERE, "Fatal protocol violation: " + e.getMessage(), e);
		} catch (IOException e) {
			LOGGER.log(Level.SEVERE, "Fatal transport error: " + e.getMessage(), e);
		} finally {
			method.releaseConnection();
		}
		
		return null;
	}
}
