package com.example.domain.zsxq;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;

import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.service.AiServices;

import com.example.domain.zsxq.classify.HeuristicTopicGuard;
import com.example.domain.zsxq.classify.LangchainTopicGuard;
import com.example.domain.zsxq.classify.TopicGuardAi;
import com.example.domain.zsxq.model.Classification;
import com.example.domain.zsxq.model.CrawledPost;
import com.example.domain.zsxq.model.CrawledReply;
import com.example.domain.zsxq.model.PostType;

/**
 * S2 分类闸单测（离线，不加载 Spring / 不连 LLM）：
 *  - HeuristicTopicGuard：合成帖子覆盖六类。
 *  - LangchainTopicGuard：用 fake ChatModel 返回固定 JSON，验证 AiService 解析 + 字段抽取。
 */
class TopicGuardTest {

    private static CrawledPost star(String content) {
        CrawledPost p = new CrawledPost();
        p.author = "马丁";
        p.authorRole = "星主";
        p.publishedAt = "2026-09-17 16:25";
        p.content = content;
        return p;
    }

    private static CrawledPost member(String content, CrawledReply... replies) {
        CrawledPost p = new CrawledPost();
        p.author = "某星友";
        p.authorRole = "星友";
        p.publishedAt = "2026-07-25 10:29";
        p.content = content;
        p.replies = List.of(replies);
        return p;
    }

    private static CrawledReply martin(String text) {
        CrawledReply r = new CrawledReply();
        r.commenter = "马丁";
        r.text = text;
        return r;
    }

    @Test
    void heuristic_techArticle() {
        Classification c = new HeuristicTopicGuard().classify(
                star("下面是 RAG 落地：\n```java\npublic static void main(String[] a) {}\n```\n详解检索链路。"));
        assertEquals(PostType.TECH_ARTICLE, c.postType);
        assertFalse(c.drop);
        assertEquals(0.9, c.authorityScore, 1e-9);
        assertTrue(c.starMasterVerified);
    }

    @Test
    void heuristic_interviewQa() {
        Classification c = new HeuristicTopicGuard().classify(member(
                "企业级 RAG 项目怎么做？意图识别树怎么构建？",
                martin("第一个：这是面向企业级的RAG问答平台，模型会弄清楚用户真正想问什么，再判断走知识库检索还是MCP工具调用，整条链路要稳定可观测。")));
        assertEquals(PostType.INTERVIEW_QA, c.postType);
        assertFalse(c.drop);
        assertTrue(c.starMasterAnswer.length() > 60);
        assertTrue(c.starMasterVerified);
    }

    @Test
    void heuristic_architectureNote() {
        Classification c = new HeuristicTopicGuard().classify(
                star("Ragent 2.0 基于 AgentScope 重构，整体架构更清晰，选型上更偏编排。"));
        assertEquals(PostType.ARCHITECTURE_NOTE, c.postType);
        assertFalse(c.architectureQuote.isEmpty());
    }

    @Test
    void heuristic_resourceShare() {
        Classification c = new HeuristicTopicGuard().classify(
                member("发现宝藏开源项目 https://github.com/Lum1104/Understand-Anything，配合文档学习效率翻倍"));
        assertEquals(PostType.RESOURCE_SHARE, c.postType);
    }

    @Test
    void heuristic_memberPost_lowAuthorityKept() {
        Classification c = new HeuristicTopicGuard().classify(
                member("今天把项目跑起来了，记录一下自己的心得，后面方便复盘。"));
        assertEquals(PostType.MEMBER_POST, c.postType);
        assertFalse(c.drop);
        assertEquals(0.1, c.authorityScore, 1e-9);
    }

    @Test
    void heuristic_offTopic_dropped() {
        Classification c = new HeuristicTopicGuard().classify(
                member("避雷！我实习被坑了，大家擦亮眼睛别重蹈覆辙。"));
        assertEquals(PostType.OFF_TOPIC, c.postType);
        assertTrue(c.drop);
    }

    // ---- LLM 闸：用 fake 模型验证 AiService 解析 ----

    static class FakeModel implements ChatModel {
        private final String reply;
        FakeModel(String reply) { this.reply = reply; }
        @Override
        public ChatResponse doChat(ChatRequest request) {
            return ChatResponse.builder().aiMessage(AiMessage.from(reply)).build();
        }
    }

    private LangchainTopicGuard guardWith(String json) {
        TopicGuardAi ai = AiServices.create(TopicGuardAi.class, new FakeModel(json));
        return new LangchainTopicGuard(ai, new HeuristicTopicGuard());
    }

    @Test
    void llm_parse_techArticle() {
        Classification c = guardWith(
                "{\"post_type\":\"tech_article\",\"reason\":\"结构化长文\",\"star_master_answer\":\"\",\"architecture_quote\":\"\"}")
                .classify(star("随便什么正文"));
        assertEquals(PostType.TECH_ARTICLE, c.postType);
        assertFalse(c.drop);
    }

    @Test
    void llm_parse_interviewQa_extractsAnswer() {
        Classification c = guardWith(
                "{\"post_type\":\"interview_qa\",\"reason\":\"星友问+星主答\",\"star_master_answer\":\"马丁的权威回答原文\",\"architecture_quote\":\"\"}")
                .classify(member("一个问题", martin("一段较长的回答文本用于演示抽取结果。")));
        assertEquals(PostType.INTERVIEW_QA, c.postType);
        assertEquals("马丁的权威回答原文", c.starMasterAnswer);
    }

    @Test
    void llm_parse_codeFence_then_fallbackOnInvalid() {
        // 带 ```json 包裹也能解析
        Classification ok = guardWith(
                "```json\n{\"post_type\":\"architecture_note\",\"reason\":\"含金句\",\"star_master_answer\":\"\",\"architecture_quote\":\"基于 AgentScope 重构\"}\n```")
                .classify(star("随便"));
        assertEquals(PostType.ARCHITECTURE_NOTE, ok.postType);
        assertEquals("基于 AgentScope 重构", ok.architectureQuote);

        // 越界 post_type → 回退启发式（这里星友无干货 → member_post，而非 off_topic 之外的异常）
        Classification fb = guardWith("{\"post_type\":\"unknown_type\",\"reason\":\"x\"}")
                .classify(member("今天记录一下项目心得。"));
        assertEquals(PostType.MEMBER_POST, fb.postType);
    }
}
