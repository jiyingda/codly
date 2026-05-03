package com.jiyingda.codly.knowledge;

/**
 * sources.json 中 pages[] 的单条记录，对齐 skill-kit/templates.md 第 5 节。
 * 仅作为索引保存元数据，正文按 pageId 重抓时再获取。
 *
 * @param pageId            Confluence 页面 ID（必填）
 * @param title             页面标题
 * @param url               页面 URL
 * @param confluenceUpdated 文档侧最后更新时间（ISO8601 字符串）
 * @param depth             递归深度，入口页 = 0
 * @param parentPageId      父页 ID，入口页为 null
 * @param note              备注（可空）
 */
public record SourceEntry(
        String pageId,
        String title,
        String url,
        String confluenceUpdated,
        int depth,
        String parentPageId,
        String note) {
}
