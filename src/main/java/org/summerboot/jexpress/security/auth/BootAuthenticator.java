/*
 * Copyright 2005-2022 Du Law Office - The Summer Boot Framework Project
 *
 * The Summer Boot Project licenses this file to you under the Apache License, version 2.0 (the
 * "License"); you may not use this file except in compliance with the License and you have no
 * policy prohibiting employee contributions back to this file (unless the contributor to this
 * file is your current or retired employee). You may obtain a copy of the License at:
 *
 * https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software distributed under the License
 * is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express
 * or implied. See the License for the specific language governing permissions and limitations under
 * the License.
 */
package org.summerboot.jexpress.security.auth;

import org.summerboot.jexpress.boot.BootErrorCode;
import org.summerboot.jexpress.boot.BootPOI;
import org.summerboot.jexpress.integration.cache.AuthTokenCache;
import org.summerboot.jexpress.nio.server.domain.Err;
import org.summerboot.jexpress.nio.server.domain.ServiceContext;
import org.summerboot.jexpress.security.JwtUtil;
import org.summerboot.jexpress.util.FormatterUtil;
import com.google.inject.Singleton;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.ExpiredJwtException;
import io.jsonwebtoken.JwtBuilder;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.netty.handler.codec.http.HttpHeaderNames;
import io.netty.handler.codec.http.HttpHeaders;
import io.netty.handler.codec.http.HttpResponseStatus;
import java.security.Key;
import java.time.Duration;
import java.util.Date;
import java.util.Set;
import java.util.stream.Collectors;
import javax.naming.NamingException;
import org.apache.commons.lang3.StringUtils;

/**
 *
 * @author Changski Tie Zheng Zhang 张铁铮, 魏泽北, 杜旺财, 杜富贵
 * @param <T> authenticate(T metaData)
 */
@Singleton
public abstract class BootAuthenticator<T> implements Authenticator {

    protected AuthenticatorListener listener;
    protected AuthConfig authCfg = AuthConfig.cfg;

    /**
     *
     * @param listener
     */
    @Override
    public void setListener(AuthenticatorListener listener) {
        this.listener = listener;
    }

    /**
     *
     * @param uid
     * @param pwd
     * @param validForMinutes
     * @param context
     * @return
     * @throws NamingException
     */
    @Override
    public String login(String uid, String pwd, Object metaData, int validForMinutes, final ServiceContext context) throws NamingException {
        //1. protect request body from being logged
        //context.logRequestBody(true);@Deprecated use @Log(requestBody = false, responseHeader = false) at @Controller method level

        //2. login caller against LDAP or DB
        context.poi(BootPOI.LDAP_BEGIN);
        Caller caller = authenticate(uid, pwd, (T) metaData, listener, context);
        context.poi(BootPOI.LDAP_END);
        if (caller == null) {
            context.status(HttpResponseStatus.UNAUTHORIZED);
            return null;
        }

        //3. format JWT
        JwtBuilder builder = toJwt(caller);

        //4. create JWT
        //String token = JwtUtil.createJWT(authCfg.getJwtSignatureAlgorithm(),
        Key signingKey = authCfg.getJwtSigningKey();
        String token = JwtUtil.createJWT(signingKey, builder, Duration.ofMinutes(validForMinutes));
        if (listener != null) {
            listener.onLoginSuccess(caller.getUid(), token);
        }
        context.caller(caller);
        return token;
    }

    /**
     *
     * @param usename
     * @param password
     * @param metaData
     * @param listener
     * @param context
     * @return
     * @throws NamingException
     */
    abstract protected Caller authenticate(String usename, String password, T metaData, AuthenticatorListener listener, final ServiceContext context) throws NamingException;

    /**
     * Convert Caller to auth token, override this method to implement
     * customized token format
     *
     * @param caller
     * @return formatted auth token builder
     */
    @Override
    public JwtBuilder toJwt(Caller caller) {
        String jti = caller.getTenantId() + "." + caller.getId() + "_" + caller.getUid() + "_" + System.currentTimeMillis();
        String issuer = authCfg.getJwtIssuer();
        String userName = caller.getUid();
        Set<String> groups = caller.getGroups();
        String groupsCsv = groups == null || groups.size() < 1
                ? null
                : groups.stream().collect(Collectors.joining(","));
        String audience = groupsCsv;

        Claims claims = Jwts.claims();
        claims.setId(jti)
                .setIssuer(issuer)
                .setSubject(userName)
                .setAudience(audience);
        if (caller.getId() != null) {
            claims.put("callerId", caller.getId());
        }
        if (caller.getTenantId() != null) {
            claims.put("tenantId", caller.getTenantId());
        }
        if (caller.getTenantName() != null) {
            claims.put("tenantName", caller.getTenantName());
        }
        Set<String> keys = caller.propKeySet();
        if (keys != null) {
            for (String key : keys) {
                Object v = caller.getProp(key, Object.class);
                claims.put(key, v);
            }
        }

        JwtBuilder builder = Jwts.builder().setClaims(claims);

        return builder;
    }

    /**
     * Convert Caller back from auth token, override this method to implement
     * customized token format
     *
     * @param claims
     * @return Caller
     */
    @Override
    public Caller fromJwt(Claims claims) {
        //String jti = claims.getId();
        //String issuer = claims.getIssuer();
        String userName = claims.getSubject();
        String audience = claims.getAudience();
        Long userId = claims.get("callerId", Long.class);
        Long tenantId = claims.get("tenantId", Long.class);
        String tenantName = claims.get("tenantName", String.class);

        User caller = new User(tenantId, tenantName, userId, userName);

        String userGroups = audience;
        if (StringUtils.isNotBlank(userGroups)) {
            String[] groups = FormatterUtil.parseCsv(userGroups);
            for (String group : groups) {
                caller.addGroup(group);
            }
        }

        Set<String> keys = claims.keySet();
        if (keys != null) {
            for (String key : keys) {
                Object v = claims.get(key);
                caller.putProp(key, v);
            }
        }
        caller.remove(Claims.AUDIENCE);
        caller.remove(Claims.EXPIRATION);
        caller.remove(Claims.ID);
        caller.remove(Claims.ISSUED_AT);
        caller.remove(Claims.ISSUER);
        caller.remove(Claims.NOT_BEFORE);
        caller.remove(Claims.SUBJECT);
        caller.remove("callerId");
        caller.remove("tenantId");
        caller.remove("tenantName");

        return caller;
    }

    /**
     *
     * @param httpRequestHeaders
     * @return
     */
    @Override
    public String getBearerToken(HttpHeaders httpRequestHeaders) {
        String authToken = httpRequestHeaders.get(HttpHeaderNames.AUTHORIZATION);
        if (StringUtils.isBlank(authToken) || !authToken.startsWith("Bearer ")) {
            return null;
        }
        String[] a = authToken.split(" ");
        if (a.length < 2) {
            return null;
        }
        authToken = a[1];
        if (StringUtils.isBlank(authToken)) {
            return null;
        }
        return authToken;
    }

    /**
     *
     * @param httpRequestHeaders
     * @param cache
     * @param errorCode
     * @param context
     * @return
     */
    @Override
    public Caller verifyBearerToken(HttpHeaders httpRequestHeaders, AuthTokenCache cache, Integer errorCode, ServiceContext context) {
        String authToken = getBearerToken(httpRequestHeaders);
        return verifyToken(authToken, cache, errorCode, context);
    }

    /**
     *
     * @param authToken
     * @param cache
     * @param errorCode
     * @param context
     * @return
     */
    @Override
    public Caller verifyToken(String authToken, AuthTokenCache cache, Integer errorCode, ServiceContext context) {
        errorCode = errorCode == null ? overrideVerifyTokenErrorCode() : errorCode;
        Caller caller = null;
        if (authToken == null) {
            Err e = new Err(errorCode != null ? errorCode : BootErrorCode.AUTH_REQUIRE_TOKEN, null, "Missing AuthToken", null);
            context.error(e).status(HttpResponseStatus.UNAUTHORIZED);
        } else {
            try {
                Claims claims = JwtUtil.parseJWT(authCfg.getJwtParser(), authToken).getBody();
                String jti = claims.getId();
                context.callerId(jti);
                if (cache != null && cache.isBlacklist(jti)) {// because jti is used as blacklist key in logout
                    Err e = new Err(errorCode != null ? errorCode : BootErrorCode.AUTH_EXPIRED_TOKEN, null, "AuthToken has been logout", null);
                    context.error(e).status(HttpResponseStatus.UNAUTHORIZED);
                } else {
                    caller = fromJwt(claims);
                }
            } catch (ExpiredJwtException ex) {
                Err e = new Err(errorCode != null ? errorCode : BootErrorCode.AUTH_EXPIRED_TOKEN, null, "Expired AuthToken", null);
                context.error(e).status(HttpResponseStatus.UNAUTHORIZED);
            } catch (JwtException ex) {
                Err e = new Err(errorCode != null ? errorCode : BootErrorCode.AUTH_INVALID_TOKEN, null, "Invalid AuthToken - " + ex.getMessage(), null);
                context.error(e).status(HttpResponseStatus.UNAUTHORIZED);
            }
        }
        context.caller(caller);
        return caller;
    }

    protected Integer overrideVerifyTokenErrorCode() {
        return null;
    }

    /**
     *
     * @param httpRequestHeaders
     * @param cache
     * @param context
     */
    @Override
    public void logout(HttpHeaders httpRequestHeaders, AuthTokenCache cache, ServiceContext context) {
        String authToken = getBearerToken(httpRequestHeaders);
        logout(authToken, cache, context);
    }

    /**
     *
     * @param authToken
     * @param cache
     * @param context
     */
    @Override
    public void logout(String authToken, AuthTokenCache cache, ServiceContext context) {
        try {
            Claims claims = JwtUtil.parseJWT(authCfg.getJwtParser(), authToken).getBody();
            String jti = claims.getId();
            String uid = claims.getSubject();
            Date exp = claims.getExpiration();
            long expireInMilliseconds = exp.getTime() - System.currentTimeMillis();
            if (cache != null) {
                cache.blacklist(jti, authToken, expireInMilliseconds);
            }
            if (listener != null) {
                listener.onLogout(claims, authToken, expireInMilliseconds);
            }
        } catch (ExpiredJwtException ex) {
            //ignore
        } catch (JwtException ex) {
            context.status(HttpResponseStatus.FORBIDDEN);
            return;
        }
        context.status(HttpResponseStatus.NO_CONTENT);
    }

}
