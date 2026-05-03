package com.jiyingda.codly.command;

import com.jiyingda.codly.function.UserConfirmation;
import com.jiyingda.codly.knowledge.IndexMdWriter;
import com.jiyingda.codly.knowledge.KnowledgePack;
import com.jiyingda.codly.knowledge.KnowledgeRepository;
import com.jiyingda.codly.knowledge.KnowledgeSearcher;
import com.jiyingda.codly.knowledge.SectionId;
import picocli.CommandLine.Command;
import picocli.CommandLine.Parameters;

import java.io.IOException;
import java.nio.file.Files;
import java.util.List;
import java.util.Optional;

/**
 * /kb 命令：管理 ~/.codly/knowledge/ 下的知识包。
 * 支持子命令：show / section / search / scaffold / status / delete / reload。
 * 不带子命令时打印顶层 INDEX.md。
 */
@Command(name = "/kb", description = "查看与管理知识包")
public class KbCommand implements Runnable, CliCommand {

    @Parameters(index = "0", defaultValue = "",
            description = "子命令：show <name> / section <name> <section> / search <query> / scaffold <name> \"<system>\" / status <name> <draft|reviewed|stale> / delete <name> / reload")
    private String action = "";

    @Parameters(index = "1..*", arity = "0..*", description = "子命令参数")
    private String[] args = new String[0];

    @Override
    public void run() {
    }

    @Override
    public boolean execute(CommandContext ctx) {
        KnowledgeRepository repo = KnowledgeRepository.getInstance();
        switch (action.toLowerCase()) {
            case "" -> printIndex(repo);
            case "show" -> showPack(repo);
            case "section" -> showSection(repo);
            case "search" -> searchPacks(repo);
            case "scaffold" -> scaffoldPack(repo);
            case "status" -> updateStatus(repo);
            case "delete" -> deletePack(repo, ctx);
            case "reload" -> reloadAll(repo);
            default -> System.out.println("未知子命令：" + action + "，可用：show / section / search / scaffold / status / delete / reload");
        }
        return false;
    }

    private void printIndex(KnowledgeRepository repo) {
        if (repo.size() == 0) {
            System.out.println("暂无知识包。可用 /kb scaffold <name> \"<system>\" 生成一个空骨架。");
            return;
        }
        String indexMd = IndexMdWriter.read(repo.getRoot());
        if (indexMd.isBlank()) {
            System.out.println("INDEX.md 不存在，知识包列表（" + repo.size() + " 个）：");
            for (KnowledgePack pack : repo.all()) {
                System.out.println("  " + pack.toCatalogLine());
            }
            return;
        }
        System.out.println(indexMd);
    }

    private void showPack(KnowledgeRepository repo) {
        if (args.length < 1) {
            System.out.println("用法：/kb show <name>");
            return;
        }
        String name = args[0];
        Optional<KnowledgePack> pack = repo.find(name);
        if (pack.isEmpty()) {
            System.out.println("未找到知识包：" + name);
            return;
        }
        try {
            System.out.println(Files.readString(pack.get().getKnowledgeMdPath()));
        } catch (IOException e) {
            System.out.println("读取失败：" + e.getMessage());
        }
    }

    private void showSection(KnowledgeRepository repo) {
        if (args.length < 2) {
            System.out.println("用法：/kb section <name> <section>");
            System.out.println("section 取值：positioning / concepts / relations / flows / diff / pending / sources");
            return;
        }
        String name = args[0];
        String slug = args[1];
        Optional<SectionId> sid = SectionId.fromSlug(slug);
        if (sid.isEmpty()) {
            System.out.println("section 取值不合法：" + slug);
            return;
        }
        Optional<KnowledgePack> pack = repo.find(name);
        if (pack.isEmpty()) {
            System.out.println("未找到知识包：" + name);
            return;
        }
        String body = pack.get().getSection(sid.get());
        if (body == null || body.isBlank()) {
            System.out.println("该节为空或缺失：" + name + " / " + slug);
            return;
        }
        System.out.println(sid.get().standardHeading());
        System.out.println();
        System.out.println(body);
    }

    private void searchPacks(KnowledgeRepository repo) {
        if (args.length < 1) {
            System.out.println("用法：/kb search <query>");
            return;
        }
        String query = String.join(" ", args);
        List<KnowledgeSearcher.Hit> hits = repo.search(query, 10);
        if (hits.isEmpty()) {
            System.out.println("未命中任何知识包");
            return;
        }
        System.out.println("命中 " + hits.size() + " 条：");
        for (KnowledgeSearcher.Hit hit : hits) {
            System.out.printf("  [%d] %s · %s%n", hit.score(), hit.packName(), hit.sectionId().slug());
            if (!hit.snippet().isEmpty()) {
                System.out.println("      " + hit.snippet());
            }
        }
    }

    private void scaffoldPack(KnowledgeRepository repo) {
        if (args.length < 1) {
            System.out.println("用法：/kb scaffold <name> [<system 描述>]");
            return;
        }
        String name = args[0];
        String system = args.length >= 2 ? joinFrom(1) : name;
        KnowledgeRepository.OpResult result = repo.scaffold(name, system, "");
        System.out.println(result.message());
    }

    private void updateStatus(KnowledgeRepository repo) {
        if (args.length < 2) {
            System.out.println("用法：/kb status <name> <draft|reviewed|stale>");
            return;
        }
        String name = args[0];
        String newStatus = args[1];
        KnowledgeRepository.OpResult result = repo.setStatus(name, newStatus);
        System.out.println(result.message());
    }

    private void deletePack(KnowledgeRepository repo, CommandContext ctx) {
        if (args.length < 1) {
            System.out.println("用法：/kb delete <name>");
            return;
        }
        String name = args[0];
        Optional<KnowledgePack> pack = repo.find(name);
        if (pack.isEmpty()) {
            System.out.println("未找到知识包：" + name);
            return;
        }
        boolean allowed = UserConfirmation.confirm(ctx.getTerminal(),
                "删除知识包 " + name + "（" + pack.get().getDir() + "）？");
        if (!allowed) {
            System.out.println("已取消");
            return;
        }
        KnowledgeRepository.OpResult result = repo.delete(name);
        System.out.println(result.message());
    }

    private void reloadAll(KnowledgeRepository repo) {
        repo.reload();
        System.out.println("已重新加载，当前知识包数：" + repo.size());
    }

    private String joinFrom(int startIdx) {
        StringBuilder sb = new StringBuilder();
        for (int i = startIdx; i < args.length; i++) {
            if (sb.length() > 0) {
                sb.append(' ');
            }
            sb.append(args[i]);
        }
        return sb.toString();
    }
}
