package com.example.domain.zsxq.image;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.ImageContent;
import dev.langchain4j.data.message.TextContent;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.chat.response.ChatResponseMetadata;
import dev.langchain4j.model.output.FinishReason;
import dev.langchain4j.service.AiServices;

/**
 * S4 图片概括的可单测逻辑。
 *
 * <p>两块：
 * <ol>
 *   <li>向量字面量拼装 —— {@link ZsxqImageService#toVectorLiteral}（格式错了 PG 直接拒）；</li>
 *   <li>概括器的 AiService 接口形态 —— 用 fake 模型验证「图片真的被送进消息」，
 *       这是最容易踩坑的一处（裸 {@code ImageContent} 参数在<b>运行期</b>才报配置错）。</li>
 * </ol>
 *
 * <p>非正文图的判定测试不在这里 —— 判定逻辑已迁到 S1 的 {@code ContentImages}，
 * 覆盖见 {@code ContentImagesTest}。
 */
class ZsxqImageServiceTest {

    // ---------- 向量字面量 ----------

    @Test
    void vectorLiteral_usesBracketAndCommaFormat() {
        Embedding e = Embedding.from(new float[]{1.5f, -2.0f, 0.0f});

        assertEquals("[1.5,-2.0,0.0]", ZsxqImageService.toVectorLiteral(e));
    }

    // ---------- 概括器接口形态 ----------

    /**
     * 关键回归：图片参数必须真的进到消息的 contents 里。
     *
     * <p>踩过的坑：写成 {@code String describe(ImageContent img)}（裸参数）会在<b>调用时</b>
     * 抛 {@code IllegalConfigurationException}（框架要求每个参数带注解），编译期完全看不出来。
     * 这个用例把「图片参数挂 {@code @UserMessage}」这个正确形态钉住。
     */
    @Test
    void imageParameter_reachesModelAsContent() {
        CapturingChatModel fake = new CapturingChatModel("一张分层架构图。");
        ZsxqImageDescriberAi ai = AiServices.builder(ZsxqImageDescriberAi.class)
                .chatModel(fake)
                .build();

        String out = ai.describe(ImageContent.from("https://article-images.zsxq.com/abc"));

        assertEquals("一张分层架构图。", out);
        List<ChatMessage> sent = fake.lastMessages;
        assertEquals(2, sent.size(), "应当是 system + user 两条消息");
        UserMessage user = (UserMessage) sent.get(1);
        assertTrue(user.contents().stream().anyMatch(c -> c instanceof ImageContent),
                "图片必须作为 ImageContent 出现在 user 消息里，否则模型根本看不到图");
        assertTrue(user.contents().stream().anyMatch(c -> c instanceof TextContent),
                "指令文本也要在，否则消息里只有图没有要求");
    }

    // ---------- 登记策略 ----------

    /**
     * 登记用的入参是「文档 + 图片映射」，判定逻辑纯函数化后可以直接断言，
     * 不必启动数据库。
     */
    @Test
    void onlyKeepImagesDocs_areCandidates() {
        assertTrue(shouldRegister(doc(true, "123"), Map.of("123", List.of("u1"))));
        assertFalse(shouldRegister(doc(false, "123"), Map.of("123", List.of("u1"))),
                "keepImages=false 的文档（星友图）一张都不登记");
        assertFalse(shouldRegister(doc(true, null), Map.of("123", List.of("u1"))),
                "没有血缘键的文档图登记不了（post_id 外键指不过来）");
        assertFalse(shouldRegister(doc(true, "999"), Map.of("123", List.of("u1"))),
                "该帖没采到图就不登记");
        assertFalse(shouldRegister(doc(true, "123"), Map.of("123", List.of())),
                "图片列表为空同样跳过");
    }

    /** 复刻 {@code registerImages} 里的准入判定，保持与实现同步。 */
    private static boolean shouldRegister(com.example.domain.zsxq.model.ZsxqCleanedDoc d,
                                          Map<String, List<String>> imagesByPostId) {
        if (!d.keepImages || d.rawPostId == null || d.rawPostId.isBlank()) {
            return false;
        }
        List<String> urls = imagesByPostId.get(d.rawPostId);
        return urls != null && !urls.isEmpty();
    }

    private static com.example.domain.zsxq.model.ZsxqCleanedDoc doc(boolean keepImages, String rawPostId) {
        com.example.domain.zsxq.model.ZsxqCleanedDoc d = new com.example.domain.zsxq.model.ZsxqCleanedDoc();
        d.docId = "zsxq-" + rawPostId;
        d.rawPostId = rawPostId;
        d.keepImages = keepImages;
        return d;
    }

    /** 记录最后一次请求消息的假模型，避免单测真的去打 DeepSeek。 */
    private static final class CapturingChatModel implements ChatModel {
        private final String reply;
        List<ChatMessage> lastMessages = List.of();

        CapturingChatModel(String reply) {
            this.reply = reply;
        }

        @Override
        public ChatResponse chat(ChatRequest request) {
            this.lastMessages = request.messages();
            return ChatResponse.builder()
                    .aiMessage(AiMessage.from(reply))
                    .metadata(ChatResponseMetadata.builder().finishReason(FinishReason.STOP).build())
                    .build();
        }
    }
}
