/**
 * @(#)Function.java, 4 月 2, 2026.
 * <p>
 * Copyright 2026 chapaof.com. All rights reserved.
 * chapaof.COM PROPRIETARY/CONFIDENTIAL. Use is subject to license terms.
 */
package com.jiyingda.codly.function;

/**
 * Function 接口
 * 定义所有 tool function 需要实现的标准
 */
@SuppressWarnings("unused")
public interface Function {

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
     * 执行函数
     *
     * @param argsJson 函数参数（JSON 格式）
     * @return 执行结果
     */
    String execute(String argsJson);
}

