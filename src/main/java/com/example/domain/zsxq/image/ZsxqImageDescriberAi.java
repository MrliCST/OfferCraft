package com.example.domain.zsxq.image;

import dev.langchain4j.data.message.ImageContent;
import dev.langchain4j.service.SystemMessage;
import dev.langchain4j.service.UserMessage;
import dev.langchain4j.service.spring.AiService;
import dev.langchain4j.service.spring.AiServiceWiringMode;

/**
 * S4 图片概括的 LLM 接口（langchain4j AiService）。
 * 由 Spring 按 {@code deepseekVisionModel}（deepseek-flash 的多模态形态）生成实现并注册为 bean。
 *
 * <p><b>参数写法是这里唯一有讲究的地方</b>，踩过坑：langchain4j 1.20.0 的
 * {@code AiServiceValidation.validateParameters} 要求每个参数都带注解，或者类型是框架认的
 * {@code InvocationParameters}/{@code ChatRequestParameters}。裸的 {@code ImageContent} 参数
 * <b>既不是被认可的类型、又没有注解</b>，会在<b>调用时</b>抛
 * {@code IllegalConfigurationException: The parameter 'arg1' ... must be annotated with either
 * UserMessage, V, MemoryId, or UserName}——是运行期校验，编译期完全看不出来。
 *
 * <p>正确写法是给图片参数挂 {@code @UserMessage}：框架的 {@code DefaultAiServices.addContentsToUserMessage}
 * 专门处理「{@code @UserMessage} 标注的 {@code Content} 型参数」，会把它收进消息的 contents 列表
 * （见 {@code DefaultAiServices} 第 1687–1691 行）。指令文本则写在<b>方法级</b> {@code @UserMessage} 上，
 * 因为它不带参数占位符，框架会走 {@code hasContentArgument} 分支，把方法级那段文本原样当 user 消息。
 *
 * <p>为什么不写 {@code describe(@UserMessage String prompt, ImageContent image)} 这种「指令 + 图片」
 * 双参数形态：第二个参数又没有注解，照样过不了校验。指令要带上下文就用 {@code @V} 占位符
 * （当前不需要，所以固定在方法级注解里）。
 *
 * <p>返回 {@code String} 而不是自定义 POJO：图片描述是自由文本，没有结构可解析；
 * 不依赖框架的 POJO 自动解析，跨模型更稳，也方便单测里塞 fake 模型。
 */
@AiService(
    wiringMode = AiServiceWiringMode.EXPLICIT,
    chatModel = "deepseekVisionModel",
    streamingChatModel = "deepseekStreamModel"
)
public interface ZsxqImageDescriberAi {

    @SystemMessage("""
        你是知识星球技术长文里的图片概括器。这些图来自程序员的技术分享，主要是：
        架构图、流程图、UML/时序图、数据表截图、代码截图、IDE 报错截图、运行结果截图、终端输出。

        你的输出会进入向量库，供后续按自然语言检索图片。所以描述要让人"不打开图也能知道
        这张图画了什么、能不能回答我的问题"。

        ## 输出要求
        - 用中文，一到三句话，总长控制在 200 字以内。
        - 直接输出描述本身。<b>不要</b>分析过程、不要复述本提示、不要用 Markdown 包裹、不要加"图片显示了"之类的套话。
        - 不要臆测图中不存在的信息。看不清的部分就写"某处文字较小/不清晰"，不要编。

        ## 内容要涵盖（有则写，无则略）
        1. <b>图类型与主题</b>：这是什么图、在讲什么。例：「一张 Spring Boot 请求处理的流程图」。
        2. <b>关键结构</b>：主要方块 / 分层 / 节点，以及它们之间的箭头或调用关系。
           例：「自左向右三个框：Controller → Service → Mapper，最后一个框连到数据库圆柱图标」。
        3. <b>图内文字 OCR</b>：尽量把图中<b>可辨认的关键文字</b>提取出来——框内标签、类名/方法名、
           表头与字段名、代码片段中的关键行、报错信息的核心句子。这是检索的主要抓手。
           例：「含字段 user_id、create_time、status」。文字太多时挑关键的，不要逐字抄整页。
        4. <b>若为报错/结果截图</b>：说清报的是什么错、哪个类/方法、结论是什么。
           例：「控制台红字报 NullPointerException，栈顶指向 UserServiceImpl.queryById」。

        ## 示例
        输入：一张三层结构的架构图，上层写「Controller」、中层「Service」、下层「Mapper」，
        箭头自上而下，右侧有个「MySQL」圆柱。
        输出：一张 Spring Boot 分层架构图，自上而下三个框 Controller → Service → Mapper，
        箭头表示逐层调用，最下方 Mapper 连向 MySQL 数据库。

        输入：一张 IDEA 控制台的红色报错截图，正文有 "Caused by: java.lang.NullPointerException"
        和 "at com.example.service.UserServiceImpl.queryById(UserServiceImpl.java:42)"。
        输出：IDEA 控制台报错截图，异常为 java.lang.NullPointerException，栈顶定位到
        UserServiceImpl.queryById 第 42 行。
        """)
    @UserMessage("请概括这张图片。")
    String describe(@UserMessage ImageContent image);
}
