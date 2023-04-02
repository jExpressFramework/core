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
package org.summerboot.jexpress.nio.server;

import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.http.HttpHeaderNames;
import io.netty.handler.codec.http.HttpHeaders;
import io.netty.handler.codec.http.HttpMethod;
import java.util.List;
import java.util.Map;
import org.summerboot.jexpress.nio.server.domain.ServiceContext;
import org.summerboot.jexpress.util.FormatterUtil;

/**
 *
 * @author Changski Tie Zheng Zhang 张铁铮, 魏泽北, 杜旺财, 杜富贵
 * @version 1.0
 */
public class BootNioLifecycleHandler implements NioLifecycle {

    @Override
    public boolean preProcess(RequestProcessor processor, HttpHeaders httpRequestHeaders, String httpRequestPath, ServiceContext context) throws Exception {
        return true;
    }

    @Override
    public void afterService(RequestProcessor processor, ChannelHandlerContext ctx, HttpHeaders httpRequestHeaders, HttpMethod httptMethod, String httpRequestPath, Map<String, List<String>> queryParams, String httpPostRequestBody, ServiceContext context) {
        protectAuthToken(processor, httpRequestHeaders);
    }

    protected void protectAuthToken(RequestProcessor processor, HttpHeaders httpRequestHeaders) {
        if (httpRequestHeaders.contains(HttpHeaderNames.AUTHORIZATION)) {
            httpRequestHeaders.set(HttpHeaderNames.AUTHORIZATION, "***");// protect authenticator token from being logged
        }
    }

    @Override
    public String beforeSendingError(String errorContent) {
        return FormatterUtil.protectContent(errorContent, "UnknownHostException", ":", null, " ***");
    }

    @Override
    public String beforeLogging(String log) {
        return log;
    }

    @Override
    public void afterLogging(HttpHeaders httpHeaders, HttpMethod httpMethod, String httpRequestUri, String httpPostRequestBody, ServiceContext context, long queuingTime, long processTime, long responseTime, long responseContentLength, String logContent, Throwable ioEx) throws Exception {
    }
}
