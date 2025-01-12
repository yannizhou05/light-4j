package com.networknt.proxy.conquest;

import com.networknt.body.BodyHandler;
import com.networknt.client.ClientConfig;
import com.networknt.client.Http2Client;
import com.networknt.client.oauth.TokenResponse;
import com.networknt.client.ssl.TLSConfig;
import com.networknt.config.Config;
import com.networknt.config.JsonMapper;
import com.networknt.config.TlsUtil;
import com.networknt.handler.Handler;
import com.networknt.handler.MiddlewareHandler;
import com.networknt.monad.Failure;
import com.networknt.monad.Result;
import com.networknt.monad.Success;
import com.networknt.proxy.PathPrefixAuth;
import com.networknt.proxy.salesforce.SalesforceConfig;
import com.networknt.proxy.salesforce.SalesforceHandler;
import com.networknt.status.Status;
import com.networknt.utility.HashUtil;
import com.networknt.utility.ModuleRegistry;
import io.undertow.Handlers;
import io.undertow.server.HttpHandler;
import io.undertow.server.HttpServerExchange;
import io.undertow.util.Headers;
import io.undertow.util.HttpString;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.ProxySelector;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpHeaders;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.KeyStore;
import java.security.PrivateKey;
import java.security.Signature;
import java.text.MessageFormat;
import java.time.Duration;
import java.util.*;
import java.util.stream.Collectors;

public class ConquestHandler implements MiddlewareHandler {
    private static final Logger logger = LoggerFactory.getLogger(ConquestHandler.class);
    private static final String TLS_TRUSTSTORE_ERROR = "ERR10055";
    private static final String OAUTH_SERVER_URL_ERROR = "ERR10056";
    private static final String ESTABLISH_CONNECTION_ERROR = "ERR10053";
    private static final String GET_TOKEN_ERROR = "ERR10052";
    private static final String METHOD_NOT_ALLOWED  = "ERR10008";

    private volatile HttpHandler next;
    private ConquestConfig config;
    // the cached jwt token so that we can use the same token for different requests.

    private HttpClient client;

    public ConquestHandler() {
        config = ConquestConfig.load();
        if(logger.isInfoEnabled()) logger.info("ConquestHandler is loaded.");
    }

    @Override
    public HttpHandler getNext() {
        return next;
    }

    @Override
    public MiddlewareHandler setNext(HttpHandler next) {
        Handlers.handlerNotNull(next);
        this.next = next;
        return this;
    }

    @Override
    public boolean isEnabled() {
        return config.isEnabled();
    }

    @Override
    public void register() {
        // As certPassword is in the config file, we need to mask them.
        List<String> masks = new ArrayList<>();
        masks.add("certPassword");
        ModuleRegistry.registerModule(SalesforceHandler.class.getName(), Config.getInstance().getJsonMapConfigNoCache(SalesforceConfig.CONFIG_NAME), masks);
    }

    @Override
    public void reload() {
        config.reload();
    }

    @Override
    public void handleRequest(HttpServerExchange exchange) throws Exception {
        String requestPath = exchange.getRequestPath();
        if(logger.isTraceEnabled()) logger.trace("requestPath = " + requestPath);
        // make sure that the request path is in the key set. remember that key set only contains prefix not the full request path.
        for(PathPrefixAuth pathPrefixAuth: config.getPathPrefixAuths()) {
            if(requestPath.startsWith(pathPrefixAuth.getPathPrefix())) {
                if(logger.isTraceEnabled()) logger.trace("found with requestPath = " + requestPath + " prefix = " + pathPrefixAuth.getPathPrefix());
                // matched the prefix found. handler it with the config for this prefix.
                if(System.currentTimeMillis() >= (pathPrefixAuth.getExpiration() - 5000)) { // leave 5 seconds room and default value is 0
                    String jwt = createJwt(pathPrefixAuth.getAuthIssuer(), pathPrefixAuth.getAuthSubject(), pathPrefixAuth.getAuthAudience(), HashUtil.generateUUID(), pathPrefixAuth.getTokenTtl());
                    Result<TokenResponse> result = getAccessToken(pathPrefixAuth.getTokenUrl(), jwt);
                    if(result.isSuccess()) {
                        pathPrefixAuth.setExpiration(System.currentTimeMillis() + result.getResult().getExpiresIn() * 1000 - 60000); // give 60 seconds buffer.
                        pathPrefixAuth.setAccessToken(result.getResult().getAccessToken());
                    } else {
                        setExchangeStatus(exchange, result.getError());
                        return;
                    }
                }
                invokeApi(exchange, "Bearer " + pathPrefixAuth.getAccessToken(), pathPrefixAuth.getServiceHost());
                return;
            }
        }
        // not the Salesforce path, go to the next middleware handler
        if(logger.isDebugEnabled()) logger.debug("The requestPath is not matched to salesforce, go to the next handler in the chain.");
        Handler.next(exchange, next);
    }

    private String createJwt(String issuer, String subject, String audience, String jti, int tokenTtl) throws Exception {
        String certFileName = config.getCertFilename();
        String certPassword = config.getCertPassword();

        String header = "{\"typ\":\"JWT\", \"alg\":\"RS256\"}";
        String claimTemplate = "'{'\"iss\": \"{0}\", \"sub\": \"{1}\", \"aud\": \"{2}\", \"jti\": \"{3}\", \"iat\": {4}, \"exp\": {5}'}'";
        StringBuffer token = new StringBuffer();
        // Encode the JWT Header and add it to our string to sign
        token.append(org.apache.commons.codec.binary.Base64.encodeBase64URLSafeString(header.getBytes("UTF-8")));
        // Separate with a period
        token.append(".");

        String[] claimArray = new String[6];
        claimArray[0] = issuer;
        claimArray[1] = subject;
        claimArray[2] = audience;
        claimArray[3] = jti;
        claimArray[4] = Long.toString(( System.currentTimeMillis()/1000 ));
        claimArray[5] = Long.toString(( System.currentTimeMillis()/1000 ) + tokenTtl);

        MessageFormat claims;
        claims = new MessageFormat(claimTemplate);
        String payload = claims.format(claimArray);

        // Add the encoded claims object
        token.append(org.apache.commons.codec.binary.Base64.encodeBase64URLSafeString(payload.getBytes("UTF-8")));

        KeyStore keystore = TlsUtil.loadKeyStore(certFileName, certPassword.toCharArray());
        PrivateKey privateKey = (PrivateKey) keystore.getKey(certFileName.substring(0, certFileName.indexOf(".")), certPassword.toCharArray());

        // Sign the JWT Header + "." + JWT Claims Object
        Signature signature = Signature.getInstance("SHA256withRSA");
        signature.initSign(privateKey);
        signature.update(token.toString().getBytes("UTF-8"));
        String signedPayload = org.apache.commons.codec.binary.Base64.encodeBase64URLSafeString(signature.sign());

        // Separate with a period
        token.append(".");

        // Add the encoded signature
        token.append(signedPayload);
        return token.toString();
    }

    private Result<TokenResponse> getAccessToken(String serverUrl, String jwt) throws Exception {
        TokenResponse tokenResponse = null;
        if(client == null) {
            try {
                HttpClient.Builder clientBuilder = HttpClient.newBuilder()
                        .followRedirects(HttpClient.Redirect.NORMAL)
                        .connectTimeout(Duration.ofMillis(ClientConfig.get().getTimeout()))
                        .sslContext(Http2Client.createSSLContext());
                if(config.getProxyHost() != null) clientBuilder.proxy(ProxySelector.of(new InetSocketAddress(config.getProxyHost(), config.getProxyPort() == 0 ? 443 : config.getProxyPort())));
                if(config.isEnableHttp2()) clientBuilder.version(HttpClient.Version.HTTP_2);
                // this a workaround to bypass the hostname verification in jdk11 http client.
                Map<String, Object> tlsMap = (Map<String, Object>)ClientConfig.get().getMappedConfig().get(Http2Client.TLS);
                if(tlsMap != null && !Boolean.TRUE.equals(tlsMap.get(TLSConfig.VERIFY_HOSTNAME))) {
                    final Properties props = System.getProperties();
                    props.setProperty("jdk.internal.httpclient.disableHostnameVerification", Boolean.TRUE.toString());
                }
                client = clientBuilder.build();

            } catch (IOException e) {
                logger.error("Cannot create HttpClient:", e);
                return Failure.of(new Status(TLS_TRUSTSTORE_ERROR));
            }
        }
        try {
            if(serverUrl == null) {
                return Failure.of(new Status(OAUTH_SERVER_URL_ERROR, "tokenUrl"));
            }

            Map<String, String> parameters = new HashMap<>();
            parameters.put("grant_type", "client_credentials");
            parameters.put("client_assertion_type", "urn:ietf:params:oauth:client-assertion-type:jwt-bearer");
            parameters.put("client_assertion", jwt);

            String form = parameters.entrySet()
                    .stream()
                    .map(e -> e.getKey() + "=" + URLEncoder.encode(e.getValue(), StandardCharsets.UTF_8))
                    .collect(Collectors.joining("&"));

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(serverUrl))
                    .headers("Content-Type", "application/x-www-form-urlencoded")
                    .POST(HttpRequest.BodyPublishers.ofString(form))
                    .build();

            HttpResponse<?> response = client.send(request, HttpResponse.BodyHandlers.ofString());
            // {"access_token":"eyJ0eXAiOiJKV1QiLCJ6aXAiOiJOT05FIiwia2lkIjoiM0ptbzhUWFJtQTJ2U2hkcFJ6UHpUbC9Xak1zPSIsImFsZyI6IlJTMjU2In0.eyJzdWIiOiJjb25xdWVzdC1wdWJsaWMtdWF0LXN1bmxpZmUtand0LWludGVncmF0aW9uIiwiYXVkaXRUcmFja2luZ0lkIjoiM2UwNjc5ODYtMzRmYy00MzhjLThiYmEtOWJlODhiNGUzZTgxIiwiaXNzIjoiaHR0cHM6Ly9zdW5saWZlLWF1dGgudWF0LmNvbnF1ZXN0LXB1YmxpYy5jb25xdWVzdHBsYW5uaW5nLmNvbTo0NDMvbG9naW4vb2F1dGgyL3JlYWxtcy9yb290L3JlYWxtcy9jb24vcmVhbG1zL3VhdCIsInRva2VuTmFtZSI6ImFjY2Vzc190b2tlbiIsInR5cCI6IkJlYXJlciIsInRva2VuX3R5cGUiOiJCZWFyZXIiLCJhdXRoR3JhbnRJZCI6IjhmODVjOTFkLTQ1NzAtNDA5Ni1iYTdkLWI3Mzk2NDJiZGVhMiIsImF1ZCI6ImNvbnF1ZXN0LXB1YmxpYy11YXQtc3VubGlmZS1qd3QtaW50ZWdyYXRpb24iLCJuYmYiOjE2NjYyOTg1OTksInJlYWxtX2FjY2VzcyI6e30sInNjb3BlIjoiYXBpLmNvbnF1ZXN0cGxhbm5pbmcuY29tIiwiYXV0aF90aW1lIjoxNjY2Mjk4NTk5LCJyZWFsbSI6Ii9jb24vdWF0IiwiZXhwIjoxNjY2MzAwMzk5LCJpYXQiOjE2NjYyOTg1OTksImV4cGlyZXNfaW4iOjE4MDAwMDAsImp0aSI6IjU0ZjMzYzU2LTRhYjktNGI2OC1hYWU2LTAwZGJhZWJiNmVhOSJ9.Fvp2bs2h4pRo9Dcd_w7yMJGwY0Acq4h1fouYbo6b0WVVu8KTTC3Xxrl59kPT7f8Rsd-BjeORM83VypgAVWBvEhZWSOY_PpEIgPL0_EHBDOsOyd9x6Q_78WtVxpQ37Vag3nGT_EZA2b5ECWX1fg4C0qIJ4uUf4wyI6a91fui-95EgVBRsdsNa7TaX4AcsCX4T_96X-sqUY127YGyKV20S9ppKzwpg2kR1Xp43_HxtyBu5i-oSj8ry1EVZd5I0hTl2dzddyYUT8SfCiitS-BrAXC_1MM91td00kn3WlMjFahE5PcC6rg8yVFGpG0OQyIbElvCnfSeqNLjx3FPyVx3rqw","scope":"api.conquestplanning.com","token_type":"Bearer","expires_in":1799}
            System.out.println(response.statusCode() + " " + response.body().toString());
            if(response.statusCode() == 200) {
                // construct a token response and return it.
                Map<String, Object> map = JsonMapper.string2Map(response.body().toString());
                if(map != null) {
                    tokenResponse = new TokenResponse();
                    tokenResponse.setAccessToken((String)map.get("access_token"));
                    tokenResponse.setTokenType((String)map.get("token_type"));
                    tokenResponse.setScope((String)map.get("scope"));
                    tokenResponse.setExpiresIn((Integer)map.get("expires_in"));
                    return Success.of(tokenResponse);
                } else {
                    return Failure.of(new Status(GET_TOKEN_ERROR, "response body is not a JSON"));
                }
            } else {
                logger.error("Error in getting the token with status code " + response.statusCode() + " and body " + response.body().toString());
                return Failure.of(new Status(GET_TOKEN_ERROR, response.body().toString()));
            }
        } catch (Exception e) {
            logger.error("Exception:", e);
            return Failure.of(new Status(ESTABLISH_CONNECTION_ERROR, serverUrl));
        }
    }

    private void invokeApi(HttpServerExchange exchange, String authorization, String requestHost) throws Exception {
        // call the Salesforce API directly here with the token from the cache.
        String requestPath = exchange.getRequestPath();
        String method = exchange.getRequestMethod().toString();
        String queryString = exchange.getQueryString();
        String contentType = exchange.getRequestHeaders().getFirst(Headers.CONTENT_TYPE);
        HttpRequest request = null;
        if(method.equalsIgnoreCase("GET")) {
            request = HttpRequest.newBuilder()
                    .uri(new URI(requestHost + requestPath + "?" + queryString))
                    .headers("Authorization", authorization, "Content-Type", contentType)
                    .GET()
                    .build();

        } else if(method.equalsIgnoreCase("DELETE")) {
            request = HttpRequest.newBuilder()
                    .uri(new URI(requestHost + requestPath + "?" + queryString))
                    .headers("Authorization", authorization, "Content-Type", contentType)
                    .DELETE()
                    .build();

        } else if(method.equalsIgnoreCase("POST")) {
            String bodyString = exchange.getAttachment(BodyHandler.REQUEST_BODY_STRING);
            if(logger.isTraceEnabled()) logger.trace("Post request body = " + bodyString);
            request = HttpRequest.newBuilder()
                    .uri(new URI(requestHost + requestPath))
                    .headers("Authorization", authorization, "Content-Type", contentType)
                    .POST(bodyString == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(bodyString))
                    .build();
        } else if(method.equalsIgnoreCase("PUT")) {
            String bodyString = exchange.getAttachment(BodyHandler.REQUEST_BODY_STRING);
            if(logger.isTraceEnabled()) logger.trace("Put request body = " + bodyString);
            request = HttpRequest.newBuilder()
                    .uri(new URI(requestHost + requestPath))
                    .headers("Authorization", authorization, "Content-Type", contentType)
                    .PUT(bodyString == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(bodyString))
                    .build();
        } else if(method.equalsIgnoreCase("PATCH")) {
            String bodyString = exchange.getAttachment(BodyHandler.REQUEST_BODY_STRING);
            if(logger.isTraceEnabled()) logger.trace("Patch request body = " + bodyString);
            request = HttpRequest.newBuilder()
                    .uri(new URI(requestHost + requestPath))
                    .headers("Authorization", authorization, "Content-Type", contentType)
                    .method("PATCH", bodyString == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(bodyString))
                    .build();
        } else {
            logger.error("wrong http method " + method + " for request path " + requestPath);
            setExchangeStatus(exchange, METHOD_NOT_ALLOWED, method, requestPath);
            return;
        }
        HttpResponse<byte[]> response  = client.send(request, HttpResponse.BodyHandlers.ofByteArray());
        HttpHeaders responseHeaders = response.headers();
        byte[] responseBody = response.body();
        exchange.setStatusCode(response.statusCode());
        for (Map.Entry<String, List<String>> header : responseHeaders.map().entrySet()) {
            // remove empty key in the response header start with a colon.
            if(header.getKey() != null && !header.getKey().startsWith(":") && header.getValue().get(0) != null) {
                for(String s : header.getValue()) {
                    exchange.getResponseHeaders().add(new HttpString(header.getKey()), s);
                }
            }
        }
        exchange.getResponseSender().send(ByteBuffer.wrap(responseBody));
    }
}
