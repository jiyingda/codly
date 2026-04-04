package com.jiyingda.codly.config;

/**
 * 配置异常类，用于表示配置相关的错误。
 */
public class ConfigException extends RuntimeException {

    public ConfigException(String message) {
        super(message);
    }

    public ConfigException(String message, Throwable cause) {
        super(message, cause);
    }
}