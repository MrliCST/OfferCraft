package com.example.domain.zsxq.vector;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

import org.springframework.stereotype.Component;

import dev.langchain4j.data.document.Document;
import dev.langchain4j.data.document.DocumentSplitter;
import dev.langchain4j.data.document.Metadata;
import dev.langchain4j.data.document.splitter.DocumentByParagraphSplitter;
import dev.langchain4j.data.segment.TextSegment;

/**
 * 把一篇清洗后的文档切成若干块：先按 Markdown 标题分节，节太长再交给 langchain4j 的段落切块器。
 *
 * <p>这个类是官方 {@link DocumentSplitter} 接口的一个实现，所以能直接插进
 * {@code EmbeddingStoreIngestor} 那条链（切块 → 向量化 → 入库），不用自己写编排。
 *
 * <p>为什么标题这层要自己写：星主长文（实测 1.4~2.5 万字）是靠 {@code ### 1. 定位与演进} 这类标题
 * 组织的，召回时要能回答「这段出自哪一篇的哪一节」。langchain4j 的
 * {@link DocumentByParagraphSplitter} 只认 {@code \n\n} 段落边界，会把标题连着下一节一起打包，
 * 标题归属就没了。所以分工是：<b>标题分节归我们，节内再切归官方</b>——
 * 段落打包、超长段降级到句子切、段间重叠（overlap）都用它的现成能力。
 *
 * <p>尺寸：软上限 900 字、段间重叠 120 字。百炼 v3 单条上限 8192 token，900 汉字约 600 token，
 * 就算加上标题前缀和重叠也远远够用。
 *
 * <p>输出的每个 {@link TextSegment} 继承文档级元数据（doc_id / post_type / author 等），
 * 再补上本块的 {@code index} 和 {@code heading}—— 前两者是入库时拼 chunk_id 的依据，
 * 见 {@link ZsxqChunkEmbeddingStore}。
 *
 * <p>兜底：切块器遇到「一段没有任何句读、自己也切不动」的极端文本会抛异常，这里捕获后退回定长滑窗，
 * 不让一篇脏数据把整批向量化卡死。
 */
@Component
public class MarkdownChunker implements DocumentSplitter {

    /** 单块软上限（字）。 */
    static final int SOFT_MAX = 900;
    /** 相邻块的重叠（字），避免一句话被切断后两边都丢了语境。 */
    static final int OVERLAP = 120;
    /** 太短的节并进下一节，不然会切出一堆几十字的碎片块。 */
    private static final int MIN_SECTION = 150;

    /** 元数据键：所属文档 id（入库时拼 chunk_id 用）。 */
    static final String MD_DOC_ID = "doc_id";
    /** 元数据键：块在文内的序号（从 0 开始）。 */
    static final String MD_INDEX = "index";
    /** 元数据键：所属小节标题。 */
    static final String MD_HEADING = "heading";

    private static final Pattern ATX_HEADING = Pattern.compile("^#{1,6}\\s+\\S.*$");
    /** 另一种标题写法：上一行是标题文字，下一行是 --- 或 ===。 */
    private static final Pattern SETEXT_UNDERLINE = Pattern.compile("^(?:-{3,}|={3,})\\s*$");

    private final DocumentSplitter bodySplitter = new DocumentByParagraphSplitter(SOFT_MAX, OVERLAP);

    @Override
    public List<TextSegment> split(Document document) {
        String content = document.text();
        if (content == null || content.isBlank()) {
            return List.of();
        }
        List<TextSegment> out = new ArrayList<>();
        int seq = 0;
        for (Section s : splitByHeading(content)) {
            String body = s.body().trim();
            if (body.isEmpty()) {
                continue;
            }
            String prefix = s.heading() == null ? "" : s.heading() + "\n\n";
            if (body.length() <= SOFT_MAX) {
                out.add(segment(document, seq++, s.heading(), (prefix + body).trim()));
                continue;
            }
            for (String piece : splitLongBody(body)) {
                out.add(segment(document, seq++, s.heading(), (prefix + piece).trim()));
            }
        }
        if (out.isEmpty()) {
            out.add(segment(document, 0, null, content.trim()));
        }
        return out;
    }

    /** 块 = 文档级元数据 + 本块的序号/标题，标题拼进正文开头，检索时自带语境。 */
    private static TextSegment segment(Document doc, int seq, String heading, String text) {
        Metadata md = Metadata.from(doc.metadata().toMap());
        md.put(MD_INDEX, seq);
        if (heading != null) {
            // Metadata 不接受 null 值，没标题就别放这个键（入库后 heading 列是 NULL）
            md.put(MD_HEADING, heading);
        }
        return TextSegment.from(text, md);
    }

    /**
     * 按 Markdown 标题分节。两种标题写法都认：{@code # 标题} 和 {@code 标题\n------}。
     * 分完再把过短的节并进下一节。
     */
    List<Section> splitByHeading(String content) {
        String[] lines = content.replace("\r\n", "\n").split("\n", -1);
        List<Section> raw = new ArrayList<>();
        StringBuilder body = new StringBuilder();
        String heading = null;
        for (int i = 0; i < lines.length; i++) {
            String line = lines[i];
            if (ATX_HEADING.matcher(line).matches()) {
                raw.add(new Section(heading, body.toString()));
                heading = line.trim();
                body.setLength(0);
                continue;
            }
            if (isSetextHeading(lines, i)) {
                raw.add(new Section(heading, body.toString()));
                heading = line.trim();
                body.setLength(0);
                i++;   // 下划线那行不算正文
                continue;
            }
            body.append(line).append('\n');
        }
        raw.add(new Section(heading, body.toString()));
        return mergeTinySections(raw);
    }

    /**
     * 是不是「文字行 + 下划线」式标题。
     *
     * <p>下划线本身也是 Markdown 的分隔线，所以加两条限制防误判：上一行不能太长（标题不会写满一行），
     * 且不能以句号结尾（句号结尾的是正文段落，下面的 --- 多半只是分隔线）。
     */
    private static boolean isSetextHeading(String[] lines, int i) {
        if (i + 1 >= lines.length) {
            return false;
        }
        String line = lines[i].trim();
        return !line.isEmpty()
                && line.length() <= 60
                && !line.endsWith("。")
                && SETEXT_UNDERLINE.matcher(lines[i + 1].trim()).matches();
    }

    private static List<Section> mergeTinySections(List<Section> raw) {
        List<Section> out = new ArrayList<>();
        Section pending = null;
        for (Section s : raw) {
            if (s.body().isBlank()) {
                continue;
            }
            if (pending == null) {
                pending = s;
            } else if (pending.body().length() < MIN_SECTION) {
                pending = pending.merge(s);
            } else {
                out.add(pending);
                pending = s;
            }
        }
        if (pending != null && !pending.body().isBlank()) {
            out.add(pending);
        }
        return out;
    }

    /** 长正文再切：交给段落切块器；它切不动就退回定长滑窗。 */
    private List<String> splitLongBody(String body) {
        try {
            List<TextSegment> segments = bodySplitter.split(Document.from(body));
            return segments.stream().map(TextSegment::text).toList();
        } catch (RuntimeException e) {
            return slide(body);
        }
    }

    private static List<String> slide(String s) {
        List<String> out = new ArrayList<>();
        int step = SOFT_MAX - OVERLAP;
        for (int i = 0; i < s.length(); i += step) {
            out.add(s.substring(i, Math.min(s.length(), i + SOFT_MAX)));
        }
        return out;
    }

    /** 一节：标题 + 正文。 */
    record Section(String heading, String body) {
        Section merge(Section next) {
            String merged = body.trim() + "\n\n" + (next.heading() == null ? "" : next.heading() + "\n\n") + next.body();
            return new Section(heading, merged);
        }
    }
}
