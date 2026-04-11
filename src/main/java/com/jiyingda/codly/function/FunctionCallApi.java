/**
 * @(#)Function.java, 4 月 2, 2026.
 * <p>
 * Copyright 2026 chapaof.com. All rights reserved.
 * chapaof.COM PROPRIETARY/CONFIDENTIAL. Use is subject to license terms.
 */
package com.jiyingda.codly.function;

import com.jiyingda.codly.command.CommandContext;
import com.jiyingda.codly.data.Parameters;

/**
 * Function 接口
 * 定义所有 tool function 需要实现的标准
 */
@SuppressWarnings("unused")
public interface FunctionCallApi {

    /**
     * 获取函数名称
     *
     * @return 函数名称
     */
    String getName();

    /**
     * 获取函数描述
     *
     * @return 函数描述
     */
    String getDescription();

    /**
     * 获取函数参数定义
     *
     * @return 参数定义
     */
    Parameters getParameters();

    /**
     * 执行函数
     *
     * @param argsJson 函数参数（JSON 格式）
     * @return 执行结果
     */
    String execute(String argsJson, CommandContext ctx);

    /**
     * 是否需要用户二次确认才能执行（写操作、危险操作应返回 true）
     */
    default boolean requiresConfirmation() {
        return false;
    }

    /**
     * 生成确认提示摘要，供用户判断是否允许执行。
     * 仅在 {@link #requiresConfirmation()} 返回 true 时被调用。
     *
     * @param argsJson 函数参数（JSON 格式）
     * @return 人类可读的操作摘要
     */
    default String confirmationSummary(String argsJson) {
        return getName() + " " + argsJson;
    }
}


