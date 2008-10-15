/*
 * RHQ Management Platform
 * Copyright (C) 2005-2008 Red Hat, Inc.
 * All rights reserved.
 *
 * This program is free software; you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation version 2 of the License.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program; if not, write to the Free Software
 * Foundation, Inc., 675 Mass Ave, Cambridge, MA 02139, USA.
 */
package org.rhq.enterprise.server.plugins.jboss.software;

import java.io.InputStream;
import java.net.URI;
import java.net.URL;
import java.util.Collection;

import churchillobjects.rss4j.RssDocument;
import churchillobjects.rss4j.parser.RssParser;

import org.apache.commons.httpclient.HostConfiguration;
import org.apache.commons.httpclient.HttpClient;
import org.apache.commons.httpclient.HttpStatus;
import org.apache.commons.httpclient.UsernamePasswordCredentials;
import org.apache.commons.httpclient.Credentials;
import org.apache.commons.httpclient.auth.AuthScope;
import org.apache.commons.httpclient.methods.GetMethod;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;

import org.rhq.core.clientapi.server.plugin.content.ContentSourceAdapter;
import org.rhq.core.clientapi.server.plugin.content.ContentSourcePackageDetails;
import org.rhq.core.clientapi.server.plugin.content.PackageSyncReport;
import org.rhq.core.domain.configuration.Configuration;
import org.rhq.core.domain.configuration.PropertySimple;

/**
 * Hook into the server to field requests on JBoss software related packages.
 *
 * @author Jason Dobies
 */
public class JBossSoftwareContentSourceAdapter implements ContentSourceAdapter {
    // Attributes  --------------------------------------------

    // Connection properties, read from the user specified values specified at content source creation time
    private String url;
    private String username;
    private String password;
    private String proxyUrl;
    private int proxyPort;
    private String proxyUsername;
    private String proxyPassword;

    /**
     * Indicates if the feed parsing should be performed. This check is only done at the call to
     * {@link #synchronizePackages(PackageSyncReport, java.util.Collection)}. This flag is not used in the
     * call to {@link #getInputStream(String)}; it only refers to the feed retrieval and parsing. If you managed to
     * get the patches into the system prior to deactivating this content source, you will still be able to
     * retrieve the bits for them.
     *
     * This value is defined as a connection property in the same way as the above attributes. It will be set
     * by the user at content source creation time.
     */
    private boolean active;

    private RssFeedParser parser = new RssFeedParser();

    private final Log log = LogFactory.getLog(this.getClass());

    // ContentSourceAdapter Implementation  --------------------------------------------

    public void initialize(Configuration configuration) throws Exception {
        url = safeGetConfigurationProperty("url", configuration);

        if (url == null) {
            throw new IllegalArgumentException("url cannot be null");
        }

        PropertySimple activeProperty = configuration.getSimple("active");
        if (activeProperty != null)
            active = activeProperty.getBooleanValue();

        username = safeGetConfigurationProperty("username", configuration);
        password = safeGetConfigurationProperty("password", configuration);
        proxyUrl = safeGetConfigurationProperty("proxyUrl", configuration);
        proxyUsername = safeGetConfigurationProperty("proxyUsername", configuration);
        proxyPassword = safeGetConfigurationProperty("proxyPassword", configuration);

        String sProxyPort = safeGetConfigurationProperty("proxyPort", configuration);
        if (sProxyPort != null) {
            proxyPort = Integer.parseInt(sProxyPort);
        }
    }

    public void shutdown() {
        // No-op
    }

    public void testConnection() throws Exception {
        if (!active)
            throw new Exception("Content source is NOT set to active, connection cannot be established.");

        // If there is an error in the connection, this call will throw an exception describing it
        retrieveRssDocument();
    }

    public void synchronizePackages(PackageSyncReport report, Collection<ContentSourcePackageDetails> existingPackages)
        throws Exception {
        if (!active)
            return;

        RssDocument rssDocument = retrieveRssDocument();

        if (rssDocument == null) {
            throw new Exception("Null RSS document received from adapter: " + this);
        }

        parser.parseResults(rssDocument, report, existingPackages);
    }

    public InputStream getInputStream(String location) throws Exception {
        HttpClient client = new HttpClient();

        configureProxy(client);

        // Authentication
        GetMethod method = new GetMethod(location);
        method.setDoAuthentication(true);
        method.setFollowRedirects(true);
        if (username != null && password != null) {
            method.addRequestHeader("username", username);
            method.addRequestHeader("password", password);
        }

        int status = client.executeMethod(method);

        if (status != HttpStatus.SC_OK) {
            throw new Exception("Call to retrieve stream returned status code: " + status);
        }

        InputStream stream = method.getResponseBodyAsStream();

        return stream;
    }

    // Public  --------------------------------------------

    @Override
    public String toString() {
        return "JBossSoftwareContentSourceAdapter[url=" + this.url + ", username=" + this.username + "]";
    }

    // Private  --------------------------------------------

    /**
     * Returns the string value of a property in a configuration. This method will check the property for null before
     * extracting the string value.
     *
     * @param  propertyName  property being retrieved
     * @param  configuration contains property values
     *
     * @return string value of the property if it's in the configuration; <code>null</code> if the property was missing
     */
    private String safeGetConfigurationProperty(String propertyName, Configuration configuration) {
        PropertySimple property = configuration.getSimple(propertyName);
        return (property != null) ? property.getStringValue() : null;
    }

    /**
     * Uses the connection properties set on this object to conncet to the RSS feed source and parse the feed contents
     * into churchill domain objects.
     *
     * @return churchill domain representation of the RSS feed contents
     *
     * @throws Exception if there are any errors in connecting to the feed
     */
    private RssDocument retrieveRssDocument() throws Exception {
        HttpClient client = new HttpClient();

        configureProxy(client);

        // Authentication
        URI feedURI = new URI(url);
        URL feedURL = feedURI.toURL(); // proper RFC2396 decode happens here
        GetMethod method = new GetMethod(feedURL.toString());
        method.setDoAuthentication(true);
        if (username != null && password != null) {
            UsernamePasswordCredentials upc = new UsernamePasswordCredentials(username, password);
            client.getState().setCredentials("users", method.getHostConfiguration().getHost(), upc);
        }

        // Perform the connection and capture its response XML
        String rawFeed = null;
        try {
            int status = client.executeMethod(method);

            if (status == 404) {
                throw new Exception("Could not find the feed at URL [" + url + "]. Make sure the URL field correctly " +
                    "refers to the CSP feed location.");
            }

            if (status == 401 || status == 403) {
                throw new Exception("Invalid login credentials specified for user [" + username + "]. Make sure " +
                    "this user has an active account at the CSP and that the password is correct.");
            }

            if (status != 200) {
                throw new Exception("The call to retrieve the RSS feed failed with status code: " + status);
            }

            rawFeed = method.getResponseBodyAsString();
        } finally {
            method.releaseConnection();
        }

        // Parse the raw feed into chuchill domain objects
        RssDocument parsedFeed = null;
        if (rawFeed != null) {
            parsedFeed = RssParser.parseRss(rawFeed);
        }

        return parsedFeed;
    }

    /**
     * If proxy information was specified, configures the client to use it.
     *
     * @param client client being used in the invocation
     */
    private void configureProxy(HttpClient client) {
        // If a proxy URL was specified, configure the client for proxy support
        if (proxyUrl != null) {
            log.debug("Configuring feed for proxy. URL: " + proxyUrl + ", Port: " + proxyPort);
            HostConfiguration hostConfiguration = client.getHostConfiguration();
            hostConfiguration.setProxy(proxyUrl, proxyPort);

            // If a proxy username was specified, indicate it as the proxy credentials
            if (proxyUsername != null) {
                log.debug("Configuring feed for authenticating proxy. User: " + proxyUsername);
                AuthScope proxyAuthScope = new AuthScope(proxyUrl, proxyPort, AuthScope.ANY_REALM);
                Credentials proxyCredentials = new UsernamePasswordCredentials(proxyUsername, proxyPassword);
                client.getState().setProxyCredentials(proxyAuthScope, proxyCredentials);
            }
        }
    }

}