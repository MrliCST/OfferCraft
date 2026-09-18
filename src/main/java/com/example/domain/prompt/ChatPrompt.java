package com.example.domain.prompt;

import dev.langchain4j.model.input.structured.StructuredPrompt;
import lombok.RequiredArgsConstructor;

/**
 * 提示词 POJO：提示词正文写在注解里，{{question}} 由同名字段填。
 * 直接当参数传给 AI 服务即可（{@code AIChatService.chat(new ChatPrompt(...))}），
 * 框架认这个 {@code @StructuredPrompt} 注解并自动渲染，不用手写
 * {@code StructuredPromptProcessor.toPrompt(...)}。
 *
 * <p>渲染过程：注解的 value 用 "\n" 拼成模板 → Jackson 按字段反射
 * （PropertyAccessor.FIELD + Visibility.ANY）把字段抽成变量表 → 拿变量表套模板。
 * 所以字段是 private final 也不需要 getter，构造器交给 Lombok 按 final 字段生成。
 *
 * <p>人设走新手向：说话通俗、多用比喻。配合 {@code deepseekChatModel} 那个偏高的温度
 * （1.3），语气会活泼些，所以最后两条专门用来压高温度的副作用——别编、别跑题。
 */
@StructuredPrompt({
    "你是一个耐心的编程入门老师，专门给刚学编程的新手讲东西，主要讲 Java、Spring Boot、Maven、数据库和算法。",
    "用户可能连术语都没听过：能不用黑话就不用，非用不可时先用一句话把它解释清楚，多打比方、多举例子。",
    "回答时先把结论摆出来，再一步步展开；代码给完整的、能直接跑通的那种，关键几行加上注释。",
    "踩坑的地方直接点破，别绕弯子，也别写客套话。",
    "不确定就说不确定，不要编；也别跑题，只回答用户问的那件事。",
    "用户让你截图或保存某个网页时，用工具去截，成功后把工具返回的完整路径原样告诉他；路径只能来自工具，不许自己编。",
    "",
    "{{question}}"
})
@RequiredArgsConstructor
public class ChatPrompt {

    @SuppressWarnings("unused")
    private final String question;
}
