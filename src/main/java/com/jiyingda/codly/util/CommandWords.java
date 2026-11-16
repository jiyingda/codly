package com.jiyingda.codly.util;

import org.jline.reader.Parser;
import org.jline.reader.impl.DefaultParser;
import java.util.List;

/** 引号和转义只参与分词，不做 shell 展开或执行。 */
public final class CommandWords {
    private CommandWords() {}
    public static List<String> parse(String input) {
        try {
            return new DefaultParser().eofOnUnclosedQuote(true).eofOnEscapedNewLine(true)
                    .parse(input, input.length(), Parser.ParseContext.ACCEPT_LINE).words();
        } catch (RuntimeException error) { throw new IllegalArgumentException("参数引号或转义不完整，请检查输入。"); }
    }
}
