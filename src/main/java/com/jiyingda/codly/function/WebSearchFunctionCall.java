/**
 * @(#)WebSearchFunctionCall.java, 4 月 8, 2026.
 * <p>
 * Copyright 2026 chapaof.com. All rights reserved.
 * chapaof.COM PROPRIETARY/CONFIDENTIAL. Use is subject to license terms.
 */
package com.jiyingda.codly.function;

import com.alibaba.fastjson.JSON;
import com.jiyingda.codly.command.CommandContext;
import com.jiyingda.codly.config.Config;
import com.jiyingda.codly.data.Parameters;
import com.jiyingda.codly.data.Property;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * 联网搜索 Function Call 实现，基于通义搜索 API。
 */
@SuppressWarnings("unused")
public class WebSearchFunctionCall implements FunctionCallApi {

    private static final Logger logger = LoggerFactory.getLogger(WebSearchFunctionCall.class);
    private static final String SEARCH_BACKEND = "https://cloud-iqs.aliyuncs.com";
    private static final OkHttpClient HTTP_CLIENT = new OkHttpClient.Builder()
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(5, TimeUnit.SECONDS)
        .build();

    @Override
    public String getName() {
        return "web_search";
    }

    @Override
    public String getDescription() {
        return "提供开放域的实时搜索能力，用于获取最新信息、新闻、事件等。当用户询问需要联网才能回答的问题时使用。";
    }

    @Override
    public Parameters getParameters() {
        Parameters params = new Parameters();
        params.setType("object");

        Map<String, Property> props = new HashMap<>();
        Property queryProp = new Property();
        queryProp.setType("string");
        queryProp.setDescription("搜索问题（长度 >= 2 且 <= 100）");
        props.put("query", queryProp);
        params.setProperties(props);
        params.setRequired(Collections.singletonList("query"));
        return params;
    }

    @Override
    public String execute(String argsJson, CommandContext ctx) {
        try {
            Map<String, Object> args = JSON.parseObject(argsJson, Map.class);
            String query = (String) args.get("query");
            if (query == null || query.trim().length() < 2) {
                return "搜索参数 query 不能为空（长度 >= 2）";
            }
            if (query.length() > 100) {
                query = query.substring(0, 100);
            }

            String apiKey = Config.getIqsApiKeySafe();
            if (apiKey == null) {
                return "不支持联网搜索。";
            }

            String url = SEARCH_BACKEND + "/search/genericSearch?query=" + java.net.URLEncoder.encode(query, java.nio.charset.StandardCharsets.UTF_8);
            Request request = new Request.Builder()
                .url(url)
                .addHeader("X-API-Key", apiKey)
                .get()
                .build();

            try (Response response = HTTP_CLIENT.newCall(request).execute()) {
                if (!response.isSuccessful() || response.body() == null) {
                    return "搜索请求失败，HTTP " + response.code();
                }
                String body = response.body().string();
                com.alibaba.fastjson.JSONObject json = JSON.parseObject(body);
                var pageItems = json.getJSONArray("pageItems");
                if (pageItems == null || pageItems.isEmpty()) {
                    return "搜索结果为空";
                }

                StringBuilder sb = new StringBuilder();
                for (int i = 0; i < pageItems.size(); i++) {
                    var item = pageItems.getJSONObject(i);
                    String title = item.getString("title");
                    String link = item.getString("link");
                    String snippet = item.getString("snippet");
                    String mainText = item.getString("mainText");
                    String publishDate = "";
                    Long publishTime = item.getLong("publishTime");
                    if (publishTime != null) {
                        publishDate = DateTimeFormatter.ofPattern("yyyy-MM-dd")
                            .format(Instant.ofEpochMilli(publishTime).atZone(ZoneId.systemDefault()));
                    }

                    sb.append("Title: ").append(title != null ? title : "").append("\n");
                    sb.append("URL: ").append(link != null ? link : "").append("\n");
                    sb.append("Published Date: ").append(publishDate).append("\n");
                    sb.append("Snippet: ").append(snippet != null ? snippet : "").append("\n");
                    sb.append("Text: ").append(mainText != null ? mainText : "").append("\n\n");
                    sb.append("---\n");
                }
                return sb.toString();
            }
        } catch (Exception e) {
            logger.error("搜索异常：{}", e.getMessage(), e);
            return "搜索失败：" + e.getMessage();
        }
    }
}