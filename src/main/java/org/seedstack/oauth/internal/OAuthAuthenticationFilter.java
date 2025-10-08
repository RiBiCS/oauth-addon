/*
 * Copyright © 2013-2021, The SeedStack authors <http://seedstack.org>
 *
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */
package org.seedstack.oauth.internal;

import static com.google.common.base.Preconditions.checkNotNull;
import static org.apache.shiro.web.util.WebUtils.issueRedirect;
import static org.seedstack.oauth.internal.OAuthUtils.OPENID_SCOPE;
import static org.seedstack.oauth.internal.OAuthUtils.createScope;

import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import javax.inject.Inject;
import javax.servlet.ServletRequest;
import javax.servlet.ServletResponse;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.apache.shiro.SecurityUtils;
import org.apache.shiro.authc.AuthenticationException;
import org.apache.shiro.authc.AuthenticationToken;
import org.apache.shiro.session.Session;
import org.apache.shiro.subject.Subject;
import org.apache.shiro.web.filter.authc.AuthenticatingFilter;
import org.apache.shiro.web.util.WebUtils;
import org.seedstack.oauth.OAuthConfig;
import org.seedstack.oauth.OAuthProvider;
import org.seedstack.oauth.OAuthService;
import org.seedstack.seed.Configuration;
import org.seedstack.seed.web.SecurityFilter;
import org.seedstack.seed.web.security.SessionRegeneratingFilter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.common.base.Strings;
import com.nimbusds.oauth2.sdk.AuthorizationRequest;
import com.nimbusds.oauth2.sdk.ParseException;
import com.nimbusds.oauth2.sdk.ResponseType;
import com.nimbusds.oauth2.sdk.Scope;
import com.nimbusds.oauth2.sdk.id.ClientID;
import com.nimbusds.oauth2.sdk.id.State;
import com.nimbusds.oauth2.sdk.token.AccessToken;
import com.nimbusds.oauth2.sdk.token.BearerAccessToken;
import com.nimbusds.oauth2.sdk.token.TypelessAccessToken;
import com.nimbusds.openid.connect.sdk.AuthenticationRequest;
import com.nimbusds.openid.connect.sdk.Nonce;

@SecurityFilter("oauth")
public class OAuthAuthenticationFilter extends AuthenticatingFilter implements SessionRegeneratingFilter {
    static final String STATE_KEY = "org.seedstack.oauth.OAuthState";
    static final String NONCE_KEY = "org.seedstack.oauth.OIDCNonce";
    private static final Logger LOGGER = LoggerFactory.getLogger(OAuthAuthenticationFilter.class);
    private static final String AUTHORIZATION = "Authorization";
    @Inject
    private OAuthService oAuthService;
    @Configuration
    private OAuthConfig oauthConfig;

    @Override
    protected AuthenticationToken createToken(ServletRequest request, ServletResponse response) {
        return new OAuthAuthenticationTokenImpl(
                resolveAccessToken(WebUtils.toHttp(request))
                        .orElseThrow(() -> new AuthenticationException("Missing access token")));
    }

    private Optional<AccessToken> resolveAccessToken(HttpServletRequest request) {
        String customAccessTokenHeader = oauthConfig.getCustomAccessTokenHeader();
        if (Strings.isNullOrEmpty(customAccessTokenHeader)) {
            String headerValue = request.getHeader(AUTHORIZATION);
            if (headerValue != null) {
                // Bearer access token
                try {
                    return Optional.of(BearerAccessToken.parse(headerValue));
                } catch (ParseException e) {
                    LOGGER.debug("Unable to parse bearer access token from: " + headerValue);
                }
            }
        } else {
            String headerValue = request.getHeader(customAccessTokenHeader);
            if (headerValue != null) {
                try {
                    return Optional.of(TypelessAccessToken.parse(headerValue));
                } catch (ParseException e) {
                    LOGGER.debug("Unable to parse typeless access token from: " + headerValue);
                }
            }
        }
        return Optional.empty();
    }

    @Override
    protected boolean onAccessDenied(ServletRequest request, ServletResponse response) throws Exception {
        boolean loggedIn = false;
        if (resolveAccessToken(WebUtils.toHttp(request)).isPresent()) {
            loggedIn = executeLogin(request, response);
        }
        if (!loggedIn) {
            // if (oauthConfig.getRedirect() != null) {
            if (redirectToAuthorizationEndpoint(request, response))
                return loggedIn;
            try {
                ((HttpServletResponse) response).sendError(
                        HttpServletResponse.SC_UNAUTHORIZED,
                        OAuthUtils.formatUnauthorizedMessage(request, oauthConfig.isDiscloseUnauthorizedReason()));
            } catch (IOException e1) {
                LOGGER.debug("Unable to send {} HTTP code to client", HttpServletResponse.SC_UNAUTHORIZED, e1);
            }
        }
        return loggedIn;

    }

    @Override
    protected boolean onLoginSuccess(AuthenticationToken token, Subject subject, ServletRequest request,
            ServletResponse response) {
        regenerateSession(subject);
        return true;
    }

    @Override
    protected boolean onLoginFailure(AuthenticationToken token, AuthenticationException e,
            ServletRequest request, ServletResponse response) {
        if (LOGGER.isDebugEnabled()) {
            LOGGER.debug("Authentication exception", e);
        }
        request.setAttribute(OAuthUtils.LOGIN_FAILURE_REASON_KEY, e);
        return false;
    }

    private boolean redirectToAuthorizationEndpoint(ServletRequest request, ServletResponse response) throws IOException {
        State state = new State();
        Nonce nonce = new Nonce();
        Scope scope = createScope(oauthConfig.getScopes());

        URI callback = createRedirectCallback(request);

        URI uri;
        if (scope.contains(OPENID_SCOPE)) {
            uri = buildAuthenticationURI(state, nonce, scope, callback);
        } else {
            uri = buildAuthorizationURI(state, scope, callback);
        }

        saveState(state, nonce);
        saveRequest(request);
        issueRedirect(request, response, uri.toString());
        return true;
    }

    private URI createRedirectCallback(ServletRequest request) {
        String scheme = request.getScheme();
        String host = request.getServerName();
        int port = request.getServerPort();
        try {
            String portPart = (port == 80 || port == 443) ? "" : ":" + port;
            URI callback = new URI(scheme + "://" + host + portPart + "/callback");
            oauthConfig.setRedirect(callback);
            return callback;
        } catch (URISyntaxException e) {
            throw new IllegalStateException("Invalid redirect URI", e);
        }
    }

    private URI buildAuthorizationURI(State state, Scope scope, URI callback) {
        OAuthProvider oauthProvider = oAuthService.getOAuthProvider();
        URI endpointURI = oauthProvider.getAuthorizationEndpoint();
        Map<String, List<String>> parameters = OAuthUtils.extractQueryParameters(endpointURI);
        endpointURI = OAuthUtils.stripQueryString(endpointURI);

        AuthorizationRequest.Builder builder = new AuthorizationRequest.Builder(
                new ResponseType(ResponseType.Value.CODE),
                new ClientID(checkNotNull(oauthConfig.getClientId(), "Missing client identifier")))
                        .scope(scope)
                        .redirectionURI(
                                checkNotNull(oauthConfig.getRedirect() != null ? oauthConfig.getRedirect() : callback, "Missing redirect URI"))
                        .endpointURI(endpointURI)
                        .state(state);

        for (Map.Entry<String, List<String>> parameter : parameters.entrySet()) {
            builder.customParameter(parameter.getKey(), parameter.getValue().toArray(new String[0]));
        }

        return builder.build().toURI();
    }

    private URI buildAuthenticationURI(State state, Nonce nonce, Scope scope, URI callback) {
        OAuthProvider oauthProvider = oAuthService.getOAuthProvider();
        URI endpointURI = oauthProvider.getAuthorizationEndpoint();
        Map<String, List<String>> parameters = OAuthUtils.extractQueryParameters(endpointURI);
        endpointURI = OAuthUtils.stripQueryString(endpointURI);

        AuthenticationRequest.Builder builder = new AuthenticationRequest.Builder(
                new ResponseType(ResponseType.Value.CODE),
                scope,
                new ClientID(checkNotNull(oauthConfig.getClientId(), "Missing client identifier")),
                checkNotNull(oauthConfig.getRedirect() != null ? oauthConfig.getRedirect() : callback, "Missing redirect URI"))
                        .endpointURI(endpointURI)
                        .state(state)
                        .nonce(nonce);

        for (Map.Entry<String, List<String>> parameter : parameters.entrySet()) {
            builder.customParameter(parameter.getKey(), parameter.getValue().toArray(new String[0]));
        }

        return builder.build().toURI();
    }

    private void saveState(State state, Nonce nonce) {
        Subject subject = SecurityUtils.getSubject();
        Session session = subject.getSession();
        session.setAttribute(STATE_KEY, state);
        session.setAttribute(NONCE_KEY, nonce);
    }
}
