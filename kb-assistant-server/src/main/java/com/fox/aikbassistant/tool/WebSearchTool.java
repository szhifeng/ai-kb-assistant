package com.fox.aikbassistant.tool;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.util.Map;

@Component
public class WebSearchTool {

    private static final Logger log = LoggerFactory.getLogger(WebSearchTool.class);

    private final RestClient client;
    private final String apiKey;

    @Autowired
    public WebSearchTool(@Value("${tavily.api-key:}") String apiKey) {
        this(RestClient.create(), apiKey);
    }

    WebSearchTool(RestClient client, String apiKey) {
        this.client = client;
        this.apiKey = apiKey;
    }

    @Tool(description = "联网搜索实时信息，返回若干条结果摘要")
    public String search(@ToolParam(description = "搜索关键词") String query) {
        if (apiKey == null || apiKey.isBlank()) {
            return "联网搜索未配置 TAVILY_API_KEY";
        }
        long start = System.currentTimeMillis();
        try {
            Map<?, ?> response = client.post()
                    .uri("https://api.tavily.com/search")
                    .body(Map.of("api_key", apiKey, "query", query, "max_results", 5))
                    .retrieve()
                    .body(Map.class);
            Object results = response == null ? null : response.get("results");
            String summary = results == null ? "无搜索结果" : results.toString();
            log.info("tool=web_search query='{}' elapsedMs={} resultLength={}",
                    query, System.currentTimeMillis() - start, summary.length());
            return summary;
        }
        catch (RuntimeException ex) {
            log.warn("tool=web_search query='{}' elapsedMs={} failed={}",
                    query, System.currentTimeMillis() - start, ex.getMessage());
            throw ex;
        }
    }
}
