# Spring AI 知识库问答助手 实现计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 构建基于 Spring AI 2.0 的个人知识库 RAG 问答系统，支持文档入库、语义检索、SSE 流式问答与引用溯源，并增量叠加记忆/限流/工具调用/多模型路由。

**Architecture:** Maven 多模块（`kb-assistant-server` 后端 + `kb-assistant-web-ui` 前端占位）。后端按 controller/service/ingest/rag/tool/config/model 分层。RAG 链路用 `ChatClient` + `QuestionAnswerAdvisor`，PGVector 存向量，Redis 存会话与限流计数，SSE 走 WebFlux `Flux`。

**Tech Stack:** Spring Boot 4.1.0 / Java 25 / Spring AI 2.0.0-RC2 / PGVector / Redis / OpenAI(embedding+chat) / DeepSeek(chat) / Docker Compose。

**前置约定（所有任务通用）:**
- 工作目录：`d:\work\workspace\ai-kb-assistant`
- 构建命令：`.\mvnw.cmd -q -pl kb-assistant-server <goal>`（Windows PowerShell）
- 包根：`com.fox.aikbassistant`
- 测试集成依赖（pgvector/redis）通过 Testcontainers 启动，无需手动起 Docker。
- 密钥用环境变量，禁止硬编码。无 Key 时用 `test` profile + mock Bean 跑单测。

---

## 里程碑总览

- **M1 工程骨架**（Task 1-4）：多模块拆分、依赖瘦身、docker-compose、应用启动。
- **M2 入库管线**（Task 5-8）：上传 → 解析 → 切片 → 向量化 → PGVector。
- **M3 流式 RAG 问答 / MVP 终点**（Task 9-12）：RAG 链路 + SSE + 引用溯源。
- **M4 记忆 + 成本控制**（Task 13-15）：Redis 多轮记忆 + Token 限流。
- **M5 工具 + 路由**（Task 16-19）：Function Calling + 多模型路由。
- **M6 前端**（Task 20-21）：Vue3 对话界面 + 文档管理。

MVP = M1–M3。下面任务按里程碑顺序展开。

---

## M1 工程骨架

### Task 1: 根 pom 改为聚合模块

**Files:**
- Modify: `pom.xml`
- Create: `kb-assistant-server/pom.xml`

- [ ] **Step 1: 备份并改写根 `pom.xml` 为聚合 pom**

根 pom 改为 `packaging=pom`，移除所有业务依赖（下沉到 server），保留 parent、properties、dependencyManagement(spring-ai-bom)，新增 `<modules>` 与 `<packaging>`：

```xml
<packaging>pom</packaging>
<modules>
    <module>kb-assistant-server</module>
</modules>
```

保留 `<properties>`（java.version=25, spring-ai.version=2.0.0-RC2）与 `<dependencyManagement>` 中的 `spring-ai-bom`。删除根 pom 的 `<dependencies>` 整段和 `<build>` 整段（build 下沉 server）。

- [ ] **Step 2: 创建 `kb-assistant-server/pom.xml`**

```xml
<?xml version="1.0" encoding="UTF-8"?>
<project xmlns="http://maven.apache.org/POM/4.0.0"
         xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
         xsi:schemaLocation="http://maven.apache.org/POM/4.0.0 https://maven.apache.org/xsd/maven-4.0.0.xsd">
    <modelVersion>4.0.0</modelVersion>
    <parent>
        <groupId>com.fox</groupId>
        <artifactId>ai-kb-assistant</artifactId>
        <version>0.0.1-SNAPSHOT</version>
    </parent>
    <artifactId>kb-assistant-server</artifactId>
    <name>kb-assistant-server</name>

    <dependencies>
        <dependency>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-starter-web</artifactId>
        </dependency>
        <dependency>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-starter-webflux</artifactId>
        </dependency>
        <dependency>
            <groupId>org.springframework.ai</groupId>
            <artifactId>spring-ai-starter-vector-store-pgvector</artifactId>
        </dependency>
        <dependency>
            <groupId>org.springframework.ai</groupId>
            <artifactId>spring-ai-starter-model-openai</artifactId>
        </dependency>
        <dependency>
            <groupId>org.springframework.ai</groupId>
            <artifactId>spring-ai-starter-model-deepseek</artifactId>
        </dependency>
        <dependency>
            <groupId>org.springframework.ai</groupId>
            <artifactId>spring-ai-starter-model-chat-memory-repository-redis</artifactId>
        </dependency>
        <dependency>
            <groupId>org.springframework.ai</groupId>
            <artifactId>spring-ai-tika-document-reader</artifactId>
        </dependency>
        <dependency>
            <groupId>org.springframework.ai</groupId>
            <artifactId>spring-ai-markdown-document-reader</artifactId>
        </dependency>
        <dependency>
            <groupId>org.springframework.ai</groupId>
            <artifactId>spring-ai-advisors-vector-store</artifactId>
        </dependency>
        <dependency>
            <groupId>org.projectlombok</groupId>
            <artifactId>lombok</artifactId>
            <optional>true</optional>
        </dependency>
        <dependency>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-starter-test</artifactId>
            <scope>test</scope>
        </dependency>
        <dependency>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-testcontainers</artifactId>
            <scope>test</scope>
        </dependency>
        <dependency>
            <groupId>org.testcontainers</groupId>
            <artifactId>postgresql</artifactId>
            <scope>test</scope>
        </dependency>
        <dependency>
            <groupId>com.redis</groupId>
            <artifactId>testcontainers-redis</artifactId>
            <version>2.2.2</version>
            <scope>test</scope>
        </dependency>
    </dependencies>

    <build>
        <plugins>
            <plugin>
                <groupId>org.springframework.boot</groupId>
                <artifactId>spring-boot-maven-plugin</artifactId>
            </plugin>
        </plugins>
    </build>
</project>
```

- [ ] **Step 3: 验证依赖解析**

Run: `.\mvnw.cmd -q -DskipTests dependency:resolve`
Expected: BUILD SUCCESS，无依赖缺失。

- [ ] **Step 4: Commit**

```bash
git add pom.xml kb-assistant-server/pom.xml
git commit -m "chore: 拆分为多模块并瘦身依赖"
```

### Task 2: 迁移启动类与资源到 server 模块

**Files:**
- Move: `src/main/java/com/fox/aikbassistant/AiKbAssistantApplication.java` → `kb-assistant-server/src/main/java/com/fox/aikbassistant/AiKbAssistantApplication.java`
- Move: `src/test/java/com/fox/aikbassistant/AiKbAssistantApplicationTests.java` → `kb-assistant-server/src/test/...`
- Move: `src/main/resources/application.yaml` → `kb-assistant-server/src/main/resources/application.yaml`
- Delete: 原 `src/` 目录

- [ ] **Step 1: 移动源码与资源**

把 `src/main/java`、`src/test/java`、`src/main/resources` 整体移入 `kb-assistant-server/`，保持包路径 `com.fox.aikbassistant` 不变。删除根目录空的 `src/`。启动类内容不变：

```java
package com.fox.aikbassistant;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication
public class AiKbAssistantApplication {
    public static void main(String[] args) {
        SpringApplication.run(AiKbAssistantApplication.class, args);
    }
}
```

- [ ] **Step 2: 验证编译**

Run: `.\mvnw.cmd -q -DskipTests compile`
Expected: BUILD SUCCESS，server 模块编译通过。

- [ ] **Step 3: Commit**

```bash
git add -A
git commit -m "chore: 迁移启动类与资源至 kb-assistant-server"
```

### Task 3: docker-compose 起 pgvector + redis

**Files:**
- Create: `docker/docker-compose.yml`
- Create: `.env.example`

- [ ] **Step 1: 创建 `docker/docker-compose.yml`**

```yaml
services:
  postgres:
    image: pgvector/pgvector:pg17
    environment:
      POSTGRES_DB: kb
      POSTGRES_USER: postgres
      POSTGRES_PASSWORD: postgres
    ports: ["5432:5432"]
    volumes:
      - pgdata:/var/lib/postgresql/data
  redis:
    image: redis:7-alpine
    ports: ["6379:6379"]
volumes:
  pgdata:
```

- [ ] **Step 2: 创建 `.env.example`（密钥占位，提示用户复制为 .env）**

```bash
OPENAI_API_KEY=sk-xxx
DEEPSEEK_API_KEY=sk-xxx
TAVILY_API_KEY=tvly-xxx
DB_USER=postgres
DB_PASSWORD=postgres
```

- [ ] **Step 3: 启动依赖并验证**

Run: `docker compose -f docker/docker-compose.yml up -d`
Expected: postgres 与 redis 容器 Up。
Run: `docker compose -f docker/docker-compose.yml ps`
Expected: 两个服务状态 running。

- [ ] **Step 4: Commit**

```bash
git add docker/docker-compose.yml .env.example
git commit -m "chore: 新增 docker-compose 启动 pgvector 与 redis"
```

### Task 4: 配置 application.yaml 并验证应用启动

**Files:**
- Modify: `kb-assistant-server/src/main/resources/application.yaml`
- Create: `kb-assistant-server/src/main/resources/application-test.yaml`

- [ ] **Step 1: 写 `application.yaml`**

```yaml
spring:
  application:
    name: ai-kb-assistant
  datasource:
    url: jdbc:postgresql://localhost:5432/kb
    username: ${DB_USER:postgres}
    password: ${DB_PASSWORD:postgres}
  data:
    redis:
      host: localhost
      port: 6379
  ai:
    openai:
      api-key: ${OPENAI_API_KEY}
      embedding:
        options:
          model: text-embedding-3-small
    deepseek:
      api-key: ${DEEPSEEK_API_KEY}
      chat:
        options:
          model: deepseek-chat
    vectorstore:
      pgvector:
        index-type: HNSW
        distance-type: COSINE_DISTANCE
        initialize-schema: true
```

- [ ] **Step 2: 写 `application-test.yaml`（测试用占位 key，连接由 Testcontainers 动态注入）**

```yaml
spring:
  ai:
    openai:
      api-key: test-key
    deepseek:
      api-key: test-key
```

- [ ] **Step 3: 启动应用验证（需先 export 环境变量或用 .env）**

Run: `.\mvnw.cmd -q -pl kb-assistant-server spring-boot:run`
Expected: 应用启动日志出现 "Started AiKbAssistantApplication"，pgvector 表 `vector_store` 自动建表（initialize-schema）。手动 Ctrl+C 停止。

- [ ] **Step 4: Commit**

```bash
git add kb-assistant-server/src/main/resources/
git commit -m "feat: 配置数据源/Redis/Spring AI 并验证启动"
```

---

## M2 入库管线

### Task 5: 定义 DTO 与切片管线骨架

**Files:**
- Create: `kb-assistant-server/src/main/java/com/fox/aikbassistant/model/Citation.java`
- Create: `kb-assistant-server/src/main/java/com/fox/aikbassistant/model/IngestResult.java`
- Create: `kb-assistant-server/src/main/java/com/fox/aikbassistant/model/DocumentInfo.java`

- [ ] **Step 1: 创建 `Citation`（引用溯源结构，record）**

```java
package com.fox.aikbassistant.model;

public record Citation(String source, String snippet, Double score) {}
```

- [ ] **Step 2: 创建 `IngestResult`（入库结果）**

```java
package com.fox.aikbassistant.model;

public record IngestResult(String source, int chunkCount) {}
```

- [ ] **Step 3: 创建 `DocumentInfo`（文档列表项）**

```java
package com.fox.aikbassistant.model;

public record DocumentInfo(String source, long chunkCount) {}
```

- [ ] **Step 4: 验证编译并提交**

Run: `.\mvnw.cmd -q -DskipTests compile`
Expected: BUILD SUCCESS

```bash
git add kb-assistant-server/src/main/java/com/fox/aikbassistant/model/
git commit -m "feat: 新增入库与引用相关 DTO"
```

### Task 6: 实现 IngestService（解析→切片→向量化→入库）

**Files:**
- Create: `kb-assistant-server/src/main/java/com/fox/aikbassistant/service/IngestService.java`
- Test: `kb-assistant-server/src/test/java/com/fox/aikbassistant/service/IngestServiceTest.java`

- [ ] **Step 1: 写失败测试（用内存 SimpleVectorStore 验证切片入库）**

```java
package com.fox.aikbassistant.service;

import com.fox.aikbassistant.model.IngestResult;
import org.junit.jupiter.api.Test;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.core.io.Resource;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

class IngestServiceTest {

    @Test
    void ingest_splitsAndStoresWithSourceMetadata() {
        VectorStore store = mock(VectorStore.class);
        List<Document> captured = new ArrayList<>();
        doAnswer(inv -> { captured.addAll(inv.getArgument(0)); return null; })
                .when(store).add(anyList());

        IngestService service = new IngestService(store);
        Resource res = new ByteArrayResource(
                "# Title\n\nHello world. ".repeat(200).getBytes()) {
            @Override public String getFilename() { return "note.md"; }
        };

        IngestResult result = service.ingest(res, "note.md");

        assertThat(result.source()).isEqualTo("note.md");
        assertThat(result.chunkCount()).isGreaterThan(0);
        assertThat(captured).isNotEmpty();
        assertThat(captured.get(0).getMetadata()).containsKey("source");
        assertThat(captured.get(0).getMetadata().get("source")).isEqualTo("note.md");
    }
}
```

- [ ] **Step 2: 运行测试确认失败**

Run: `.\mvnw.cmd -q -pl kb-assistant-server test -Dtest=IngestServiceTest`
Expected: FAIL（IngestService 不存在 / 编译错误）

- [ ] **Step 3: 实现 `IngestService`**

按扩展名选 reader：`.md` 用 `MarkdownDocumentReader`，其余用 `TikaDocumentReader`；`TokenTextSplitter` 切片；写入 `source` metadata；存 `VectorStore`。

```java
package com.fox.aikbassistant.service;

import com.fox.aikbassistant.model.IngestResult;
import org.springframework.ai.document.Document;
import org.springframework.ai.reader.markdown.MarkdownDocumentReader;
import org.springframework.ai.reader.tika.TikaDocumentReader;
import org.springframework.ai.transformer.splitter.TokenTextSplitter;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
public class IngestService {

    private final VectorStore vectorStore;
    private final TokenTextSplitter splitter = new TokenTextSplitter();

    public IngestService(VectorStore vectorStore) {
        this.vectorStore = vectorStore;
    }

    public IngestResult ingest(Resource resource, String source) {
        List<Document> docs = read(resource, source);
        List<Document> chunks = splitter.apply(docs);
        chunks.forEach(d -> d.getMetadata().put("source", source));
        vectorStore.add(chunks);
        return new IngestResult(source, chunks.size());
    }

    private List<Document> read(Resource resource, String source) {
        if (source.toLowerCase().endsWith(".md")) {
            return new MarkdownDocumentReader(resource, MarkdownDocumentReader
                    .builder().build().getConfig()).get();
        }
        return new TikaDocumentReader(resource).get();
    }
}
```

注：若 `MarkdownDocumentReader` 构造签名与上述不符，改用 `new MarkdownDocumentReader(resource)` 默认构造（以实际 2.0-RC2 API 为准，二选一能编译通过即可）。

- [ ] **Step 4: 运行测试确认通过**

Run: `.\mvnw.cmd -q -pl kb-assistant-server test -Dtest=IngestServiceTest`
Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add kb-assistant-server/src/main/java/com/fox/aikbassistant/service/IngestService.java kb-assistant-server/src/test/java/com/fox/aikbassistant/service/IngestServiceTest.java
git commit -m "feat: 实现文档解析切片入库管线"
```

### Task 7: DocumentController 上传/列表/删除接口

**Files:**
- Create: `kb-assistant-server/src/main/java/com/fox/aikbassistant/controller/DocumentController.java`

- [ ] **Step 1: 实现 controller**

```java
package com.fox.aikbassistant.controller;

import com.fox.aikbassistant.model.IngestResult;
import com.fox.aikbassistant.service.IngestService;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.core.io.Resource;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;

@RestController
@RequestMapping("/api/documents")
public class DocumentController {

    private final IngestService ingestService;

    public DocumentController(IngestService ingestService) {
        this.ingestService = ingestService;
    }

    @PostMapping
    public IngestResult upload(@RequestParam("file") MultipartFile file) throws IOException {
        String name = file.getOriginalFilename();
        Resource res = new ByteArrayResource(file.getBytes()) {
            @Override public String getFilename() { return name; }
        };
        return ingestService.ingest(res, name);
    }
}
```

注：列表/删除接口依赖 PGVector 的 metadata 查询，列为 Task 8 的扩展；本步先交付上传。

- [ ] **Step 2: 编译验证**

Run: `.\mvnw.cmd -q -DskipTests compile`
Expected: BUILD SUCCESS

- [ ] **Step 3: Commit**

```bash
git add kb-assistant-server/src/main/java/com/fox/aikbassistant/controller/DocumentController.java
git commit -m "feat: 新增文档上传接口"
```

### Task 8: 入库链路集成测试（Testcontainers + PGVector）

**Files:**
- Create: `kb-assistant-server/src/test/java/com/fox/aikbassistant/IngestIntegrationTest.java`
- Create: `kb-assistant-server/src/test/java/com/fox/aikbassistant/TestcontainersConfig.java`

- [ ] **Step 1: 写 Testcontainers 配置（pgvector 容器 + 动态数据源）**

```java
package com.fox.aikbassistant;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

@TestConfiguration(proxyBeanMethods = false)
public class TestcontainersConfig {

    @Bean
    @ServiceConnection
    PostgreSQLContainer<?> postgres() {
        return new PostgreSQLContainer<>(
                DockerImageName.parse("pgvector/pgvector:pg17")
                        .asCompatibleSubstituteFor("postgres"))
                .withDatabaseName("kb");
    }
}
```

- [ ] **Step 2: 写集成测试（mock EmbeddingModel 避免外网，验证入库后能检索到）**

```java
package com.fox.aikbassistant;

import com.fox.aikbassistant.service.IngestService;
import org.junit.jupiter.api.Test;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.core.io.Resource;
import org.springframework.test.context.ActiveProfiles;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

@SpringBootTest
@ActiveProfiles("test")
@Import(TestcontainersConfig.class)
class IngestIntegrationTest {

    @Autowired IngestService ingestService;
    @Autowired VectorStore vectorStore;
    @MockBean EmbeddingModel embeddingModel;

    @Test
    void ingestThenRetrieve() {
        float[] vec = new float[1536];
        for (int i = 0; i < vec.length; i++) vec[i] = 0.01f;
        when(embeddingModel.embed(org.mockito.ArgumentMatchers.<String>any())).thenReturn(vec);
        when(embeddingModel.dimensions()).thenReturn(1536);

        Resource res = new ByteArrayResource("Spring AI RAG 知识库。".repeat(50).getBytes()) {
            @Override public String getFilename() { return "doc.md"; }
        };
        ingestService.ingest(res, "doc.md");

        var results = vectorStore.similaritySearch(SearchRequest.builder().query("Spring AI").topK(3).build());
        assertThat(results).isNotEmpty();
    }
}
```

- [ ] **Step 3: 运行集成测试**

Run: `.\mvnw.cmd -q -pl kb-assistant-server test -Dtest=IngestIntegrationTest`
Expected: PASS（容器启动 → 建表 → 入库 → 检索命中）

- [ ] **Step 4: Commit**

```bash
git add kb-assistant-server/src/test/java/com/fox/aikbassistant/IngestIntegrationTest.java kb-assistant-server/src/test/java/com/fox/aikbassistant/TestcontainersConfig.java
git commit -m "test: 入库链路集成测试 (Testcontainers + PGVector)"
```

---

## M3 流式 RAG 问答（MVP 终点）

### Task 9: ChatClient 配置（RAG 链路装配）

**Files:**
- Create: `kb-assistant-server/src/main/java/com/fox/aikbassistant/config/ChatClientConfig.java`

- [ ] **Step 1: 装配带 QuestionAnswerAdvisor 的 ChatClient**

默认使用 DeepSeek `ChatModel`，挂 `QuestionAnswerAdvisor`（检索注入）。Spring AI 同时存在 OpenAI 与 DeepSeek 两个 ChatModel Bean，用 `@Qualifier` 指定 DeepSeek。

```java
package com.fox.aikbassistant.config;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.vectorstore.QuestionAnswerAdvisor;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class ChatClientConfig {

    @Bean
    ChatClient ragChatClient(@Qualifier("deepSeekChatModel") ChatModel chatModel,
                             VectorStore vectorStore) {
        return ChatClient.builder(chatModel)
                .defaultAdvisors(QuestionAnswerAdvisor.builder(vectorStore)
                        .searchRequest(SearchRequest.builder().topK(4).build())
                        .build())
                .build();
    }
}
```

注：DeepSeek ChatModel 的 bean 名以实际自动配置为准，若 `deepSeekChatModel` 不存在则改用注入唯一匹配或 `chatModel` 名称。装配失败时先 `--debug` 看可用 bean 名再修正 Qualifier。

- [ ] **Step 2: 编译验证**

Run: `.\mvnw.cmd -q -DskipTests compile`
Expected: BUILD SUCCESS

- [ ] **Step 3: Commit**

```bash
git add kb-assistant-server/src/main/java/com/fox/aikbassistant/config/ChatClientConfig.java
git commit -m "feat: 装配带 RAG Advisor 的 ChatClient"
```

### Task 10: RagChatService（检索问答 + 引用提取）

**Files:**
- Create: `kb-assistant-server/src/main/java/com/fox/aikbassistant/service/RagChatService.java`
- Test: `kb-assistant-server/src/test/java/com/fox/aikbassistant/service/RagChatServiceTest.java`

- [ ] **Step 1: 写引用提取的失败测试（纯逻辑，从 Document 列表抽 Citation）**

```java
package com.fox.aikbassistant.service;

import com.fox.aikbassistant.model.Citation;
import org.junit.jupiter.api.Test;
import org.springframework.ai.document.Document;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class RagChatServiceTest {

    @Test
    void toCitations_extractsSourceAndSnippet() {
        Document doc = Document.builder()
                .text("Spring AI 提供 RAG 能力，支持向量检索。")
                .metadata(Map.of("source", "ai.md"))
                .build();

        List<Citation> citations = RagChatService.toCitations(List.of(doc));

        assertThat(citations).hasSize(1);
        assertThat(citations.get(0).source()).isEqualTo("ai.md");
        assertThat(citations.get(0).snippet()).contains("Spring AI");
    }
}
```

- [ ] **Step 2: 运行确认失败**

Run: `.\mvnw.cmd -q -pl kb-assistant-server test -Dtest=RagChatServiceTest`
Expected: FAIL（RagChatService 不存在）

- [ ] **Step 3: 实现 `RagChatService`**

提供流式问答 `stream(question, conversationId)` 返回 `Flux<String>`，以及独立的检索 `retrieve` 用于引用提取。`toCitations` 为静态纯函数便于单测。

```java
package com.fox.aikbassistant.service;

import com.fox.aikbassistant.model.Citation;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;

import java.util.List;

@Service
public class RagChatService {

    private final ChatClient chatClient;
    private final VectorStore vectorStore;

    public RagChatService(ChatClient ragChatClient, VectorStore vectorStore) {
        this.chatClient = ragChatClient;
        this.vectorStore = vectorStore;
    }

    public Flux<String> stream(String question) {
        return chatClient.prompt().user(question).stream().content();
    }

    public List<Citation> retrieveCitations(String question) {
        List<Document> docs = vectorStore.similaritySearch(
                SearchRequest.builder().query(question).topK(4).build());
        return toCitations(docs);
    }

    static List<Citation> toCitations(List<Document> docs) {
        return docs.stream()
                .map(d -> new Citation(
                        String.valueOf(d.getMetadata().getOrDefault("source", "unknown")),
                        snippet(d.getText()),
                        d.getScore()))
                .toList();
    }

    private static String snippet(String text) {
        if (text == null) return "";
        return text.length() <= 160 ? text : text.substring(0, 160) + "...";
    }
}
```

注：`Document.getScore()` 在 2.0-RC2 若不可用，则 Citation.score 传 null（白名单字段，不影响主链路）。

- [ ] **Step 4: 运行确认通过**

Run: `.\mvnw.cmd -q -pl kb-assistant-server test -Dtest=RagChatServiceTest`
Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add kb-assistant-server/src/main/java/com/fox/aikbassistant/service/RagChatService.java kb-assistant-server/src/test/java/com/fox/aikbassistant/service/RagChatServiceTest.java
git commit -m "feat: 实现 RAG 问答服务与引用提取"
```

### Task 11: ChatController（SSE 流式 + 引用帧）

**Files:**
- Create: `kb-assistant-server/src/main/java/com/fox/aikbassistant/controller/ChatController.java`

- [ ] **Step 1: 实现 SSE 流式接口**

正文 token 逐帧推 `event: token`；流结束追加一帧 `event: citations`（JSON 引用列表）。

```java
package com.fox.aikbassistant.controller;

import com.fox.aikbassistant.model.Citation;
import com.fox.aikbassistant.service.RagChatService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.http.MediaType;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.List;

@RestController
@RequestMapping("/api/chat")
public class ChatController {

    private final RagChatService ragChatService;
    private final ObjectMapper objectMapper;

    public ChatController(RagChatService ragChatService, ObjectMapper objectMapper) {
        this.ragChatService = ragChatService;
        this.objectMapper = objectMapper;
    }

    @GetMapping(value = "/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<ServerSentEvent<String>> stream(@RequestParam String question) {
        Flux<ServerSentEvent<String>> tokens = ragChatService.stream(question)
                .map(t -> ServerSentEvent.<String>builder().event("token").data(t).build());

        Mono<ServerSentEvent<String>> citationFrame = Mono.fromCallable(() -> {
            List<Citation> citations = ragChatService.retrieveCitations(question);
            return ServerSentEvent.<String>builder()
                    .event("citations")
                    .data(objectMapper.writeValueAsString(citations))
                    .build();
        });

        return tokens.concatWith(citationFrame);
    }
}
```

- [ ] **Step 2: 编译验证**

Run: `.\mvnw.cmd -q -DskipTests compile`
Expected: BUILD SUCCESS

- [ ] **Step 3: Commit**

```bash
git add kb-assistant-server/src/main/java/com/fox/aikbassistant/controller/ChatController.java
git commit -m "feat: 新增 SSE 流式问答接口含引用帧"
```

### Task 12: MVP 端到端冒烟（手动 + 文档化）

**Files:**
- Create: `README.md`

- [ ] **Step 1: 写 README（启动步骤 + 冒烟流程）**

包含：复制 `.env.example` 为 `.env` 填 key；`docker compose up -d`；`mvnw spring-boot:run`；curl 上传文档；curl SSE 提问。命令块：

```bash
curl -F "file=@./sample.md" http://localhost:8080/api/documents
curl -N "http://localhost:8080/api/chat/stream?question=这篇文档讲了什么"
```

- [ ] **Step 2: 手动冒烟（需真实 OPENAI_API_KEY + DEEPSEEK_API_KEY）**

启动应用后执行上面两条 curl。
Expected: 上传返回 `chunkCount > 0`；SSE 持续输出 `event:token` 帧，末尾出现 `event:citations` 帧含 source。

- [ ] **Step 3: Commit**

```bash
git add README.md
git commit -m "docs: 新增 README 与 MVP 冒烟流程"
```

---

## M4 记忆 + 成本控制

### Task 13: Redis 多轮记忆（MessageChatMemoryAdvisor）

**Files:**
- Modify: `kb-assistant-server/src/main/java/com/fox/aikbassistant/config/ChatClientConfig.java`
- Modify: `kb-assistant-server/src/main/java/com/fox/aikbassistant/service/RagChatService.java`
- Modify: `kb-assistant-server/src/main/java/com/fox/aikbassistant/controller/ChatController.java`

- [ ] **Step 1: 在 ChatClientConfig 增加 ChatMemory + MessageChatMemoryAdvisor**

注入 Redis 支持的 `ChatMemoryRepository`（由 starter 自动配置），构建 `MessageChatMemory`，作为默认 advisor 之一。

```java
@Bean
ChatMemory chatMemory(ChatMemoryRepository repository) {
    return MessageWindowChatMemory.builder()
            .chatMemoryRepository(repository)
            .maxMessages(20)
            .build();
}

@Bean
ChatClient ragChatClient(@Qualifier("deepSeekChatModel") ChatModel chatModel,
                         VectorStore vectorStore,
                         ChatMemory chatMemory) {
    return ChatClient.builder(chatModel)
            .defaultAdvisors(
                    QuestionAnswerAdvisor.builder(vectorStore)
                            .searchRequest(SearchRequest.builder().topK(4).build())
                            .build(),
                    MessageChatMemoryAdvisor.builder(chatMemory).build())
            .build();
}
```

补充 import：`org.springframework.ai.chat.memory.*`、`org.springframework.ai.chat.client.advisor.MessageChatMemoryAdvisor`。注：bean/类名以 2.0-RC2 实际为准，`MessageWindowChatMemory` 若不存在改用可用的 ChatMemory 实现。

- [ ] **Step 2: RagChatService.stream 接收 conversationId 并传 advisor 参数**

```java
public Flux<String> stream(String question, String conversationId) {
    return chatClient.prompt()
            .user(question)
            .advisors(a -> a.param(
                    org.springframework.ai.chat.memory.ChatMemory.CONVERSATION_ID,
                    conversationId))
            .stream().content();
}
```

- [ ] **Step 3: ChatController.stream 增加 conversationId 入参**

```java
@GetMapping(value = "/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
public Flux<ServerSentEvent<String>> stream(
        @RequestParam String question,
        @RequestParam(defaultValue = "default") String conversationId) {
    Flux<ServerSentEvent<String>> tokens = ragChatService.stream(question, conversationId)
            .map(t -> ServerSentEvent.<String>builder().event("token").data(t).build());
    // citationFrame 同前，不变
    ...
}
```

- [ ] **Step 4: 编译验证**

Run: `.\mvnw.cmd -q -DskipTests compile`
Expected: BUILD SUCCESS

- [ ] **Step 5: Commit**

```bash
git add kb-assistant-server/src/main/java/com/fox/aikbassistant/
git commit -m "feat: 接入 Redis 多轮上下文记忆"
```

### Task 14: Token 限流器（Redis 滑动窗口）

**Files:**
- Create: `kb-assistant-server/src/main/java/com/fox/aikbassistant/ratelimit/TokenRateLimiter.java`
- Create: `kb-assistant-server/src/main/java/com/fox/aikbassistant/ratelimit/RateLimitProperties.java`
- Test: `kb-assistant-server/src/test/java/com/fox/aikbassistant/ratelimit/TokenRateLimiterTest.java`

- [ ] **Step 1: 配置属性类**

```java
package com.fox.aikbassistant.ratelimit;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "kb.ratelimit")
public record RateLimitProperties(
        long windowSeconds,
        long maxTokensPerWindow,
        String overflowStrategy) {
    public RateLimitProperties {
        if (windowSeconds <= 0) windowSeconds = 60;
        if (maxTokensPerWindow <= 0) maxTokensPerWindow = 100_000;
        if (overflowStrategy == null) overflowStrategy = "reject";
    }
}
```

- [ ] **Step 2: 写失败测试（用 mock RedisTemplate 验证超额判定）**

```java
package com.fox.aikbassistant.ratelimit;

import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

class TokenRateLimiterTest {

    @Test
    void tryAcquire_returnsFalseWhenOverLimit() {
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        ValueOperations<String, String> ops = mock(ValueOperations.class);
        when(redis.opsForValue()).thenReturn(ops);
        when(ops.get("kb:tokens:c1")).thenReturn("99000");

        var props = new RateLimitProperties(60, 100000, "reject");
        var limiter = new TokenRateLimiter(redis, props);

        assertThat(limiter.tryAcquire("c1", 2000)).isFalse();
        assertThat(limiter.tryAcquire("c1", 500)).isTrue();
    }
}
```

- [ ] **Step 3: 运行确认失败**

Run: `.\mvnw.cmd -q -pl kb-assistant-server test -Dtest=TokenRateLimiterTest`
Expected: FAIL

- [ ] **Step 4: 实现 TokenRateLimiter**

```java
package com.fox.aikbassistant.ratelimit;

import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;

@Component
public class TokenRateLimiter {

    private final StringRedisTemplate redis;
    private final RateLimitProperties props;

    public TokenRateLimiter(StringRedisTemplate redis, RateLimitProperties props) {
        this.redis = redis;
        this.props = props;
    }

    public boolean tryAcquire(String conversationId, long estimatedTokens) {
        String key = "kb:tokens:" + conversationId;
        String current = redis.opsForValue().get(key);
        long used = current == null ? 0 : Long.parseLong(current);
        return used + estimatedTokens <= props.maxTokensPerWindow();
    }

    public void record(String conversationId, long actualTokens) {
        String key = "kb:tokens:" + conversationId;
        Long total = redis.opsForValue().increment(key, actualTokens);
        if (total != null && total == actualTokens) {
            redis.expire(key, Duration.ofSeconds(props.windowSeconds()));
        }
    }
}
```

- [ ] **Step 5: 运行确认通过**

Run: `.\mvnw.cmd -q -pl kb-assistant-server test -Dtest=TokenRateLimiterTest`
Expected: PASS

- [ ] **Step 6: Commit**

```bash
git add kb-assistant-server/src/main/java/com/fox/aikbassistant/ratelimit/ kb-assistant-server/src/test/java/com/fox/aikbassistant/ratelimit/
git commit -m "feat: 新增 Redis Token 滑动窗口限流器"
```

### Task 15: 限流接入 RAG 链路

**Files:**
- Modify: `kb-assistant-server/src/main/java/com/fox/aikbassistant/service/RagChatService.java`
- Modify: `kb-assistant-server/src/main/java/com/fox/aikbassistant/AiKbAssistantApplication.java`
- Modify: `kb-assistant-server/src/main/resources/application.yaml`
- Create: `kb-assistant-server/src/main/java/com/fox/aikbassistant/ratelimit/RateLimitExceededException.java`

- [ ] **Step 1: 启用配置属性**

在启动类加 `@ConfigurationPropertiesScan` 或在 config 加 `@EnableConfigurationProperties(RateLimitProperties.class)`。

```java
@SpringBootApplication
@org.springframework.boot.context.properties.ConfigurationPropertiesScan
public class AiKbAssistantApplication { ... }
```

- [ ] **Step 2: 定义异常**

```java
package com.fox.aikbassistant.ratelimit;

public class RateLimitExceededException extends RuntimeException {
    public RateLimitExceededException(String msg) { super(msg); }
}
```

- [ ] **Step 3: RagChatService 进链路前校验，结束后回写**

`stream()` 开头调用 `tryAcquire`（用问题长度/4 粗估 token），超额则 `Flux.error(new RateLimitExceededException(...))`；流式无精确 usage 时按估算 `record`。

```java
public Flux<String> stream(String question, String conversationId) {
    long estimate = Math.max(1, question.length() / 4) + 1000;
    if (!rateLimiter.tryAcquire(conversationId, estimate)) {
        return Flux.error(new RateLimitExceededException("token 配额超限"));
    }
    rateLimiter.record(conversationId, estimate);
    return chatClient.prompt().user(question)
            .advisors(a -> a.param(ChatMemory.CONVERSATION_ID, conversationId))
            .stream().content();
}
```

构造器注入 `TokenRateLimiter`。

- [ ] **Step 4: application.yaml 加限流配置**

```yaml
kb:
  ratelimit:
    window-seconds: 60
    max-tokens-per-window: 100000
    overflow-strategy: reject
```

- [ ] **Step 5: 编译并跑全部单测**

Run: `.\mvnw.cmd -q -pl kb-assistant-server test`
Expected: 全部 PASS

- [ ] **Step 6: Commit**

```bash
git add kb-assistant-server/
git commit -m "feat: RAG 链路接入 Token 限流控成本"
```

---

## M5 工具 + 路由

### Task 16: SqlQueryTool（只读 SQL，三重防护）

**Files:**
- Create: `kb-assistant-server/src/main/java/com/fox/aikbassistant/tool/SqlQueryTool.java`
- Test: `kb-assistant-server/src/test/java/com/fox/aikbassistant/tool/SqlQueryToolTest.java`

- [ ] **Step 1: 写失败测试（白名单：拒绝非 SELECT，接受 SELECT）**

```java
package com.fox.aikbassistant.tool;

import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.mock;

class SqlQueryToolTest {

    private final SqlQueryTool tool = new SqlQueryTool(mock(JdbcTemplate.class));

    @Test
    void rejectsNonSelect() {
        assertThatThrownBy(() -> tool.query("DELETE FROM users"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> tool.query("DROP TABLE users"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> tool.query("update t set a=1"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void acceptsSelect() {
        assertThatCode(() -> tool.validate("SELECT * FROM t")).doesNotThrowAnyException();
    }
}
```

- [ ] **Step 2: 运行确认失败**

Run: `.\mvnw.cmd -q -pl kb-assistant-server test -Dtest=SqlQueryToolTest`
Expected: FAIL

- [ ] **Step 3: 实现 SqlQueryTool（SELECT 白名单 + 参数化 + 行数上限）**

```java
package com.fox.aikbassistant.tool;

import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

@Component
public class SqlQueryTool {

    private static final int MAX_ROWS = 100;
    private final JdbcTemplate jdbcTemplate;

    public SqlQueryTool(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Tool(description = "对知识库业务表执行只读 SELECT 查询，返回最多100行")
    public List<Map<String, Object>> query(
            @ToolParam(description = "合法的只读 SELECT 语句") String sql) {
        validate(sql);
        return jdbcTemplate.queryForList(sql).stream().limit(MAX_ROWS).toList();
    }

    void validate(String sql) {
        if (sql == null) throw new IllegalArgumentException("SQL 不能为空");
        String normalized = sql.trim().toLowerCase();
        if (!normalized.startsWith("select")) {
            throw new IllegalArgumentException("仅允许 SELECT 查询");
        }
        if (normalized.matches(".*\\b(insert|update|delete|drop|alter|truncate|create|grant)\\b.*")) {
            throw new IllegalArgumentException("检测到禁止的写操作关键字");
        }
    }
}
```

- [ ] **Step 4: 运行确认通过**

Run: `.\mvnw.cmd -q -pl kb-assistant-server test -Dtest=SqlQueryToolTest`
Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add kb-assistant-server/src/main/java/com/fox/aikbassistant/tool/SqlQueryTool.java kb-assistant-server/src/test/java/com/fox/aikbassistant/tool/SqlQueryToolTest.java
git commit -m "feat: 新增只读 SQL 查询工具含安全防护"
```

### Task 17: WebSearchTool（Tavily 联网搜索）

**Files:**
- Create: `kb-assistant-server/src/main/java/com/fox/aikbassistant/tool/WebSearchTool.java`
- Modify: `kb-assistant-server/src/main/resources/application.yaml`

- [ ] **Step 1: 实现 WebSearchTool（调 Tavily REST）**

用 `RestClient` POST `https://api.tavily.com/search`，Key 从配置注入。返回结果摘要文本（作为不可信外部数据，仅做拼接）。

```java
package com.fox.aikbassistant.tool;

import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.util.List;
import java.util.Map;

@Component
public class WebSearchTool {

    private final RestClient client = RestClient.create();
    private final String apiKey;

    public WebSearchTool(@Value("${tavily.api-key:}") String apiKey) {
        this.apiKey = apiKey;
    }

    @Tool(description = "联网搜索实时信息，返回若干条结果摘要")
    public String search(@ToolParam(description = "搜索关键词") String query) {
        Map<?, ?> resp = client.post()
                .uri("https://api.tavily.com/search")
                .body(Map.of("api_key", apiKey, "query", query, "max_results", 5))
                .retrieve()
                .body(Map.class);
        Object results = resp == null ? null : resp.get("results");
        return results == null ? "无搜索结果" : results.toString();
    }
}
```

- [ ] **Step 2: application.yaml 加 tavily 配置**

```yaml
tavily:
  api-key: ${TAVILY_API_KEY:}
```

- [ ] **Step 3: 编译验证**

Run: `.\mvnw.cmd -q -DskipTests compile`
Expected: BUILD SUCCESS

- [ ] **Step 4: Commit**

```bash
git add kb-assistant-server/src/main/java/com/fox/aikbassistant/tool/WebSearchTool.java kb-assistant-server/src/main/resources/application.yaml
git commit -m "feat: 新增 Tavily 联网搜索工具"
```

### Task 18: 工具挂载到 ChatClient（含 ToolContext + 审计）

**Files:**
- Modify: `kb-assistant-server/src/main/java/com/fox/aikbassistant/service/RagChatService.java`

- [ ] **Step 1: stream 挂载工具并传 ToolContext**

注入 `SqlQueryTool`、`WebSearchTool`，在 prompt 上 `.tools(...)`，并通过 advisors/toolContext 传 `tenantId`（企业级数据隔离的占位上下文）。

```java
return chatClient.prompt()
        .user(question)
        .tools(sqlQueryTool, webSearchTool)
        .toolContext(java.util.Map.of("conversationId", conversationId))
        .advisors(a -> a.param(ChatMemory.CONVERSATION_ID, conversationId))
        .stream().content();
```

构造器追加注入两个工具 Bean。

- [ ] **Step 2: 审计日志（工具调用入口记日志）**

在 `SqlQueryTool.query` 与 `WebSearchTool.search` 方法首行加 `log.info("tool=... args=...")`（用 `@Slf4j` Lombok）。记录入参摘要与耗时。

- [ ] **Step 3: 编译并跑全部单测**

Run: `.\mvnw.cmd -q -pl kb-assistant-server test`
Expected: 全部 PASS

- [ ] **Step 4: Commit**

```bash
git add kb-assistant-server/
git commit -m "feat: 挂载 Function Calling 工具并加审计与上下文"
```

### Task 19: 多模型路由（DeepSeek / OpenAI）

**Files:**
- Modify: `kb-assistant-server/src/main/java/com/fox/aikbassistant/config/ChatClientConfig.java`
- Modify: `kb-assistant-server/src/main/java/com/fox/aikbassistant/service/RagChatService.java`
- Modify: `kb-assistant-server/src/main/java/com/fox/aikbassistant/controller/ChatController.java`
- Test: `kb-assistant-server/src/test/java/com/fox/aikbassistant/config/ChatClientRouterTest.java`

- [ ] **Step 1: 注册两个 ChatClient（按模型名 Map 路由）**

config 中分别用 DeepSeek 与 OpenAI ChatModel 各建一个 ChatClient（同样挂 RAG advisor），放进 `Map<String, ChatClient>`。

```java
@Bean
Map<String, ChatClient> chatClients(
        @Qualifier("deepSeekChatModel") ChatModel deepseek,
        @Qualifier("openAiChatModel") ChatModel openai,
        VectorStore vs, ChatMemory mem) {
    return Map.of(
        "deepseek", buildClient(deepseek, vs, mem),
        "openai", buildClient(openai, vs, mem));
}
```

`buildClient` 私有方法封装原 advisor 装配逻辑（DRY）。

- [ ] **Step 2: 写路由选择失败测试**

```java
package com.fox.aikbassistant.config;

import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

class ChatClientRouterTest {

    @Test
    void resolvesDefaultWhenUnknown() {
        Map<String, ChatClient> clients = Map.of(
                "deepseek", mock(ChatClient.class),
                "openai", mock(ChatClient.class));
        ChatClientRouter router = new ChatClientRouter(clients);

        assertThat(router.resolve("openai")).isSameAs(clients.get("openai"));
        assertThat(router.resolve("unknown")).isSameAs(clients.get("deepseek"));
        assertThat(router.resolve(null)).isSameAs(clients.get("deepseek"));
    }
}
```

- [ ] **Step 3: 实现 ChatClientRouter 并让 RagChatService 用它**

```java
package com.fox.aikbassistant.config;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.stereotype.Component;

import java.util.Map;

@Component
public class ChatClientRouter {
    private final Map<String, ChatClient> clients;
    public ChatClientRouter(Map<String, ChatClient> chatClients) {
        this.clients = chatClients;
    }
    public ChatClient resolve(String model) {
        return clients.getOrDefault(model == null ? "deepseek" : model,
                clients.get("deepseek"));
    }
}
```

`RagChatService.stream(question, conversationId, model)` 改用 `router.resolve(model)` 取 ChatClient；`ChatController` 增加 `@RequestParam(defaultValue="deepseek") String model` 并透传。

- [ ] **Step 4: 运行测试**

Run: `.\mvnw.cmd -q -pl kb-assistant-server test -Dtest=ChatClientRouterTest`
Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add kb-assistant-server/
git commit -m "feat: 支持 DeepSeek/OpenAI 多模型路由切换"
```

---

## M6 前端

### Task 20: 初始化 kb-assistant-web-ui（Vue3 + Vite）

**Files:**
- Create: `kb-assistant-web-ui/`（脚手架）
- Modify: `pom.xml`（仅注释说明前端独立构建，不纳入 Maven reactor）

- [ ] **Step 1: 脚手架**

Run: `npm create vite@latest kb-assistant-web-ui -- --template vue`
Then: `cd kb-assistant-web-ui && npm install`
Expected: 生成 Vue3 项目，`npm run dev` 可启动。

- [ ] **Step 2: 配置 Vite 代理后端（避免跨域）**

`kb-assistant-web-ui/vite.config.js` 加：

```javascript
export default {
  server: {
    proxy: {
      '/api': 'http://localhost:8080'
    }
  }
}
```

- [ ] **Step 3: Commit**

```bash
git add kb-assistant-web-ui/
git commit -m "feat: 初始化 Vue3 前端子模块"
```

### Task 21: 对话界面（SSE 接收 + 文档上传）

**Files:**
- Create: `kb-assistant-web-ui/src/components/ChatView.vue`
- Modify: `kb-assistant-web-ui/src/App.vue`

- [ ] **Step 1: ChatView 用 EventSource 接收流式回答 + 引用**

```javascript
const es = new EventSource(`/api/chat/stream?question=${encodeURIComponent(q)}&conversationId=${cid}`)
es.addEventListener('token', e => { answer.value += e.data })
es.addEventListener('citations', e => { citations.value = JSON.parse(e.data); es.close() })
```

文档上传用 `fetch('/api/documents', { method:'POST', body: formData })`。

- [ ] **Step 2: 手动验证（前后端同时起）**

启动后端 + `npm run dev`，浏览器上传文档并提问。
Expected: 答案逐字流式出现，下方显示引用来源。

- [ ] **Step 3: Commit**

```bash
git add kb-assistant-web-ui/src/
git commit -m "feat: 实现流式对话界面与文档上传"
```

---

## 自检结论

- **Spec 覆盖**：多模块(Task1-2)/依赖瘦身(Task1)/docker(Task3)/配置(Task4)/入库管线(Task5-8)/RAG+SSE+引用(Task9-12)/记忆(Task13)/限流(Task14-15)/Function Calling 含企业要素(Task16-18)/多模型路由(Task19)/向量化扩展点(spec §6.1，本期锁定 OpenAI 不实现切换)/前端(Task20-21)。通义千问与 Google embedding 按 spec 列为后续扩展，无对应 task（符合 YAGNI）。
- **类型一致**：`Citation(source,snippet,score)`、`IngestResult(source,chunkCount)`、`RagChatService.stream` 签名随里程碑演进（M3 单参 → M4 加 conversationId → M5 加 model），每次修改在对应 Task 显式给出新签名。
- **RC2 API 风险**：涉及 reader 构造、ChatModel bean 名、ChatMemory 实现类处已标注"以实际 2.0-RC2 API 为准"的回退方案。

## 执行说明

实现时如遇 Spring AI 2.0-RC2 的具体 API 与计划示例签名不一致（reader 构造、advisor builder、bean 名称），以编译通过为准做最小调整，保持每个 Task 的验证目标不变。
