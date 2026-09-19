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

    /** provider 取值：通义千问 */
    public static final String PROVIDER_QWEN = "qwen";
    /** provider 取值：DeepSeek */
    public static final String PROVIDER_DEEPSEEK = "deepseek";

    private static final String DEFAULT_API_URL_QWEN =
        "https://dashscope.aliyuncs.com/compatible-mode/v1/chat/completions";
    private static final String DEFAULT_API_URL_DEEPSEEK = "https://api.deepseek.com/chat/completions";
    private static final String DEFAULT_DEEPSEEK_MODEL = "deepseek-flash";
    private static final String DEFAULT_DEEPSEEK_REASONING_EFFORT = "high";

    private String provider;
    private String apiKey;
    private String iqsApiKey;
    private Boolean enableThinking;
    private String defaultModel;
    private List<String> availableModels;
    private String apiUrl;

    private String deepseekApiKey;
    private String deepseekApiUrl;
    private String deepseekModel;
    private String deepseekReasoningEffort;
    private List<String> deepseekAvailableModels;

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
                this.provider = json.getString("provider");
                this.apiKey = json.getString("apiKey");
                this.iqsApiKey = json.getString("iqsApiKey");
                this.enableThinking = json.getBoolean("enableThinking");
                this.defaultModel = json.getString("defaultModel");
                this.availableModels = json.getJSONArray("availableModels")
                    != null ? json.getJSONArray("availableModels").toJavaList(String.class) : null;
                this.apiUrl = json.getString("apiUrl");

                JSONObject deepseek = json.getJSONObject("deepseek");
                if (deepseek != null) {
                    this.deepseekApiKey = deepseek.getString("apiKey");
                    this.deepseekApiUrl = deepseek.getString("apiUrl");
                    this.deepseekModel = deepseek.getString("model");
                    this.deepseekReasoningEffort = deepseek.getString("reasoningEffort");
                    this.deepseekAvailableModels = deepseek.getJSONArray("availableModels")
                        != null ? deepseek.getJSONArray("availableModels").toJavaList(String.class) : null;
                }
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
    public boolean isNotConfigLoaded() {
        return !configLoaded;
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

    public String getIqsApiKey() {
        return iqsApiKey;
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

    public String getProvider() {
        return provider;
    }

    public String getDeepseekApiKey() {
        return deepseekApiKey;
    }

    public String getDeepseekApiUrl() {
        return deepseekApiUrl;
    }

    public String getDeepseekModel() {
        return deepseekModel;
    }

    public String getDeepseekReasoningEffort() {
        return deepseekReasoningEffort;
    }

    public List<String> getDeepseekAvailableModels() {
        return deepseekAvailableModels;
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
     * 获取通义搜索 API Key
     */
    public static String getIqsApiKeySafe() {
        Config config = getInstance();
        String key = config.getIqsApiKey();
        if (key == null || key.isBlank()) {
            return null;
        }
        return key;
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
            apiUrl = DEFAULT_API_URL_QWEN;
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
            enableThinking = false;
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
     * 获取当前生效的 provider，未配置时默认通义千问
     */
    public static String getProviderSafe() {
        String provider = getInstance().getProvider();
        if (provider == null || provider.isBlank()) {
            return PROVIDER_QWEN;
        }
        return provider.trim().toLowerCase();
    }

    /**
     * 获取 DeepSeek API Key，未配置时返回 null
     */
    public static String getDeepseekApiKeySafe() {
        String apiKey = getInstance().getDeepseekApiKey();
        if (apiKey == null || apiKey.isBlank()) {
            return null;
        }
        return apiKey;
    }

    /**
     * 获取 DeepSeek API URL，未配置时返回官方默认地址
     */
    public static String getDeepseekApiUrlSafe() {
        String apiUrl = getInstance().getDeepseekApiUrl();
        if (apiUrl == null || apiUrl.isBlank()) {
            apiUrl = DEFAULT_API_URL_DEEPSEEK;
        }
        return apiUrl;
    }

    /**
     * 获取 DeepSeek 默认模型，未配置时返回 deepseek-flash
     */
    public static String getDeepseekModelSafe() {
        String model = getInstance().getDeepseekModel();
        if (model == null || model.isBlank()) {
            model = DEFAULT_DEEPSEEK_MODEL;
        }
        return model;
    }

    /**
     * 获取 DeepSeek 可用模型列表
     */
    public static List<String> getDeepseekAvailableModelsSafe() {
        List<String> models = getInstance().getDeepseekAvailableModels();
        if (models == null || models.isEmpty()) {
            models = List.of(DEFAULT_DEEPSEEK_MODEL, "deepseek-v4-pro");
        }
        return models;
    }

    /**
     * 获取 DeepSeek 思考强度（none / low / high / max），未配置时默认 high
     */
    public static String getDeepseekReasoningEffortSafe() {
        String effort = getInstance().getDeepseekReasoningEffort();
        if (effort == null || effort.isBlank()) {
            effort = DEFAULT_DEEPSEEK_REASONING_EFFORT;
        }
        return effort;
    }

    /**
     * 获取当前 provider 对应的可用模型列表
     */
    public static List<String> getCurrentProviderModelsSafe() {
        if (PROVIDER_DEEPSEEK.equals(getProviderSafe())) {
            return getDeepseekAvailableModelsSafe();
        }
        return getAvailableModelsSafe();
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
        out.accept("    \"provider\": \"qwen\",");
        out.accept("    \"apiKey\": \"your-api-key-here\",");
        out.accept("    \"enableThinking\": true,");
        out.accept("    \"defaultModel\": \"qwen3.5-plus\",");
        out.accept("    \"availableModels\": [...] ,");
        out.accept("    \"deepseek\": {");
        out.accept("      \"apiKey\": \"sk-your-deepseek-key\",");
        out.accept("      \"model\": \"deepseek-flash\"");
        out.accept("    }");
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