package com.jiyingda.codly.config;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONObject;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.List;
import java.util.function.Consumer;

/**
 * 从 ~/.codly/settings.json 读取配置的类
 */
public class Config {

    private static final String CONFIG_PATH = System.getProperty("user.home") + "/.codly/settings.json";
    private static final String CONFIG_EXAMPLE_PATH = CONFIG_PATH + ".example";

    private String apiKey;
    private Boolean enableThinking;
    private String defaultModel;
    private List<String> availableModels;
    private String apiUrl;
    private boolean configLoaded = false;
    private String loadError = null;

    private static Config instance;

    private Config() {
        load();
    }

    public static Config getInstance() {
        if (instance == null) {
            instance = new Config();
        }
        return instance;
    }

    /**
     * 重新加载配置
     */
    public void load() {
        File configFile = new File(CONFIG_PATH);
        if (!configFile.exists()) {
            this.loadError = "配置文件不存在";
            this.configLoaded = false;
            return;
        }

        try {
            String content = Files.readString(Paths.get(CONFIG_PATH));
            JSONObject json = JSON.parseObject(content);
            if (json != null) {
                this.apiKey = json.getString("apiKey");
                this.enableThinking = json.getBoolean("enableThinking");
                this.defaultModel = json.getString("defaultModel");
                this.availableModels = json.getJSONArray("availableModels")
                    != null ? json.getJSONArray("availableModels").toJavaList(String.class) : null;
                this.apiUrl = json.getString("apiUrl");
            }
            this.configLoaded = true;
            this.loadError = null;
        } catch (IOException e) {
            this.loadError = "读取配置文件失败：" + e.getMessage();
            this.configLoaded = false;
        }
    }

    /**
     * 检查配置是否成功加载
     */
    public boolean isConfigLoaded() {
        return configLoaded;
    }

    /**
     * 获取加载错误信息
     */
    public String getLoadError() {
        return loadError;
    }

    public String getApiKey() {
        return apiKey;
    }

    public Boolean getEnableThinking() {
        return enableThinking;
    }

    public String getDefaultModel() {
        return defaultModel;
    }

    public List<String> getAvailableModels() {
        return availableModels;
    }

    public String getApiUrl() {
        return apiUrl;
    }

    /**
     * 获取 API Key，配置文件中必须配置
     */
    public static String getApiKeySafe() {
        Config config = getInstance();
        String apiKey = config.getApiKey();
        if (apiKey == null || apiKey.isBlank()) {
            return null;
        }
        return apiKey;
    }

    /**
     * 获取默认模型，配置文件中必须配置
     */
    public static String getDefaultModelSafe() {
        Config config = getInstance();
        String model = config.getDefaultModel();
        if (model == null || model.isBlank()) {
            return null;
        }
        return model;
    }

    /**
     * 获取 API URL，配置文件中可选配置
     */
    public static String getApiUrlSafe() {
        Config config = getInstance();
        String apiUrl = config.getApiUrl();
        if (apiUrl == null || apiUrl.isBlank()) {
            apiUrl = "https://dashscope.aliyuncs.com/compatible-mode/v1/chat/completions";
        }
        return apiUrl;
    }

    /**
     * 获取是否启用 thinking，优先从配置文件读取
     */
    public static Boolean getEnableThinkingSafe() {
        Config config = getInstance();
        Boolean enableThinking = config.getEnableThinking();
        if (enableThinking == null) {
            enableThinking = true;
        }
        return enableThinking;
    }

    /**
     * 获取可用模型列表，优先从配置文件读取
     */
    public static List<String> getAvailableModelsSafe() {
        Config config = getInstance();
        List<String> models = config.getAvailableModels();
        if (models == null || models.isEmpty()) {
            models = List.of(getDefaultModelSafe());
        }
        return models;
    }

    /**
     * 获取配置文件路径
     */
    public static String getConfigPath() {
        return CONFIG_PATH;
    }

    public static void printLoadErr(Config config, Consumer<String> out) {
        out.accept("");
        out.accept("  错误：" + config.getLoadError());
        out.accept("  请创建配置文件：" + Config.getConfigPath());
        out.accept("");
        out.accept("  配置文件格式:");
        out.accept("  {");
        out.accept("    \"apiKey\": \"your-api-key-here\",");
        out.accept("    \"enableThinking\": true,");
        out.accept("    \"defaultModel\": \"qwen3.5-plus\",");
        out.accept("    \"availableModels\": [...]");
        out.accept("  }");
        out.accept("");
    }

    public static void printLlmConfigErr(ConfigException e, Consumer<String> out) {
        out.accept("");
        out.accept("  错误：" + e.getMessage());
        out.accept("  请检查配置文件：" + Config.getConfigPath());
        out.accept("");
    }
}