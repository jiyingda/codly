package com.jiyingda.codly.command;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONObject;
import com.jiyingda.codly.data.ChatRequest;
import com.jiyingda.codly.data.Message;
import com.jiyingda.codly.util.HttpClientUtil;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import picocli.CommandLine.Command;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;

/**
 * /gen-skill 命令：根据最近的对话和流程自动生成一个 skill。
 * 前置判断：如果对话内容过于简单或仅闲聊，则不生成。
 */
@Command(name = "/gen-skill", description = "根据最近的对话自动生成一个 skill")
public class GenSkillCommand implements Runnable, CliCommand {

    private static final Logger logger = LoggerFactory.getLogger(GenSkillCommand.class);
    private static final MediaType JSON_MEDIA_TYPE = MediaType.parse("application/json; charset=utf-8");

    private static final String SHOULD_GEN_PROMPT = """
        你是一个判断助手。根据下面的对话内容，判断是否适合生成一个 skill（可复用的命令/技能）。

        判断标准：
        1. 对话中是否完成了一个有意义的工作流程（如：实现了某个功能、修复了 bug、完成了一次重构等）
        2. 这个流程是否可能在未来被重复使用，或具有通用性
        3. 是否包含足够的上下文来生成一个合理的 skill

        不适合生成的情况：
        - 仅闲聊、问答、简单解释代码
        - 仅做了微小修改（如改一个变量名、加一行日志）
        - 没有明确的工作流程

        请用以下 JSON 格式回复（严格遵守，不要输出任何其他内容）：
        {
          "shouldGenerate": true/false,
          "reason": "判断原因（一句话）"
        }
        """;

    private static final String GEN_SKILL_PROMPT = """
        你是一个 skill 生成助手。根据下面的对话内容，生成一个合理的 skill 定义。

        Skill 的格式为 SKILL.md 文件，包含 YAML frontmatter 和 Markdown 正文：

        ---
        name: <小写+连字符的英文名称>
        description: <一句话描述 skill 的功能和触发时机>
        allowed-tools: [<需要的工具列表，如 Read, Grep, Bash>]
        ---

        <skill 的具体指令，包含步骤、规则、注意事项等>

        要求：
        1. name 使用小写英文+连字符（如：fix-bug、create-api、refactor-auth）
        2. description 精准描述功能和使用场景
        3. 正文用祈使句（动词开头），步骤清晰
        4. 控制在 200 行以内
        5. 只输出 SKILL.md 的内容，不要输出其他内容

        基于对话中完成的工作流程来生成，确保 skill 可以直接使用。
        """;

    @Override
    public void run() {}

    @Override
    public boolean execute(CommandContext ctx) {
        List<Message> memory = ctx.getMemory();

        if (memory.isEmpty()) {
            System.out.println("当前没有对话记录，无法生成 skill");
            return false;
        }

        System.out.println("正在分析最近的对话...");

        // Step 1: 判断是否可以生成 skill
        String decisionJson = callLlm(ctx, buildConversationContext(memory), SHOULD_GEN_PROMPT, false);
        if (decisionJson == null || decisionJson.isBlank()) {
            System.out.println("分析失败，无法判断是否生成 skill");
            return false;
        }

        logger.info("生成判断结果: {}", decisionJson);

        JSONObject decision;
        try {
            decision = JSON.parseObject(decisionJson);
        } catch (Exception e) {
            logger.error("解析判断结果失败: {}", e.getMessage());
            System.out.println("分析结果格式异常");
            return false;
        }

        boolean shouldGenerate = decision.getBooleanValue("shouldGenerate");
        String reason = decision.getString("reason");

        if (!shouldGenerate) {
            System.out.println("不适合生成 skill：" + reason);
            return false;
        }

        System.out.println("检测到可生成的 skill：" + reason);
        System.out.println("正在生成 skill...");

        // Step 2: 生成 skill 内容
        String skillContent = callLlm(ctx, buildConversationContext(memory), GEN_SKILL_PROMPT, true);
        if (skillContent == null || skillContent.isBlank()) {
            System.out.println("skill 生成失败");
            return false;
        }

        // Step 3: 解析 skill name 和保存到本地
        String skillName = extractSkillName(skillContent);
        Path skillDir = Paths.get(System.getProperty("user.home"), ".codly", "skills", skillName);
        Path skillFile = skillDir.resolve("SKILL.md");

        try {
            Files.createDirectories(skillDir);
            Files.writeString(skillFile, skillContent, StandardCharsets.UTF_8);
            System.out.println("skill 已生成: " + skillFile);
            System.out.println("路径: " + skillDir);
        } catch (IOException e) {
            logger.error("保存 skill 失败: {}", e.getMessage(), e);
            System.out.println("保存 skill 失败：" + e.getMessage());
        }

        return false;
    }

    /**
     * 构建对话上下文字符串，只取最近的消息避免过长。
     */
    private String buildConversationContext(List<Message> memory) {
        // 取最近 20 条消息
        int start = Math.max(0, memory.size() - 20);
        StringBuilder sb = new StringBuilder();
        for (int i = start; i < memory.size(); i++) {
            Message msg = memory.get(i);
            sb.append("[").append(msg.getRole()).append("]: ").append(msg.getContent()).append("\n");
        }
        return sb.toString();
    }

    /**
     * 调用 LLM 获取回复。
     */
    private String callLlm(CommandContext ctx, String conversationContext, String systemPrompt, boolean stream) {
        try {
            String apiKey = com.jiyingda.codly.config.Config.getApiKeySafe();
            String apiUrl = com.jiyingda.codly.config.Config.getApiUrlSafe();
            String model = ctx.getLlmClient().getModel();

            List<Message> messages = new ArrayList<>();
            messages.add(Message.fromSystem(systemPrompt));
            messages.add(Message.fromUser(conversationContext));

            ChatRequest req = new ChatRequest();
            req.setModel(stream ? model : "qwen-turbo");
            req.setStream(stream);
            req.setTemperature(stream ? 0.7 : 0.3);
            req.setEnable_thinking(false);
            req.setResult_format("message");
            req.setMessages(messages);

            String jsonBody = JSON.toJSONString(req);
            logger.info("gen-skill LLM请求: model={}, apiUrl={}", req.getModel(), apiUrl);

            Request request = new Request.Builder()
                .url(apiUrl)
                .addHeader("Authorization", "Bearer " + apiKey)
                .addHeader("Content-Type", "application/json")
                .post(RequestBody.create(jsonBody, JSON_MEDIA_TYPE))
                .build();

            OkHttpClient client = HttpClientUtil.createOptimizedHttpClient();

            if (stream) {
                return callLlmStream(client, request);
            } else {
                return callLlmSync(client, request);
            }
        } catch (Exception e) {
            logger.error("gen-skill LLM调用失败: {}", e.getMessage(), e);
            return null;
        }
    }

    private String callLlmSync(OkHttpClient client, Request request) throws IOException {
        try (Response response = client.newCall(request).execute()) {
            if (!response.isSuccessful() || response.body() == null) {
                logger.error("gen-skill 同步请求失败: {}", response.code());
                return null;
            }
            String body = response.body().string();
            logger.info("gen-skill 同步响应: {}", body);
            JSONObject json = JSON.parseObject(body);
            return json
                .getJSONArray("choices")
                .getJSONObject(0)
                .getJSONObject("message")
                .getString("content");
        }
    }

    private String callLlmStream(OkHttpClient client, Request request) throws IOException {
        StringBuilder fullContent = new StringBuilder();
        try (Response response = client.newCall(request).execute()) {
            if (!response.isSuccessful() || response.body() == null) {
                logger.error("gen-skill 流式请求失败: {}", response.code());
                return null;
            }
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(response.body().byteStream()))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    if (!line.startsWith("data: ")) continue;
                    String data = line.substring(6).trim();
                    if ("[DONE]".equals(data)) break;
                    JSONObject json = JSON.parseObject(data);
                    var choices = json.getJSONArray("choices");
                    if (choices == null || choices.isEmpty()) continue;
                    var delta = choices.getJSONObject(0).getJSONObject("delta");
                    if (delta == null) continue;
                    String content = delta.getString("content");
                    if (content != null && !content.isEmpty()) {
                        fullContent.append(content);
                    }
                }
            }
        }
        logger.info("gen-skill 流式响应长度: {}", fullContent.length());
        return fullContent.toString();
    }

    /**
     * 从 skill 内容中提取 name（从 frontmatter 中解析）。
     */
    private String extractSkillName(String content) {
        String[] lines = content.split("\n");
        for (String line : lines) {
            if (line.startsWith("name:")) {
                String name = line.substring("name:".length()).trim();
                // 移除可能的引号
                if ((name.startsWith("\"") && name.endsWith("\"")) || (name.startsWith("'") && name.endsWith("'"))) {
                    name = name.substring(1, name.length() - 1);
                }
                return name;
            }
        }
        // fallback
        return "new-skill";
    }
}