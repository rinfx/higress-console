/*
 * Copyright (c) 2022-2023 Alibaba Group Holding Ltd.
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file except in compliance with
 * the License. You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software distributed under the License is distributed on
 * an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the License for the
 * specific language governing permissions and limitations under the License.
 */
package com.alibaba.higress.console.controller;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.Enumeration;
import java.util.Locale;
import java.util.Set;

import javax.annotation.PostConstruct;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.apache.http.Header;
import org.apache.http.HttpEntity;
import org.apache.http.client.config.RequestConfig;
import org.apache.http.client.methods.CloseableHttpResponse;
import org.apache.http.client.methods.RequestBuilder;
import org.apache.http.entity.BufferedHttpEntity;
import org.apache.http.entity.ContentType;
import org.apache.http.entity.InputStreamEntity;
import org.apache.http.impl.client.CloseableHttpClient;
import org.apache.http.impl.client.HttpClients;
import org.apache.http.message.BasicHeader;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.alibaba.fastjson.JSON;
import com.alibaba.higress.console.constant.SystemConfigKey;
import com.alibaba.higress.console.controller.dto.Response;
import com.google.common.collect.ImmutableSet;

import joptsimple.internal.Strings;

/**
 * @author CH3CHO
 */
@RestController("AiProxyController")
@RequestMapping(AiProxyController.BASE_PATH)
public class AiProxyController {

    static final String BASE_PATH = "/aiproxy";

    private static final Set<String> INVALID_REQUEST_HEADERS =
        ImmutableSet.of("connection", "content-length", "accept-encoding", "host", "cookie");

    private static final Set<String> INVALID_RESPONSE_HEADERS =
        ImmutableSet.of("connection", "content-length", "content-encoding", "server", "transfer-encoding");

    @Value("${" + SystemConfigKey.AI_PROXY_SERVICE_URL_KEY + ":}")
    private String serviceUrl;

    @Value("${" + SystemConfigKey.AI_PROXY_SERVICE_TOKEN_KEY + ":}")
    private String serviceToken;

    @Value("${" + SystemConfigKey.AI_PROXY_CONNECTION_TIMEOUT_KEY + ":"
        + SystemConfigKey.AI_PROXY_CONNECTION_TIMEOUT_DEFAULT + "}")
    private int connectionTimeout = SystemConfigKey.AI_PROXY_CONNECTION_TIMEOUT_DEFAULT;

    @Value("${" + SystemConfigKey.AI_PROXY_SOCKET_TIMEOUT_KEY + ":" + SystemConfigKey.AI_PROXY_SOCKET_TIMEOUT_DEFAULT
        + "}")
    private int socketTimeout = SystemConfigKey.AI_PROXY_SOCKET_TIMEOUT_DEFAULT;

    private CloseableHttpClient client;

    @PostConstruct
    public void initialize() {
        RequestConfig requestConfig =
            RequestConfig.custom().setConnectTimeout(connectionTimeout).setSocketTimeout(socketTimeout).build();
        client = HttpClients.custom().setDefaultRequestConfig(requestConfig).build();
    }

    @RequestMapping("/**")
    public Object proxy(HttpServletRequest req, HttpServletResponse resp) throws IOException {
        try {
            RequestBuilder requestBuilder = RequestBuilder.create(req.getMethod()).setUri(buildTargetUrl(req));

            final String method = req.getMethod().toUpperCase(Locale.ROOT);
            if ("POST".equals(method) || "PUT".equals(method)) {
                HttpEntity entity =
                    new BufferedHttpEntity(new InputStreamEntity(req.getInputStream(), req.getContentLength()));
                requestBuilder.setEntity(entity);
            }

            for (Enumeration<String> headerNames = req.getHeaderNames(); headerNames.hasMoreElements();) {
                String name = headerNames.nextElement().toLowerCase();
                String value = req.getHeader(name);
                if (!INVALID_REQUEST_HEADERS.contains(name)) {
                    requestBuilder.setHeader(new BasicHeader(name, value));
                }
            }

            if (!Strings.isNullOrEmpty(serviceToken)) {
                requestBuilder.setHeader(HttpHeaders.AUTHORIZATION, "Bearer " + serviceToken);
            }

            try (CloseableHttpResponse response = client.execute(requestBuilder.build())) {
                forwardResponse(resp, response);
            }
        } catch (Exception ex) {
            resp.setStatus(HttpServletResponse.SC_INTERNAL_SERVER_ERROR);
            resp.setContentType(ContentType.APPLICATION_JSON.getMimeType());
            Response<Object> response = Response.failure(ex);
            resp.getWriter().write(JSON.toJSONString(response));
        }
        return null;
    }

    private String buildTargetUrl(HttpServletRequest req) {
        final String baseUrl = this.serviceUrl;
        if (Strings.isNullOrEmpty(baseUrl)) {
            throw new IllegalStateException("Missing serviceUrl.");
        }

        String url = baseUrl;

        String originalRequestUri = req.getRequestURI();
        int basePathStartIndex = originalRequestUri.indexOf(BASE_PATH);
        String relativePath = originalRequestUri.substring(basePathStartIndex + BASE_PATH.length());
        if (!relativePath.isEmpty()) {
            if (url.endsWith("/") && relativePath.startsWith("/")) {
                relativePath = relativePath.substring(1);
            }
        }
        url = url + "/" + relativePath;

        if (!Strings.isNullOrEmpty(req.getQueryString())) {
            url = url + "?" + req.getQueryString();
        }

        return url;
    }

    private void forwardResponse(HttpServletResponse resp, CloseableHttpResponse response) throws IOException {
        resp.setStatus(response.getStatusLine().getStatusCode());

        for (Header header : response.getAllHeaders()) {
            String name = header.getName().toLowerCase(Locale.ROOT);
            String value = header.getValue();
            if (!INVALID_RESPONSE_HEADERS.contains(name)) {
                resp.setHeader(name, value);
            }
        }

        try (InputStream input = response.getEntity().getContent()) {
            OutputStream output = resp.getOutputStream();
            byte[] buffer = new byte[8192];
            int bytesRead;
            while ((bytesRead = input.read(buffer)) != -1) {
                output.write(buffer, 0, bytesRead);
                output.flush();
            }
        }
    }
}
