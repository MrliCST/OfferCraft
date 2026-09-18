参考一：
来源：精华栏目--帖子
一定要努力努力努力（这个是成员的名称)

2026-07-25 10:29（发帖时间)
（下面是帖子内容：)

请你简短介绍一下，你的这个企业级的RAG项目。

这个企业级主要体现在什么地方？

意图识别你这里是怎么做的，树是怎么构建的？

澄清的触发条件是什么？为什么不直接选择分数最高的意图？

MCP工具调用的完整链路是什么？请你叙述一下

子问题中存在依赖关系怎么办？

这是最近的一次面试，各位可以看看能不能回答上来。

查看详情（下面是讨论)

bymas、天天天、Iridescent*、Stephen、淘气、葳蕤、Go and see、习嗯哼、微信用户、夕颜 等35人觉得很赞

一定要努力努力努力：我复盘一下我面试时候的回答，感觉还有很多问题。还请大家纠正讨论一下，互相提高。

第一个：这是一个面向企业级的RAG问答平台，就是用户提出了一个问题，大模型会弄清楚用户真正想要问什么，然后判断走知识库检索、MCP工具调用还是纯系统回答。如果用户的问题存在歧义或者不明确，会返回给用户澄清，明确了之后。再从相关知识库中召回，然后合并，去重，排序，最后交给大模型回答。然后天气，业务查询等一些外部信息，通过MCP调用工具去查。然后系统还支持模型切换、异常降级还有评测，来保证系统运行的稳定。

第二个：我这个企业级主要体现在他不是单纯的调用API，也没有使用具体的框架。像企业级或者真实的生产端他肯定是需要稳定，发现问题好排查，系统好坏可以评估观测，如果是调用API的话，他就相当于一个黑盒子，不好排查，然后框架升级迭代也比较快，会出现整个代码不适配的情况，就需要整体翻新，对于企业级来说成本就比较大。这是企业级主要体现的地方。

第三个：是这样的面试官，我们是先把企业中的具体业务意图整理成一颗层级数，包含知识库检索、MCP工具调用和纯系统问答等叶子意图，中间层是按照业务领域来组织的，整个层级是分为三层的，具体是domin、catagory、topic。在具体识别的时候，大模型不是具体逐层判断的，他是先把所有叶子节点全部给大模型，大模型根据用户问题去打分，然后最符合用户问题的几个候选意图。然后面试管追问了一下：这个意图树是你们提前准备好的吗？我说是，是我们提前写到数据库中的，也引入了redis去减轻数据库的压力和提高加载的速度。

第四个问题：触发的条件，嗯.....，我举一个例子就是假设用户问了一个问题，模型识别出来了退款政策，但是这时候模型不知道是家电的退款政策，还是手机的退款政策，这个时候就会触发澄清。为什么不直接选择分数最高的，是这样的，如果每次都选最高的话容易出问题，比如手机退款政策是0.9，家电是0.89，那么这样很容易出错，因为太模糊了。只有当其中一个意图的分数明显高于第二个时，这样模型对不同意图的区分度比较大，不容易出错。面试官追问了一下，我有点想不起来了。

第五个问题，系统先判断需要调用工具，然后确定调用那个工具，然后从工具的schma中提取所需要的参数，然后大模型根据用户的自然语言和工具的参数schma中提取参数，然后调用工具执行，执行完将结果丢给大模型。如果缺少参数就追问，执行失败就降级处理，避免整个链路中断。

第六个问题，这个系统目前的范式主要还是Workflow的，他还不太支持这种情况，如果子问题中存在依赖关系的话我会考虑使用ReAct进行一个多轮交互，就是大模型先拆任务，然后分析完成这个任务需要的条件，对就是一步一步来。

面试完就来记录了，感觉面试时候表达能力还是有很大问题的。

2026-07-25 11:19

8

马丁 回复 一定要努力努力努力：核心技术表达的还是挺好的，起码能够表达清楚功能相关语义。面试中也不存在绝对的标准，更多是先确保自己理解没问题，再加上通过自己话术表达出来，无所谓好坏。

给出几个建议：

1、项目介绍时，可以针对自己内容测评输出一些，比如说下自己的测评思路，测评了多少数据集，涵盖了多少范围文档等，这些都可以和面试官聊聊。

2、回答深度方面，虽然问题能够做到答出来核心内容，但是不具备深度和扩展性。举个例子，就是模型切换、异常降级，其实内部还是挺复杂的，虽然没必要介绍时全部讲细节，但是再说深一层还是可以，比如拆分了 chat、向量、重排序等。再比如，讲 MCP 时，可以延伸讲讲用了官方 SDK 集成的，和 SpringAI、Langchain4j 没有本质区别，他们只是在官方 API 上包了一层。这么一讲，面试官肯定会觉得你的学习深度不局限于表面。

3、最后就是子问题依赖，其实现在是 OK 的，因为不同子问题会基于同一个大模型最终回答，如果说问了类似于简单依赖问题，模型就能评估出并对应回答。

面试的过程就是不断挖掘自己的问题，不怕觉得自己有问题，就怕有问题而不自知。加油。

2026-07-26 11:14

1

一定要努力努力努力：1、数据库中是怎么表示父子关系的？

2、Redis缓存没有命中后如何加载意图树？

3、澄清后用户补充的信息如何继续处理？

4、多个子问题、通道和知识库如何并行？

第一个：我们这个项目是没有把整颗意图树作为一个json存进数据库的，而是把每个意图节点作为一条记录保存在数据库中的。每个节点有自己的唯一标识，还保存了父节点的标识；根节点没有父节点。我举一个例子，比如就是退货政策的父节点是售后服务，售后服务的父节点是电商业务，这样就能表示一个三层关系。系统加载的时候，是一次性查出所有节点，再根据父节点标识在内存中把他们组装成树。这样设计比较方便的去管理节点，比如新增、删除节点，不需要每次的去更新整棵树。

第二个：嗯是这样的面试官，意图识别开始的时候，会根据key去redis中加载意图树的json，如果存在的话，就将json反序列化成树，如果加载失败，就会去查询数据库。查询数据库是分为两个阶段的，首先就是挑选出开启的，没有被删除的所有记录，然后遍历这些节点，根据父节点标识去标识父节点，没有父节点就是根节点，去建立树，建立好了再反序列化成json存入redis里面，供下次使用。

第三个：假设项目触发澄清了，那么本次请求会提前结束，并且把澄清消息保存下来，然后用户补充完信息时会发送一次新的请求，然后在经过一轮新的步骤比如加载历史记忆啊，意图改写啊等后续的流程。

第四个：我们这个项目的并行不是放在同一个线程池中的，而是放在多个线程池中的，比如就是用户的原问题拆分成一个一个子问题，使用一个线程池中的多个线程并行执行，意图识别的时候同样是使用另一个线程池中的多个线程同时执行

2026-07-27 20:27

2

微信用户：写的很好啊佬 能看看佬的简历是怎么写的吗 谢谢佬了愉快

参考二：

（来源：精华贴)

水静犹明（发帖成员)

2026-05-29 11:48（发帖时间)
（下面这个是这个发帖人推流自己的项目，这个怎么处理？技术分享还是无关贴子，一般没有，不过也可以试试单独一篇文章把所有类似的带链接的连上下文抽出来。)

发现一个宝藏开源项目，[GitHub - Lum1104/Understand-Anything: Graphs that ...](https://github.com/Lum1104/Understand-Anything "GitHub%20-%20Lum1104%2FUnderstand-Anything%3A%20Graphs%20that%20...")，配合文档学习马哥的项目效率至少提升两倍![](https://wx.zsxq.com/assets_dweb/images/emoji/%E5%91%B2%E7%89%99.png "呲牙")

查看详情（评论：)

随风去流浪、Ethan、SUM、微信用户、Lz、星恒、AB、白开水、framework、木薪韭

 **等23人觉得很赞**

**马丁**：**加精啦，期待分享最佳实践～**

2026-05-30 19:46

**水静犹明** 回复 **马丁**：**已更新**![](https://wx.zsxq.com/assets_dweb/images/emoji/%E5%91%B2%E7%89%99.png "呲牙")

2026-06-01 13:39

三、发帖人分享自己的被坑经历：（避雷贴子，一般也没用，个人的经历不同，也可以抽取出一篇避雷文章，如果成本高就算了)
游乐

2026-06-06 18:35

马哥，我最近在校招提前实习中踩了个大坑，现在只能重新找工作。特意把这段经历发出来，希望大家擦亮眼睛，千万别重蹈覆辙。

事情是这样的：周五HR突然找我谈话，以“积极性不够、没有主动加班”为由将我辞退。我当时很诧异，明确表示自己分内的工作已经全部完成。但对方却辩称，仅仅完成本职工作是不够的，还必须主动去帮别人干活、了解与自己无关的其他公司业务。当我提出索要赔偿金时，他们起初态度强硬，坚决不肯给。

更恶心的是他们的反复无常。下午他们又矢口否认上午的辞退决定，让我继续回去工作。经过一番艰难的扯皮，我才勉强要到了3000元的赔偿。

后来我才恍然大悟，这根本不是什么“积极性”的问题！真实原因是公司招到了更有性价比的新人。因为组里就我一个实习生，开掉我不需要支付高额赔偿，所以他们随便找了个理由就把我踢出局了。我在实习时既要负责开发又要负责测试，甚至还需要分析需求，工作量和正式员工一模一样，结果用完即弃。

真心提醒大家，找工作时一定要擦亮眼睛，遇到这种毫无底线、把人当耗材的垃圾公司，一定要尽早远离！

收起

查看详情

好好写代码、烧烤、答案说明所有、Aaron、103、马丁、澐、Mobius、。。。、DKF 等11人觉得很赞

。：所以是不是不建议提前实习xd

2026-06-07 09:59

游乐 回复 。：我有三方都被他们随随便便毁约，甚至不想赔偿，只是这个公司单纯烂，能去还是可以去的，一个月学到了许多东西

2026-06-07 11:29

马丁：Mark

2026-06-07 11:30

游乐：补充说明一下，加班的补贴是20块餐补，除此之外无任何报酬。

2026-06-07 16:45

呵：哪家公司啊 xd撇嘴，这不得曝光
四、上岸分享：（这个一般没用，但是也可能帖子会分享自己怎么上岸的)
桂圆有喜了

2026-04-16 22:42

上岸经验分享

		上岸了北京某大厂（应该算大厂吧）的软件开发，分享一些面经，希望对大家有所帮助！

		我是非科班，通信转码，背景是双9硕，之前没有实习经历，所以简历上就是项目经历占大头，ai项目用的是一个别地方来的ai项目，因为当时马哥的ai项目还没完工，所以不是马哥这里的，我就不多介绍了；然后传统后端项目用的是马哥这里 ...

上岸经验分享

🌈面试相关

🤩晒个offer

💫优质面经

最后编辑：2026-04-16 22:42

等27人觉得很赞

红烧小白兔：大佬社会社会

2026-04-16 23:30

桂圆有喜了 回复 红烧小白兔：非佬非佬捂脸捂脸跳跳跳跳

2026-04-17 08:39

范特西 回复 桂圆有喜了：26届还是27届？

2026-04-17 14:16

桂圆有喜了 回复 范特西：27届的

2026-04-17 20:12

范特西 回复 桂圆有喜了：27届直接拿的offer么？不是实习offer？

2026-04-17 20:13

桂圆有喜了 回复 范特西：是实习offer，我的表述有误，抱歉哈

2026-04-17 20:14

1

范特西 回复 桂圆有喜了：懂了，谢谢回答呲牙

2026-04-17 20:14

桂圆有喜了 回复 范特西：不客气不客气

2026-04-17 20:15

Felixxx：求推 ai 项目

五、作者的吐槽帖子：（内容大多也是无用的)
马丁（这个是作者，就是唯一的星主)

2026-05-11 23:21

嗨，兄弟们，好久没冒泡了，跟大家唠唠最近的情况。

五一在家其实没怎么玩，一直在琢磨星球后面 AI 这条路线该怎么走，怎么能真正帮大家成长起来，目前思路已经比较清晰了。期间也把 Chat 对话章节剩下的近 10 篇文档写完了，限流那块的代码也重构了一版。

本来一切都挺顺，结果从家回来后，电脑没摔没碰也没进水，主板就这么烧了……官方旗舰店说得换主板，数据全没。我不死心，又跑了一家苹果店（非自营），他们说可以单换主板上的电芯，数据能保住，反复确认了好几遍，大概还要 3 天能修好。这也是我这段时间没冒泡的原因。

这几天临时换了台 Windows 凑合用，那感觉真是跟断手断脚一样，贼难受。不过马哥也没闲着，开始整 Ragent 的测评集了——把原始数据梳理出来，结合 AI 和人工生成问题，搞了一套全局评测。进度还挺理想。

这么搞下来，大家以后写到简历上的项目，会无限接近生产环境。比如为什么选 DeepSeek 而不是 Qwen，为什么用这个向量化模型而不是另一个，这些问题都能通过这套测评集给出有依据的答案。我的场景是选了个小米（比特）商城的电商智能体思路，到时候会把全流程给大家讲透，大家换个思路，花点 Token 就能无缝迁移。

最终的效果就是：项目能跑出准确率指标，不同架构下检索效果到底怎么提升的，一目了然。

顺利的话，这周就能给大家放个大的。当然要做好最坏打算——万一数据真全丢了，那就只能从头再写一遍。祝我好运吧！

#📌星球通知

📌星球通知

等48人觉得很赞

马丁：置顶电脑有惊无险修复了，开始更新！

2026-05-14 22:05

2

李诗雅：加油马哥，数据会回来的

2026-05-11 23:23

马丁 回复 李诗雅：拥抱拥抱

2026-05-11 23:27

李诗雅：我说五一期间怎么马哥没更新 也挺久了

2026-05-11 23:23

XXXKML：马哥辛苦了！希望数据能保留下来~

2026-05-11 23:24

何必归星辰：好期待，希望数据没事

2026-05-11 23:25

侯：期待马哥更新 希望数据能回来

2026-05-11 23:26

小龙猫：马哥辛苦了，数据会保住的玫瑰玫瑰玫瑰

2026-05-11 23:32

一加一等于三：马哥这套机制与ragas对比有什么不同？

2026-05-11 23:42

马丁 回复 一加一等于三：包括不限于，其中RAG核心内容会基于 RAGAS 去做，有些 RAGAS 做不了的，我会单独实现。

马丁

2026-05-11 23:21

嗨，兄弟们，好久没冒泡了，跟大家唠唠最近的情况。

五一在家其实没怎么玩，一直在琢磨星球后面 AI 这条路线该怎么走，怎么能真正帮大家成长起来，目前思路已经比较清晰了。期间也把 Chat 对话章节剩下的近 10 篇文档写完了，限流那块的代码也重构了一版。

本来一切都挺顺，结果从家回来后，电脑没摔没碰也没进水，主板就这么烧了……官方旗舰店说得换主板，数据全没。我不死心，又跑了一家苹果店（非自营），他们说可以单换主板上的电芯，数据能保住，反复确认了好几遍，大概还要 3 天能修好。这也是我这段时间没冒泡的原因。

这几天临时换了台 Windows 凑合用，那感觉真是跟断手断脚一样，贼难受。不过马哥也没闲着，开始整 Ragent 的测评集了——把原始数据梳理出来，结合 AI 和人工生成问题，搞了一套全局评测。进度还挺理想。

这么搞下来，大家以后写到简历上的项目，会无限接近生产环境。比如为什么选 DeepSeek 而不是 Qwen，为什么用这个向量化模型而不是另一个，这些问题都能通过这套测评集给出有依据的答案。我的场景是选了个小米（比特）商城的电商智能体思路，到时候会把全流程给大家讲透，大家换个思路，花点 Token 就能无缝迁移。

最终的效果就是：项目能跑出准确率指标，不同架构下检索效果到底怎么提升的，一目了然。

顺利的话，这周就能给大家放个大的。当然要做好最坏打算——万一数据真全丢了，那就只能从头再写一遍。祝我好运吧！

[#📌星球通知 ](https://wx.zsxq.com/tags/%F0%9F%93%8C%E6%98%9F%E7%90%83%E9%80%9A%E7%9F%A5/51121258182254)

📌星球通知

![](https://images.zsxq.com/FsQxKfH8zuayte1EBg2Od8CiR3y9?imageMogr2/auto-orient/thumbnail/150x/format/jpg/blur/1x0/quality/75/ignore-error/1&e=1793462399&token=q6iZ0sQtf9U7s1qz0r4yMawNq3-u2w6lbnai6y2J:SD-9OCzh0amE6oWioowZs0PaePs=)![](https://images.zsxq.com/FrZmual_QtcVQlQ16Vz7STMS7a-O?imageMogr2/auto-orient/thumbnail/150x/format/jpg/blur/1x0/quality/75/ignore-error/1&e=1793462399&token=q6iZ0sQtf9U7s1qz0r4yMawNq3-u2w6lbnai6y2J:btnqgpEGWEOAsqqTxi5sWfNf2XU=)![](https://images.zsxq.com/Fk6Z6gZI50jr_gq2qEDGhxxCSCY_?imageMogr2/auto-orient/thumbnail/150x/format/jpg/blur/1x0/quality/75/ignore-error/1&e=1793462399&token=q6iZ0sQtf9U7s1qz0r4yMawNq3-u2w6lbnai6y2J:sdDp76M8vP5uqrJUj6nmHBDu-Io=)![](https://images.zsxq.com/FlBPtNuJ5-IIp9g16I3GIiHadGOk?imageMogr2/auto-orient/thumbnail/150x/format/jpg/blur/1x0/quality/75/ignore-error/1&e=1793462399&token=q6iZ0sQtf9U7s1qz0r4yMawNq3-u2w6lbnai6y2J:DPeFuJHapyabBAP6KjfmGYQplaQ=)![](https://images.zsxq.com/FkSczA-ov3_VcqI5cDdWQilfC1DM?imageMogr2/auto-orient/thumbnail/150x/format/jpg/blur/1x0/quality/75/ignore-error/1&e=1793462399&token=q6iZ0sQtf9U7s1qz0r4yMawNq3-u2w6lbnai6y2J:rSPyBLt-obQlBVzU3vlxxOfvqDw=)![](https://images.zsxq.com/FvgCD57VjftYH7h3-b1z6Mw80y-z?imageMogr2/auto-orient/thumbnail/150x/format/jpg/blur/1x0/quality/75/ignore-error/1&e=1793462399&token=q6iZ0sQtf9U7s1qz0r4yMawNq3-u2w6lbnai6y2J:eW5aRibyF6ktpdvqfchzAmsYBBc=)![](https://images.zsxq.com/FgK3DFHi9mdEfuupjrrzoRIt0S9R?imageMogr2/auto-orient/thumbnail/150x/format/jpg/blur/1x0/quality/75/ignore-error/1&e=1793462399&token=q6iZ0sQtf9U7s1qz0r4yMawNq3-u2w6lbnai6y2J:v06rv3OiIeLAD6R0P_-uh4Ms9U4=)![](https://images.zsxq.com/Fk1K4UdoTs0rC47Apmr50HrK0JC5?imageMogr2/auto-orient/thumbnail/150x/format/jpg/blur/1x0/quality/75/ignore-error/1&e=1793462399&token=q6iZ0sQtf9U7s1qz0r4yMawNq3-u2w6lbnai6y2J:H49xWT3AMMhUP6hmPCsxlLi9xCI=)![](https://images.zsxq.com/FnS24wb0-93aRMB5VeD7II6vEb4a?imageMogr2/auto-orient/thumbnail/150x/format/jpg/blur/1x0/quality/75/ignore-error/1&e=1793462399&token=q6iZ0sQtf9U7s1qz0r4yMawNq3-u2w6lbnai6y2J:qqyTM0TwH18JDIP95RbbkOu5wGg=)![](https://images.zsxq.com/FlQI-uedMlWBIFehkAqfJNC1CNFz?imageMogr2/auto-orient/thumbnail/150x/format/jpg/blur/1x0/quality/75/ignore-error/1&e=1793462399&token=q6iZ0sQtf9U7s1qz0r4yMawNq3-u2w6lbnai6y2J:UZKMaKqtXiIKyhclAh_mwoJmbBY=)等48人觉得很赞

![](https://images.zsxq.com/Fr8uwu8AMapccyWqKi_UbroM7Q8K?imageMogr2/auto-orient/thumbnail/150x/format/jpg/blur/1x0/quality/75/ignore-error/1&e=1793462399&token=q6iZ0sQtf9U7s1qz0r4yMawNq3-u2w6lbnai6y2J:kkMtCvlvszjGAuxnqB-22Pmaa64=)**马丁**：**置顶**电脑有惊无险修复了，开始更新！

2026-05-14 22:05

**2**

![](https://images.zsxq.com/FmWMJSzh5V-oDt_oLqNSbCOPCFI5?imageMogr2/auto-orient/thumbnail/150x/format/jpg/blur/1x0/quality/75/ignore-error/1&e=1793462399&token=q6iZ0sQtf9U7s1qz0r4yMawNq3-u2w6lbnai6y2J:F_sIifRz7m8v9QQ6ZiZDXrnyb0c=)**李诗雅**：**加油马哥，数据会回来的**

2026-05-11 23:23

**马丁** 回复 **李诗雅**：![](https://wx.zsxq.com/assets_dweb/images/emoji/%E6%8B%A5%E6%8A%B1.png "拥抱")![](https://wx.zsxq.com/assets_dweb/images/emoji/%E6%8B%A5%E6%8A%B1.png "拥抱")

2026-05-11 23:27

![](https://images.zsxq.com/FmWMJSzh5V-oDt_oLqNSbCOPCFI5?imageMogr2/auto-orient/thumbnail/150x/format/jpg/blur/1x0/quality/75/ignore-error/1&e=1793462399&token=q6iZ0sQtf9U7s1qz0r4yMawNq3-u2w6lbnai6y2J:F_sIifRz7m8v9QQ6ZiZDXrnyb0c=)**李诗雅**：**我说五一期间怎么马哥没更新 也挺久了**

2026-05-11 23:23

![](https://images.zsxq.com/FiilZQAHRkkFCobZ1PZkxTyxoxQp?imageMogr2/auto-orient/thumbnail/150x/format/jpg/blur/1x0/quality/75/ignore-error/1&e=1793462399&token=q6iZ0sQtf9U7s1qz0r4yMawNq3-u2w6lbnai6y2J:Ai9mUCAuIHk3HcWRdEdvc8T-eMk=)**XXXKML**：**马哥辛苦了！希望数据能保留下来~**

2026-05-11 23:24

![](https://images.zsxq.com/Fnkm3cjPJ4wHz8vzSXeB8JBT_yLX?imageMogr2/auto-orient/thumbnail/150x/format/jpg/blur/1x0/quality/75/ignore-error/1&e=1793462399&token=q6iZ0sQtf9U7s1qz0r4yMawNq3-u2w6lbnai6y2J:P6UYOMmCCkn3A2WXTf2PWLNNXrw=)**何必归星辰**：**好期待，希望数据没事**

2026-05-11 23:25

![](https://images.zsxq.com/Fir2TyXLA9sOt4FM3OBophx87i_V?imageMogr2/auto-orient/thumbnail/150x/format/jpg/blur/1x0/quality/75/ignore-error/1&e=1793462399&token=q6iZ0sQtf9U7s1qz0r4yMawNq3-u2w6lbnai6y2J:JVHsPwYC8mMpaS1IkVKzPy2vqEs=)**侯**：**期待马哥更新 希望数据能回来**

2026-05-11 23:26

![](https://images.zsxq.com/FhSuKO_WKNUMS2XYOesogt7QIagM?imageMogr2/auto-orient/thumbnail/150x/format/jpg/blur/1x0/quality/75/ignore-error/1&e=1793462399&token=q6iZ0sQtf9U7s1qz0r4yMawNq3-u2w6lbnai6y2J:o7yk8bJ9cU7f0CLJB_R7pROpge8=)**小龙猫**：**马哥辛苦了，数据会保住的**![](https://wx.zsxq.com/assets_dweb/images/emoji/%E7%8E%AB%E7%91%B0.png "玫瑰")![](https://wx.zsxq.com/assets_dweb/images/emoji/%E7%8E%AB%E7%91%B0.png "玫瑰")![](https://wx.zsxq.com/assets_dweb/images/emoji/%E7%8E%AB%E7%91%B0.png "玫瑰")

2026-05-11 23:32

![](https://images.zsxq.com/FsHwWtG1vcdeTSoPjIqFMRpv-KUo?imageMogr2/auto-orient/thumbnail/150x/format/jpg/blur/1x0/quality/75/ignore-error/1&e=1793462399&token=q6iZ0sQtf9U7s1qz0r4yMawNq3-u2w6lbnai6y2J:jtteS0f5id4bLNx1X59F3j-P2L0=)**一加一等于三**：**马哥这套机制与ragas对比有什么不同？**

2026-05-11 23:42

**马丁** 回复 **一加一等于三**：**包括不限于，其中RAG核心内容会基于 RAGAS 去做，有些 RAGAS 做不了的，我会单独实现。

六、作者的有效内容：(里面有各种格式的内容，包括图片、链接、表格等等，别的地方的图片全不要，这里的图片可以保留，这需要我们讨论一下.然后就是只看星主里面的相邻的文章都是相关的:前一篇的结尾预告下一篇文章，后一篇的文章提一下前一篇的内容)
《AI大模型Ragent项目》——Ragent 2.0项目启动与数据初始化

来自： 拿个offer-开源&项目实战

用户头像

马丁

2026年09月16日 23:28

前两篇把 Agent 的基本概念讲清楚了，这一篇先把 Ragent 2.0 跑起来。

整体启动方式和 1.0 差不多，主要多了数据库重建、比特严选数据初始化和 MCP Server。Elasticsearch、LightRAG、LangFuse 都是可选项，用到对应能力时再启动。

有一条启动顺序需要提前记住：MCP Server 必须先于 RagentApplication 启动。主服务只会在启动阶段发现并注册 MCP 工具，顺序反了就要重启主服务。后续文章默认都使用这里初始化好的环境和数据。

模型服务切换至 DeepSeek

DeepSeek 随着 26年9月份发布 deepseek-flash 模型后，对于 Agent 对话有着不错的增强，咱们 v2 架构的 Agent 对话使用该模型。

2.0 架构下，百炼平台没有变化，把之前轨迹流动的向量模型切换了过来。再然后就是新增加了 DeepSeek。

步骤 1：注册并登录

访问 DeepSeek 开放平台官网：https://platform.deepseek.com

步骤 2：创建并复制 API Key

登录后，进入 「API Keys」 管理页面，点击 「创建 API Key」，输入密钥名称（如 ragent），创建成功后点击 「复制」 按钮保存 API Key。

如果仅用于本地开发测试，可以直接将 API Key 写入 application.yaml 配置文件中：

YAML

ai:

  providers:

    bailian:

      api-key: sk-xxxxxxxxxxxxx  # 替换为你的阿里云百炼 API Key

    deepseek:

      api-key: sk-xxxxxxxxxxxxx  # 替换为你的DeepSeek API Key

⚠️ 安全提示： 直接将密钥写入配置文件存在泄露风险，请勿将包含真实密钥的配置文件提交到 Git 仓库。如果需要提交 Git 仓库，可以选择通过环境变量注入方式。

多出来的服务，不用一次全开

1. 先看结论

1.0 已经用到的 PostgreSQL、Redis、RocketMQ、对象存储等基础服务，仍按原来的方式准备。

2.0 分支里比较显眼的新面孔是 Elasticsearch、LightRAG 和 LangFuse。它们各管一件事，也各有独立开关。

服务

解决什么问题

默认是否启用

什么时候才需要启动

仓库内位置

Elasticsearch

BM25 关键词召回，补向量检索对编号、专有名词和精确词不敏感的问题

否

打开关键词检索通道时

resources/docker/elasticsearch-8.17.sh

Neo4j + LightRAG

构建知识图谱，提供图谱可视化与多跳关系召回

否

打开图谱后端时

resources/docker/graphrag/

LangFuse

查看 Agent 每一轮模型输入、工具调用、耗时与 Token

否

排查或分析 Agent 链路时

resources/docker/langfuse/

默认配置已经把这些能力关掉：rag.keyword.type=none、rag.graph.type=none、agent.trace.enabled=false。不启动对应服务，主链路照常使用向量检索和 Agent 能力。

图片.png

把三套可选服务全开当然也能跑，代价是本机资源占用、启动时间和排障面都会扩大。尤其 LangFuse 的本地编排包含 Web、Worker、ClickHouse、PostgreSQL、Redis、MinIO 六个容器，只为了先把项目跑起来，没必要背上这组开销。

如果大家想学习 2.0 的 Agent 架构，上面三个都不是必须。我建议可以跳过直接看下个章节，除非你的本地电脑配置比较好，不然带起来这其中的服务太重了。

2. Elasticsearch：开关键词通道时再装

2.1 用脚本启动带 IK 的实例

仓库已经提供了启动脚本。在项目根目录执行：

Bash

sh resources/docker/elasticsearch-8.17.sh

脚本会启动 Elasticsearch 8.17.0，映射宿主机的 9200 端口，并安装同版本的 IK 分词插件。数据保存在 Docker Volume es_data 中，容器重建后仍会保留。

不用关键词检索时保持下面这组默认值：

YAML

rag:

  keyword:

    type: none

    es:

      uris: http://127.0.0.1:9200

      index: rag_keyword_store

      analyzer: ik_max_word

      search-analyzer: ik_smart

  search:

    channels:

      keyword:

        enabled: false

要打开时，把 rag.keyword.type 改为 es，再把 rag.search.channels.keyword.enabled 改为 true。这两个开关不是重复配置。前者决定是否创建 ES 客户端、索引服务和关键词通道，后者决定一次检索是否真的让这条通道参与召回。只改后一个、没改前一个，应用会在启动校验时直接指出后端没有装配。

2.2 为什么不把它设成默认依赖

关键词检索擅长型号、错误码、订单编号这类精确词，向量检索擅长语义近似，两者融合通常比单路更稳。但“通常更稳”不等于每个本地开发环境都应该强制安装 ES。单纯体验 Agent 的工具循环时，关键词通道并不是前置条件；多维护一份索引，还会增加文档写入、删除与重建时的同步成本。

Ragent 的选择是让 ES 按配置出现。rag.keyword.type=none 时，关键词读写实现都不存在，向量写入链路也不会额外挂一层关键词索引同步。等后面专门讲混合检索时再开启，问题边界更干净。

3. LightRAG：图谱能力是一整套栈

3.1 Docker 文件放在哪里

LightRAG 的本地编排在 resources/docker/graphrag/。这不是单独一个 LightRAG 容器，而是 Neo4j + LightRAG 两个进程。图数据放 Neo4j，LightRAG 的 KV、向量和文档状态复用宿主机现有的 PostgreSQL。

在项目根目录执行：

Bash

cd resources/docker/graphrag

cp .env.example .env

# 编辑 .env，填写 BAILIAN_API_KEY，并核对 PostgreSQL 地址和密码

docker compose -f lightrag-neo4j-stack.compose.yaml up -d

POSTGRES_HOST 在模板里是 host.docker.internal，因为容器要反向连接宿主机 PostgreSQL；EMBEDDING_DIM 默认是 1536，必须与主应用的 rag.default.dimension 一致。两个服务都变成 healthy 后，可以访问 http://localhost:9621/health 检查 LightRAG，访问 http://localhost:7474 打开 Neo4j Browser。

3.2 后端开关要分清两层

要接入 LightRAG，先让后端装配图谱实现：

YAML

rag:

  graph:

    type: lightrag

    lightrag:

      base-url: http://127.0.0.1:9621

      query-mode: hybrid

    embedding-model: qwen-emb-8b

  search:

    channels:

      graph:

        enabled: true

rag.graph.type=lightrag 打开后，LightRagClient 才会注册，知识文档的向量写入也会同步维护图谱。rag.search.channels.graph.enabled=true 决定问答检索时是否把图谱结果并进多通道召回。只想先看后台图谱可视化、暂时不让它参与答案，可以把前一个打开、后一个仍设为 false，这是合法组合。

不开图谱时，两处都保持默认关闭。这里不建议为了所谓的“尝鲜”“配置齐全”把 type 先写成 lightrag，因为一旦装配了图谱客户端，后续文档导入就会开始尝试同步图谱。服务没准备好虽然不会阻断主链路，却会留下持续的警告和一份不完整的图数据。

下面这个图是我之前测试数据时跑出来的效果，大家可以参考下：

图片.png

4. LangFuse：排查链路时再开

4.1 本地编排与端口

LangFuse 的 Compose 文件在 resources/docker/langfuse/：

Bash

cd resources/docker/langfuse

docker compose -f langfuse-stack-v4.compose.yaml up -d

docker compose -f langfuse-stack-v4.compose.yaml ps

六个容器全部健康后，打开 http://localhost:3000，本地默认账号为 admin@ragent.dev，密码是 admintrace。这套编排只把 3000 端口发布到宿主机，内部使用的 ClickHouse、PostgreSQL、Redis 和 MinIO 都走 Docker 网络，避免与 Ragent 自己的 5432、6379、9000 等端口冲突。

4.2 主应用怎么接

Compose 已经预置了本地项目和密钥，主应用侧打开追踪即可：

YAML

agent:

  trace:

    enabled: true

    capture-content: true

    endpoint: http://localhost:3000/api/public/otel/v1/traces

    public-key: pk-lf-ragent-local

    secret-key: sk-lf-ragent-local

capture-content=true 会记录完整提示词、模型输出和工具入参出参。本机排障很方便，放到共享环境前要先确认数据边界；只想看轮次、耗时、Token 和状态，把它改成 false。不用追踪时只把 agent.trace.enabled 保持为 false，LangFuse 整套都可以不启动。

启动应用前，先把数据库重建干净

1. 这次不建议在 v1 库上继续叠升级脚本

不管你之前有没有启动过 v1，这次都建议把 ragent 数据库删除后重新创建，再执行当前仓库里的全量脚本：

resources/database/schema_pg.sql

resources/database/init_data_pg.sql

这是课程环境的选择，不是说增量升级做不到。仓库里保留了 resources/database/upgrades/，生产环境也不可能动不动删库。但课程后面的每一段代码、内置人设、Agent 槽位和表结构都以当前全量脚本为基线。如果有人从 v1 逐个补升级脚本，有人直接跑全量脚本，后面碰到问题时很难先判断是代码差异还是数据库状态差异。

代价也要说在前面：删除旧库会丢掉原来的知识库、文档记录、会话、用户配置和运行轨迹。需要保留的内容先备份。确认这是本地学习或演示环境，再继续。

2. 删除、创建、执行脚本

先停掉仍在连接 ragent 的主应用。使用 TablePlus、DataGrip、DBeaver 或 pgAdmin 都可以，操作目标一致：连到 postgres 维护库，删除旧的 ragent，重新创建同名数据库，然后切到新库依次执行两个 SQL 文件。

习惯命令行的话，在项目根目录执行下面这组命令。账号、主机和端口与本机不一致时自行补 -h、-p 或修改用户：

Bash

psql -U postgres -d postgres -c "DROP DATABASE IF EXISTS ragent WITH (FORCE);"

psql -U postgres -d postgres -c "CREATE DATABASE ragent;"

psql -U postgres -d ragent -f resources/database/schema_pg.sql

psql -U postgres -d ragent -f resources/database/init_data_pg.sql

如果你已经跑过较早的 2.0 代码，本机可能还有一个 ragent_bit 数据库。它是 MCP Server 使用的比特严选业务库，不在上面两个全量脚本里。为了避免业务库结构版本不一致，可以一并删除，但先不要手工建表：

Bash

psql -U postgres -d postgres -c "DROP DATABASE IF EXISTS ragent_bit WITH (FORCE);"

稍后启动 McpServerApplication 时，BitSchemaInitializer 会创建 ragent_bit 并执行 mcp-server/src/main/resources/db/bit-schema.sql。把业务库建表放在 MCP Server 启动期是安全的，因为这一步只补结构、不清数据；演示数据的清空与重灌留给一次性的初始化器。要是把两件事揉在一起，每重启一次 MCP Server，订单和购物车就会被清空一次。

先认识 bit-selection 初始化器，暂时不要执行

1. 两层初始化不是一回事

刚才执行的 schema_pg.sql 和 init_data_pg.sql 负责把平台库建好，放进去的是系统运行所需的表结构、管理员账号、内置智能体和默认提示词。

resources/initializer/bit-selection/ 是第二层。它把一个已经运行的空白 Ragent 环境，重置成后续文章统一使用的比特严选：

数据

当前模板内容

写到哪里

知识库

4 个

平台库与向量存储

知识文档

69 篇

对象存储、Chunk 表与向量索引

意图节点

42 个

平台库

Agent Skill

5 份

平台库

示例问题

15 条

平台库

商品

695 个 SPU、1420 个 SKU

ragent_bit 业务库

人设

新建并激活“比特严选”人设

t_agent_profile 与 t_agent_prompt

这里没有单独的 Maven 模块。初始化器是一组一次性 Java 17 CLI，源码就在 resources/initializer/common/ 与 resources/initializer/bit-selection/ 下。这样做的好处是模板和执行器放在一起，改数据不需要给主应用再塞一个只用一次的启动入口。

未来可能会修改知识库中的文档、意图节点等。后续大家测试时，拉下最新代码，每次都执行次初始化就好了，会自动清空数据并重建。

2. 为什么现在只能讲，不能马上跑

初始化器不是离线执行几份 SQL。它会登录主服务，通过 HTTP 创建知识库、上传文档、触发分块、创建意图和技能；它还会连接 PostgreSQL 与 Redis 做预检和定向清理，并连接 ragent_bit 灌商品、订单、优惠券和购物车数据。

所以它真正执行时依赖下面这些条件：

PostgreSQL、Redis、RocketMQ、对象存储等基础服务已就绪。

MCP Server 至少成功启动过一次，ragent_bit 及其表结构已经存在。

RagentApplication 正在运行，初始化器能访问它的 HTTP 接口。

主服务使用 ragent.demo-mode=false，否则写接口会拒绝初始化。

application.yaml 与 mcp-server/application.yml 里的数据库地址、账号和密码指向当前环境。

这就是为什么数据初始化要在启动应用前先讲清楚，但命令不能现在执行。实际顺序是：重建平台库，启动 MCP Server，启动主服务，再运行 InitializeMain，最后启动前端查看结果。

图片.png

3. 完整流程在代码里是什么顺序

InitializeMain 没有把步骤藏在配置里，入口就是一条能顺着读下来的调用链：

Java

public static void main(String[] args) {

    MainSupport.run(args, context -> {

        InitializationActions.preflight(context);

        InitializationActions.cleanup(context);

        // 业务库与知识库互不依赖，但必须排在 verify 之前：商品行数与商品详情篇数是同一条约束的两端

        InitializationActions.initializeBizData(context);

        InitializationActions.initializeKnowledgeBases(context);

        InitializationActions.initializeDocuments(context);

        InitializationActions.initializeIntentTree(context);

        InitializationActions.initializeSkills(context);

        // 必须排在 cleanup 之后：cleanup 按 builtin = 0 删行，先建的人设会被它删掉

        InitializationActions.initializeAgentProfile(context);

        InitializationActions.initializeSampleQuestions(context);

        InitializationActions.verify(context);

        InitializationActions.warmup(context);

        System.out.println("[initializer] SUCCESS");

    });

}

顺序里有两处不能随便换。技能要等意图树创建完，因为技能声明里的 tool-ids 引用的是已启用的 MCP 意图节点；人设要放在清理之后，因为清理会删除非内置人设，提前创建等于刚建完又被删。

业务库数据没有塞进 MCP Server 的启动流程，也是刻意分开的。MCP Server 管表结构，保证工具一启动就有地方读写；InitializeMain 管破坏性的清空与重灌，必须带精确确认词才执行。前者可以反复启动，后者只能在明确允许重置数据时运行。

图片.png

MCP Server 这次是必选项

1. v1 可以不启，这次不行

在 v1 的纯知识问答场景里，MCP Server 没启动时，主服务可以跳过远程工具注册，RAG 主链路仍然能回答知识库问题。所以旧启动文档把它写成了可选项。

后面的比特严选案例不是纯知识问答。查订单的 query_order、查物流的 query_logistics、申请售后的 apply_after_sale、取消订单的 cancel_order 都由 mcp-server 提供。初始化器也会校验技能引用的 MCP 节点，后续 Agent 对话则要真的调用这些工具。这套数据下，MCP Server 必须启动。比特严选的预热问题只选知识型问法，走 /rag/v3/chat 直答链路，不拿预热是否成功判断 MCP 工具是否可用。

2. 先核对业务库配置

配置文件是 mcp-server/src/main/resources/application.yml。默认端口与业务库连接如下：

YAML

server:

  address: 127.0.0.1

  port: 9099

ragent:

  bit:

    datasource:

      url: jdbc:postgresql://127.0.0.1:5432/ragent_bit?client_encoding=UTF8

      username: postgres

      password: postgres

启动时，BitDataSourceConfig.bitDataSource() 会先调用 BitSchemaInitializer.initialize()，确认 ragent_bit 存在并把表结构建好，然后才创建连接池。数据库账号需要有创建数据库的权限；没有权限时，日志会要求你手工创建 ragent_bit 后重启，但表仍由 MCP Server 自己创建。

在 IDEA 中找到 mcp-server/src/main/java/com/nageoffer/ai/ragent/mcp/McpServerApplication.java，运行 McpServerApplication。看到 Started McpServerApplication 后，再确认 PostgreSQL 里出现了 ragent_bit 和 t_schema_version。

3. 主服务必须后启动

主服务的 MCP 地址在 bootstrap/src/main/resources/application.yaml：

YAML

rag:

  mcp:

    servers:

      - name: default

        url: http://localhost:9099

McpClientAutoConfiguration.init() 在主服务启动期遍历这份配置，连接 http://localhost:9099/mcp，完成握手，调用 listTools()，再把每一个远程工具注册进本地工具目录。连接失败时它会记一条错误并跳过，不阻止主服务启动，也没有后台重连任务。

因此看到 RagentApplication 已经启动成功，不代表 MCP 工具已经可用。正确的检查点是主服务日志里出现“连接 MCP Server”以及“返回 N 个工具”。如果曾经把顺序弄反，先把 MCP Server 启好，再重启 RagentApplication，不用改数据库，也不用重跑初始化。

启动 Ragent，再执行比特严选初始化

1. 主服务配置沿用 v1，补上 Agent 档位

主服务仍从 bootstrap/src/main/resources/application.yaml 读取配置。PostgreSQL、Redis、RocketMQ、对象存储和 AI Provider 的准备方式沿用 v1，启动前再核对这些与 2.0 直接相关的值：

YAML

ragent:

  engine:

    type: agent

  demo-mode: false

agent:

  chat:

    provider: deepseek

    model: deepseek-flash

ragent.engine.type=agent 才会装配 2.0 的 ReAct 执行链路。ragent.demo-mode=false 是初始化器能够调用写接口的前提。模型 Key 建议通过环境变量传入，当前默认配置至少会用到 DEEPSEEK_API_KEY；知识文档向量化、问题改写与精排还会使用 BAILIAN_API_KEY 对应的百炼能力。

在 IDEA 中运行 bootstrap/src/main/java/com/nageoffer/ai/ragent/RagentApplication.java。默认端口是 9090，Context Path 是 /api/ragent。看到 Started RagentApplication 后，先看一眼 MCP 工具注册日志；如果工具数是空的，此时不要急着跑初始化，先处理 MCP 连接。

2. 编译一次性初始化器

下面命令都在项目根目录执行。先删除旧 class 再编译，不能复用上一次留在 /tmp 里的产物。通用执行器一旦变过，旧 class 报出来的错误往往会离真实原因很远。

Bash

rm -rf /tmp/ragent-initializer-classes

mkdir -p /tmp/ragent-initializer-classes

javac -encoding UTF-8 \

  -d /tmp/ragent-initializer-classes \

  resources/initializer/common/*.java \

  resources/initializer/bit-selection/*.java

第一次建议先做 dry-run。它会连接主服务、PostgreSQL 和 Redis，完成模板与环境预检，打印将要清理和创建的内容，但不会真的改数据。dry-run 仍然要求传确认词：

Bash

java -cp /tmp/ragent-initializer-classes \

  com.nageoffer.ai.ragent.initializer.InitializeMain \

  --agent-type-dir resources/initializer/bit-selection \

  --confirm RESET-BIT-SELECTION \

  --dry-run

预检通过后执行完整初始化：

Bash

java -cp /tmp/ragent-initializer-classes \

  com.nageoffer.ai.ragent.initializer.InitializeMain \

  --agent-type-dir resources/initializer/bit-selection \

  --confirm RESET-BIT-SELECTION

完整流程会删除当前环境里的知识库、文档、意图、技能、示例问题、非内置人设和比特严选业务演示数据，再按模板重建。它不会对 Redis 执行 FLUSHDB，只清模板声明的 Key；平台用户表也不会整表清空。不过这仍然是破坏性操作，不要对共享环境执行。

最后的预热会把 questions.properties 里的问题逐个真实问一遍，生成会话、消息、检索轨迹和推荐追问，通常是整个初始化里最慢的一段。只想先把数据灌好，可以追加 --skip-warmup。看到 [initializer] SUCCESS 才算完成；某几道预热题重试后失败会被列出来，但不影响前面的知识库和业务数据。

3. 启动前端并做一次最小验证

前端的启动方式和 v1 一样，只是当前仓库是 React 18 + Vite。进入 frontend：

Bash

npm install

npm run dev

如果本地没有安装前端，使用 Codex 或者 ClaudeCode 等安装就好。我本地 NodeJs 版本：v22.12.0。

开发服务器默认在 http://localhost:5173，/api 会代理到 http://localhost:9090。使用 admin / admin 登录后，先确认欢迎页出现比特严选示例问题，再做两次验证：

问一个纯知识问题，例如“AirPods Pro 3 支持哪些听力健康功能”，确认 search_knowledge 能返回初始化文档里的内容；

问“订单 88231 现在是什么状态”，确认 Agent 能调用 query_order，而不是回答“没有查询订单的能力”。

第一条验证主服务、知识库和向量链路，第二条验证 MCP 工具发现与比特严选业务库。两条都通了，后面文章里的例子才是在同一张底图上运行。

或者来个比较负责的问题：

text

先不要直接下单，帮我整理一下购物车并比较最省钱的方案：

保留 iPhone 18 Pro 256GB 黑色和两只保护壳；Vision Pro 不要了；AirPods Pro 3 如果确实没货就移除；42 毫米表带这次也不需要。

请比较两种方案：

A. 剩余商品直接使用当前最划算的优惠券；

B. 加购一件 1500 元以内、有库存、确定能和 iPhone 搭配使用的商品，使订单达到大额满减券门槛。

方案 B 给我两个候选，分别算清商品总额、优惠金额、预计实付，以及相比方案 A 实际多花多少钱。先只给方案，不要修改购物车。

另外帮我找到包含 iPad Air 11 英寸和 Apple Pencil Pro 的那笔订单并查询物流；如果已经发货，只告诉我最新轨迹，不要尝试修改地址。

几个容易混淆的地方

1. 两个数据库怎么初始化

数据库

数据库和表结构

初始化数据

ragent

手工创建数据库，再执行 resources/database/schema_pg.sql

init_data_pg.sql 写入基础数据；InitializeMain 再写入比特严选的知识库、文档、意图、Skill 和人设

ragent_bit

MCP Server 启动时由 BitSchemaInitializer 自动建库、建表

InitializeMain 通过 initializeBizData() 写入商品、订单、物流、购物车和优惠券

简单来说，ragent 是平台库，需要手工重建；ragent_bit 是比特严选业务库，先由 MCP Server 建好结构，再由初始化器灌数据。

2. 哪些服务可以不启动

不用关键词检索，可以不启动 Elasticsearch。

不用知识图谱，可以不启动 Neo4j 和 LightRAG。

不看调用链，可以不启动 LangFuse。

MCP Server 必须启动，并且要早于主服务。

3. 初始化失败怎么查

现象

检查项

找不到 application.yaml

检查 initializer.properties 中的 application.config

无法登录 RagentAI

检查主服务端口和 /api/ragent 路径

写接口被拒绝

确认 ragent.demo-mode=false

找不到业务表

先确认 MCP Server 是否成功启动过

订单已经写入但查询不到

检查初始化账号，以及业务数据绑定的用户 ID

文档处理一直没有完成

检查 RocketMQ、对象存储、Embedding 配置和分块日志

Agent 中没有订单工具

先启动 MCP Server，再重启主服务

checksum 不一致

模板有改动，需要重新生成 checksums.sha256

初始化完成后，以最后一行 [initializer] SUCCESS 为准。只有预热问题允许个别失败，其他步骤报错都会直接停止。

文末小结

环境跑起来之后，下一篇就不再绕着配置打转了。Ragent 1.0 那套手写 ReAct 明明能跑，2.0 为什么要换成 AgentScope，多背一个框架依赖到底替项目扛走了什么，又留下了什么新的约束？
七、像是这种的都是没有必要的技术分享（我用不到，跟我们的面试和项目均不相关)
triwD3

2025-02-07 15:22

看马哥视频里敲代码很快，可不可以分享一些经常使用的IDEA快捷键坏笑。#🧐技术分享 

 分享几个自己常用的，期待兄弟们在评论区分享你们的：

1. ⌥ + Left/Right  移动到前/后一个单词

2. ⌘ + Left/Right  移动行首/行末

上述两个都可以搭配 shift 快速选中

3. ⌘ + P 显示参数信息

4. ⌘ + 1 项目结构

5. ⌘ + B 快速打开光标处的类或方法

🧐技术分享

查看详情

Aaron、Dong、马丁、.、扶不上墙、HSY🧸、🍊序🐵努力学习工作💕、LS～Dipper、希望~无尽、😁 觉得很赞

马丁：很赞，学到了👍

2025-02-09 23:49
