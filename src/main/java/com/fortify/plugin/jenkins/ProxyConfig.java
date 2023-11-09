/*******************************************************************************
 * Copyright 2021-2023 Open Text.
 * 
 * Licensed under the MIT License (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * 
 * https://opensource.org/licenses/MIT
 * 
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *******************************************************************************/
package com.fortify.plugin.jenkins;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.MalformedURLException;
import java.net.Proxy;
import java.net.URL;
import java.util.Collections;
import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.apache.commons.lang.StringUtils;
import org.apache.commons.lang3.tuple.Pair;
import org.kohsuke.stapler.DataBoundConstructor;
import org.kohsuke.stapler.StaplerRequest;

import hudson.Extension;
import hudson.ProxyConfiguration;
import hudson.model.AbstractDescribableImpl;
import hudson.model.Descriptor;
import hudson.util.Secret;
import jenkins.model.Jenkins;
import net.sf.json.JSONObject;
import okhttp3.Authenticator;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.Route;

public class ProxyConfig extends AbstractDescribableImpl<ProxyConfig> {
	private static final Logger LOGGER = Logger.getLogger(FortifyPlugin.class.getName());

	// these fields are left only for backwards compatibility
	private String proxyUrl;
	private Secret proxyUsername;
	private Secret proxyPassword;

	private List<Pattern> noProxyHostPatterns = Collections.emptyList();

	private boolean useJenkins = false;

	@DataBoundConstructor
	public ProxyConfig(String proxyUrl, Secret proxyUsername, Secret proxyPassword) {
		Pair<String, Integer> hostAndPort = null;
		try {
			proxyUrl = proxyUrl == null ? null : proxyUrl.trim();
			checkProxyUrlValue(proxyUrl);
			this.proxyUrl = proxyUrl;
			hostAndPort = parseProxyHostAndPort(proxyUrl);
		} catch (FortifyException e) {
			LOGGER.log(Level.WARNING, "Fortify proxy server configuration error: " + e.getMessage());
			this.proxyUrl = null;
		}
		if (this.proxyUrl != null && hostAndPort != null) {
			useJenkins = trySettingJenkinsProxy(hostAndPort.getLeft(), hostAndPort.getRight().intValue(), proxyUsername, proxyPassword); //during initial settings migration
		}
		if (!useJenkins) {
			this.proxyUsername = proxyUsername;
			this.proxyPassword = proxyPassword;
			//setProxyUsernameAndPassword(proxyUsername, proxyPassword);
			this.noProxyHostPatterns = Collections.singletonList(Pattern.compile(".*\\.fortify\\.com"));
		}
	}

	public static Pair<String, Integer> parseProxyHostAndPort(String proxyUrl) {
		if (proxyUrl == null) {
			return null;
		}
		String[] proxyUrlSplit = proxyUrl.split(":");
		String proxyHost = proxyUrlSplit[0];
		int proxyPort = 80;
		if (proxyUrlSplit.length > 1) {
			try {
				proxyPort = Integer.parseInt(proxyUrlSplit[1]);
			} catch (NumberFormatException nfe) {
			}
		}
		return Pair.of(proxyHost, Integer.valueOf(proxyPort));
	}

	private boolean trySettingJenkinsProxy(String proxyHost, int proxyPort, Secret proxyUsername, Secret proxyPassword) {
		Jenkins jenkins = Jenkins.get();
		if (jenkins != null) {
			ProxyConfiguration proxy = jenkins.getProxy();
			if (proxy == null || StringUtils.isBlank(proxy.getName())) {
				proxy = new ProxyConfiguration(proxyHost, proxyPort, Secret.toString(proxyUsername), Secret.toString(proxyPassword));
				proxy.setNoProxyHost("*.fortify.com"); // for backwards compatibility
				jenkins.setProxy(proxy);
				try {
					proxy.save();
					return true;
				} catch (IOException e) {
				}
			}
		}
		return false;
	}

	private ProxyConfig() {
		this.useJenkins = true;
	}

	public String getProxyUrlFor(String url) {
		if (useJenkins) {
			Pair<String, Integer> hostPort = getJenkinsProxyHostPostFor(url);
			return hostPort == null ? "" : hostPort.getLeft() + ':' + hostPort.getRight().intValue();
		}
		for (Pattern next : noProxyHostPatterns) {
			if (next.matcher(url).matches()) {
				return "";
			}
		}
		return proxyUrl;
	}

	private Pair<String, Integer> getJenkinsProxyHostPostFor(String url) {
		ProxyConfiguration proxyConfiguration = getJenkinsProxyInstanceOrNull();
		if (proxyConfiguration != null) {
			String host = proxyConfiguration.getName();
			if (!StringUtils.isBlank(host)) {
				if (!StringUtils.isBlank(url) && isInNoProxyHost(url, proxyConfiguration.getNoProxyHostPatterns())) {
					return null;
				}
				int port = proxyConfiguration.getPort();
				if (port <= 0) {
					port = 80;
				}
				return Pair.of(host, Integer.valueOf(port));
			}
		}
		return null;
	}

	private boolean isInNoProxyHost(String url, List<Pattern> noProxyHostPatterns) {
		try {
			URL parsedUrl = new URL(url);
			String host = parsedUrl.getHost();
			if (!StringUtils.isBlank(host)) {
				for (Pattern next : noProxyHostPatterns) {
					if (next.matcher(host).matches()) {
						return true;
					}
				}
			}
		} catch (MalformedURLException e) {
			// malformed url, we can't verify it
		}
		return false;
	}

	private ProxyConfiguration getJenkinsProxyInstanceOrNull() {
		Jenkins instance = Jenkins.getInstanceOrNull(); // getInstance() can return null if we happen to execute this code in a Jenkins agent.
		if (instance != null && instance.proxy != null) {
			ProxyConfiguration jenkinsProxy = instance.proxy;
			return jenkinsProxy;
		}
		return null;
	}

	public Secret getProxyUsername() {
		if (useJenkins) {
			ProxyConfiguration proxyConfiguration = getJenkinsProxyInstanceOrNull();
			if (proxyConfiguration != null) {
				return Secret.fromString(proxyConfiguration.getUserName());
			}
		}
		return proxyUsername;
	}

	public Secret getProxyPassword() {
		if (useJenkins) {
			ProxyConfiguration proxyConfiguration = getJenkinsProxyInstanceOrNull();
			if (proxyConfiguration != null) {
				return proxyConfiguration.getSecretPassword();
			}
		}
		return proxyPassword;
	}

	private static void checkProxyUrlValue(String proxyUrl) throws FortifyException {
		if (StringUtils.isNotBlank(proxyUrl)) {
			String[] splits = proxyUrl.split(":");
			if (splits.length > 2) {
				throw new FortifyException(new Message(Message.ERROR, "Invalid proxy url.  Format is <hostname>[:<port>]"));
			}
			Pattern hostPattern = Pattern.compile("([\\w\\-]+\\.)*[\\w\\-]+");
			Matcher hostMatcher = hostPattern.matcher(splits[0]);
			if (!hostMatcher.matches()) {
				throw new FortifyException(new Message(Message.ERROR, "Invalid proxy host"));
			}
			if (splits.length == 2) {
				try {
					Integer.parseInt(splits[1]);
				} catch (NumberFormatException nfe) {
					throw new FortifyException(new Message(Message.ERROR, "Invalid proxy port"));
				}
			}
		}
	}

	public OkHttpClient decorateClient(OkHttpClient client, String url) {
		OkHttpClient result = client;
		String proxyUrl = getProxyUrlFor(url);
		if (!StringUtils.isBlank(proxyUrl)) {
			Pair<String, Integer> hostPort = parseProxyHostAndPort(proxyUrl);
			Proxy proxy = new Proxy(Proxy.Type.HTTP, new InetSocketAddress(hostPort.getLeft(), hostPort.getRight().intValue()));
			result = result.newBuilder().proxy(proxy).build();
		}
		Secret proxyUsername = getProxyUsername();
		Secret proxyPassword = getProxyPassword();
		if (proxyUsername != null && proxyPassword != null) {
			final String proxyUsernameString = proxyUsername.getPlainText();
			final String proxyPasswordString = proxyPassword.getPlainText();
			if (!(StringUtils.isEmpty(proxyUsernameString) && StringUtils.isEmpty(proxyPasswordString))) {
				Authenticator proxyAuthenticator = new Authenticator() {
					boolean proxyAuthAttempted = false;
					@Override
					public Request authenticate(Route route, Response response) throws IOException {
						if (proxyAuthAttempted) {
							return null;
						} else {
							proxyAuthAttempted = true;
						}
						String credential = okhttp3.Credentials.basic(proxyUsernameString, proxyPasswordString);
						return response.request().newBuilder().header("Proxy-Authorization", credential).build();
					}
				};
				result = result.newBuilder().proxyAuthenticator(proxyAuthenticator).build();
			}
		}
		return result;
	}

	/**
	 * Gets proxy config that asks Jenkins for its proxy configuration every time.
	 *
	 * @return a Proxy proxy object for Jenkins proxy :).
	 */
	public static ProxyConfig getJenkinsProxyConfig() {
		return new ProxyConfig();
	}

	@Extension
	public static final class DescriptorImpl extends Descriptor<ProxyConfig> {
		@Override
		public String getDisplayName() {
			return "Use proxy";
		}
		@Override
		public boolean configure(StaplerRequest req, JSONObject json) throws FormException {
			return super.configure(req, json);
		}
	}
}
