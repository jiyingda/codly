package com.jiyingda.codly.constants;

/**
 * Codly 启动 Banner
 */
public class Banner {

    private static final String CYAN  = "\033[36m";
    private static final String GREEN = "\033[32m";
    private static final String BOLD  = "\033[1m";
    private static final String DIM   = "\033[2m";
    private static final String RESET = "\033[0m";

    public static final String TEXT = BOLD + CYAN +
        "   ██████╗ ██████╗ ██████╗ ██╗  ██╗   ██╗\n" +
        "  ██╔════╝██╔═══██╗██╔══██╗██║  ╚██╗ ██╔╝\n" +
        "  ██║     ██║   ██║██║  ██║██║   ╚████╔╝ \n" +
        "  ██║     ██║   ██║██║  ██║██║    ╚██╔╝  \n" +
        "  ╚██████╗╚██████╔╝██████╔╝███████╗██║   \n" +
        "   ╚═════╝ ╚═════╝ ╚═════╝ ╚══════╝╚═╝   \n" +
        RESET +
        GREEN +
        "          /\\_____/\\                       \n" +
        "         ( o\u0305 . o\u0305 )   " + RESET + BOLD + "Your AI Coding Companion" + RESET + GREEN + "\n" +
        "          > ^ ^ <                         \n" +
        "         /|     |\\                        \n" +
        RESET +
        DIM + "  ─────────────────────────────────────────\n" + RESET;

    private Banner() {}
}
