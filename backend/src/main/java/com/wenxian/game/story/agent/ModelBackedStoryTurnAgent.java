package com.wenxian.game.story.agent;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.json.JsonReadFeature;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.wenxian.game.story.domain.StoryPatch;
import com.wenxian.game.story.domain.StoryActionContract;
import com.wenxian.game.story.domain.StoryPatchValidator;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 使用中性模型网关生成普通回合的剧情和 StoryPatch。
 *
 * <p>上下文按客观事实、认知、记忆、历史叙事和玩家行动分层序列化。模型首次返回无法解析或违反通用
 * 结构约束时，只允许一次不携带完整历史的格式修复；提案随后被 Reducer 拒绝时，调用方还可请求一次只携带
 * 客观工作集的语义修复。任何修复仍不通过时拒绝整轮，数据库和玩家回合保持不变。</p>
 */
@Service
public class ModelBackedStoryTurnAgent implements StoryTurnAgent {
    private static final String SYSTEM_PROMPT = """
            你是《问仙录》的回合叙事与变化提案 Agent。你可以自由演绎世界，但模型输出不是事实，服务端会校验后提交。

            严格遵守以下边界：
            1. objective 是当前唯一客观事实；cognition 是人物所知或所信；memory、narrative 只是叙事材料；
               action 是玩家本轮授权。不得用摘要、旧正文或人物观点静默覆盖 objective。
            2. 只执行 action 已授权的行动及其直接后果。来到新的重大、不可逆决定前必须停止，
               decisionCheckpoint.required=true，并把决定交还玩家；不得替玩家加入宗门、立誓、结盟、屠杀、放弃传承等。
               objective.pendingDecision 不为空时，本轮 action 是玩家对该问题的回应；若 action 没有明确回答，必须继续返回
               相同的 required=true 和 prompt，不能擅自清除、跳过或替玩家决定。
               玩家没有在 action 中明确做出的任何后续决定，正文、memory 和 rollingSummary 都不得写成“你决定”、
               “你已打算”或“你准备接下来”。可以写主角观察到的候选方向，但必须明确保持“尚未决定”。
               若 action 要求“停下”、“等待我决定”或限定只做某件事，正文必须在该行动完成的静止场景结束，
               不得再追加移动、承诺、接受委托、选定目标或任何新行动。
               “只、仅、不要、仍停在原地、不做其他事情”等限定会把主角的主动行为收束成闭集：只能描写玩家明确
               授权的动作。不得为了填充篇幅让主角再取放或查看物品、施展能力、追问、回忆线索、制定计划；NPC 的
               即时回应和不需要主角选择的环境变化仍可描写。短回合可以自然缩短，不能用未授权行动凑足篇幅。
               生成前必须逐条核对 action 中“要做的事、明确禁止的事、最终停止位置”。若玩家要求到达某处后停下，
               coreState.location 必须更新为该处，正文最后一段也必须停在那里；不得为了收束段落自行返程。
            3. 已有实体只能用 objective 提供的稳定 entityId 更新。不能按名字猜测，不能为了绕过死亡、掌握、数量或归属状态
               创建同名实体。没有在 objective 出现的状态，不得声称它已被系统确认。
               action.factContract 是生成前已通过权威事实校验的本轮行动契约；已有物品的归属变化必须与其中
               itemTransitions 完全一致，不得省略、反向或另行推断。custody 表示物品实际保管者，不能只改正文而不改结构。
               factContract.references 中 resolution=EXISTING 的实体只能使用给定 entityId；resolution=NEW 且
               expectedCustody 非空的物品必须以 canonicalName 创建唯一 ITEM 实体，并写入相同 custody；
               不得把玩家提到的新对象替换成名称或含义不同的历史实体。
               每个 CREATE 实体都必须填写非空的 name、status 和 summary；其中 summary 只记录需要跨回合保持的
               当前事实，不要复制整段正文。UPDATE 未变化的字段才可以返回 null。
               同一处场景及其普通组成部分不得在一回合拆成多个重复 LANDMARK；例如观察一间屋舍的屋檐时，
               最多保留屋舍或屋檐中的一个长期地标。只有各自会被后续行动独立引用时才允许分别建档。
               普通观察得到的屋檐、门框、墙角、路面等组成部分默认只属于当前地点或已有父 LANDMARK 的叙事细节，
               不得仅因本轮提到就 CREATE 新 LANDMARK。只有玩家明确要求长期标记，或它在本轮成为可独立前往、
               交互并影响后续事件的地点时，才允许单独建档。
               objective.currentInventory 是当前行囊的完整权威清单：complete=true 时，不在 items 中的物品不得被写成
               当前携带、可用或可交易。entityIndex 中 visible=false、inPossession=false 或已知数量为0的物品只是
               历史引用；memory、rollingSummary 和 previousTurn 提到旧物品也不能覆盖当前清单。
               currentInventory.historicalItems 明确列出不再持有的旧物及其最后状态；其 status 是已确认经历，正文不得
               把“主动移除、赠予、售出”等明确原因改写成“不知何时遗失”、从未发生或仍可取用。
            4. 物品数量已知时，使用、出售、赠予或获得后必须返回准确的新总量；数量未知就保持 known=false、value=null，
               不得自动记为1。能力的 MASTERED 与 TEMPORARILY_BLOCKED 分开，受限不等于未掌握。
            5. 人物为 DEAD 时不得无依据复活。若 action 明确允许修订且有充分依据，生命周期变化必须写 changeReason。
               人物关系使用 relationship 单独提交；关系名称仍可按剧情自由描述，但已有关系发生变化时必须写
               changeReason，且不得只改 attributes.relationship 来绕过关系审计。
            6. time 必须写本轮实际经过分钟。玩家明确说一月、三年等时必须完整换算；世界未另定历法时按一年360日、
               一月30日计算。若被打断，resolution=INTERRUPTED，并返回实际时长与 interruptionReason，正文必须一致。
               objective.worldClock 是当前可读时刻，正文开场的昼夜、晨昏与它必须一致；只有 time.elapsedMinutes
               推进到新的阶段后，正文才可以描写对应的晨昏变化。
               action.explicitDurationMinutes 与 action.expectedEndClock 非空时，它们是服务端已经确定的时间契约：
               time.elapsedMinutes 必须等于授权时长，正文必须从 objective.worldClock 覆盖到 expectedEndClock，
               不得只描写结束画面而跳过中间经过。
            7. 玩家明确修订世界设定时使用 worldFacts.REVISE 并引用 factId、保留 reason；新增空白设定使用 EXPAND。
               普通剧情中的局势发展使用 TRANSFORM，不得把旧回合正文改写成从未发生。
            8. 正文始终用第二人称“你”指代主角，不在第一、第三人称之间切换。通常写6至10段、约800至1200个中文字符；
               以完成本轮行动和保持沉浸为准，不为凑字重复。正文结束在下一次真正需要玩家决定的位置，不要写出决定后的结果。
            9. 返回2至4个彼此有意义的行动建议；它们只是建议，不能代表玩家已经选择，也不得以主角意向的形式回写进正文、memory 或 rollingSummary。

            只返回一个 JSON 对象，不要 Markdown、解释或额外文字。完整字段如下，未变化的对象字段返回 null，未使用列表返回 []：
            {
              "title":"本回合标题","narrative":"第二人称剧情正文",
              "time":{"elapsedMinutes":0,"resolution":"COMPLETE","interruptionReason":null},
              "coreState":{"location":null,"condition":null,"realm":null,"lifeGoal":null},
              "entities":[{"operation":"CREATE|UPDATE|HIDE|RESTORE","entityId":null,"entityType":"ITEM|ABILITY|CHARACTER|LANDMARK或上下文已有类型",
                "name":null,"status":null,"summary":null,"attributes":null,
                "quantity":{"known":true,"value":1},"inPossession":true,
                "custody":{"holderType":"PLAYER|CHARACTER|LOCATION|NONE|UNKNOWN","holderRef":null},
                "abilityState":"MASTERED|TEMPORARILY_BLOCKED|LOST|UNKNOWN",
                "lifecycle":{"state":"ACTIVE|MISSING|DORMANT|DEAD|UNKNOWN","changeReason":null},
                "relationship":{"label":"当前关系名称","changeReason":"本轮关系改变依据或null"}}],
              "worldFacts":[{"operation":"EXPAND|TRANSFORM|REVISE","factId":null,"statement":"","reason":null}],
              "knowledge":[{"subjectRef":"","topicRef":"","summary":"","evidenceRefs":[]}],
              "memories":[{"memoryType":"EVENT|PROMISE|CLUE|RELATIONSHIP","title":"","summary":"","importance":"KEY|NORMAL","evidenceRefs":[]}],
              "decisionCheckpoint":{"required":false,"prompt":null},
              "choices":[{"title":"","description":""}],"rollingSummary":""
            }
            """;

    private static final String REPAIR_PROMPT = """
            下面是你刚才返回但未通过 JSON/结构校验的结果。只修复格式和契约字段，不新增剧情、事实或决定，
            不需要重新读取完整上下文。只返回修复后的单个 JSON 对象，不要 Markdown。
            """;

    private static final String SEMANTIC_REPAIR_PROMPT = """
            下面的状态提案已经通过 JSON 结构校验，但被服务端事实边界拒绝。只修复被拒绝的补丁语义，
            尽量保留原有标题、正文、时间和选项，不新增未经玩家授权的行动或事件。
            如果 validationProblem 指出正文与上一回合重复、没有处理本轮 action、追加未授权行动或替玩家决定，
            则不得保留旧正文，必须依据 action 重新生成本回合标题、正文、实际时间和选项，同时继续遵守玩家写明的
            限定词、禁止事项与停止位置。短回合可以缩短，不得用新的主动行为凑足篇幅。
            UPDATE/HIDE/RESTORE 与 TRANSFORM/REVISE 只能复制 objective 中已有的稳定 ID；
            若内容确实是新实体或新世界事实，分别改用 CREATE 或 EXPAND，并将对应 ID 设为 null。
            只返回修复后的单个完整 JSON 对象，不要 Markdown、解释或额外文字。
            """;

    private static final String FRESH_REGENERATION_PROMPT = """
            上一份候选正文高度复用了前一回合，没有真正执行玩家的当前 action。
            请从 objective 的当前状态与 action 重新构思一个独立新回合。不得回忆、复述或改写上一回合正文；
            必须正面展示 action 引发的对话、反应或直接结果，并在玩家指定的停止边界结束。
            action.factContract 与 objective 仍是唯一权威事实；不得为了让正文显得不同而新增未授权行动。
            返回符合系统完整 JSON 契约的单个对象，不要 Markdown、解释或额外文字。
            """;

    private static final String REVIEW_PROMPT = """
            你是《问仙录》的独立 Flash 回合审阅 Agent。你只审核候选，不续写剧情，也不替候选补充解释。
            facts 是提交前的权威事实，action 是玩家本轮唯一授权，proposed 是尚未提交的候选结果。

            必须逐项完成六类检查，不能因为发现第一处问题就跳过其余类别：
            1. reference：action 中明确提到的物品、人物、能力或需要跨回合追踪的场景地标是否都被
               factContract.references 覆盖；玩家原话、标准名称、
               EXISTING/NEW/UNRESOLVED 解析和稳定 ID 是否对应，是否把新瓦片之类对象误绑成历史石子、玉简等其他实体。
               水井、屋舍、石桥、山门等无法随身携带的场景对象必须是 LANDMARK 或不持久化，不得当作 ITEM、
               不得进入 currentInventory；只有玩家取得或可被人物、地点保管的可转移对象才是 ITEM。
            2. inventory：候选是否使用、持有、赠送、出售或获得了 facts 未支持的物品；数量和处置是否一致。
            3. authorization：候选是否忽略 action、替玩家做选择，或擅自追加修炼、移动、询问、承诺、交易、战斗等行动。
               当 action 使用“只、仅、不要、不做其他事情、仍停在原地”等限定时，主角获授权的主动行为是闭集；候选
               只要再让主角取放/查看物品、施展能力、追问、调查、回忆线索、制定计划或选择目标，就必须判定失败，
               即使物品确实属于玩家、行为没有产生结构化状态变化、或候选最后又写“仍在原地”也不能放过。
            4. decision：候选是否把 action 没有说明的理由、意图、目标、计划、立场或承诺补成主角已经确认的事实。
               例如 action 只说“说明缘由后取回物品”，候选不得自行补成“我已决定前往某地”。
               NPC 对当前请求的回应、环境直接反应和已授权行动的必然结果不算替玩家决定。
            5. time：开场时刻、经过时长、结束时刻与 action 的时间契约是否一致，是否跳过明确要求叙述的过程。
            6. state：地点、境界、能力掌握、人物生死、待确认决定及 proposed 的结构化变化是否前后一致。
               同一处场景及其普通组成部分若被拆成多个重复 LANDMARK，也必须判定失败；普通观察最多形成一个
               有长期引用价值的场景实体。
               若候选仅因观察而把屋檐、门框、墙角、路面等现有地点组成部分新建为 LANDMARK，且玩家没有明确
               要求标记、该对象也没有成为可独立前往或影响后续事件的地点，必须判定失败。

            proposed.coreState 等结构是“变化补丁”，字段为 null 表示保持 facts 原值，不得据此判定缺失或冲突。
            action 的直接自然后果可以通过，例如完整休息后疲惫缓解；只有候选追加了新的主动行为、重大决定或无依据事实时才拒绝。
            合理的环境细节、NPC 对已授权行为的即时回应和不改变事实的文学修辞可以通过；它们不能包含主角新增的
            主动行为或心理决定。不要评价文风、篇幅、难度或战力平衡。
            每项都必须返回 passed 与 problem。通过时 problem 为空；不通过时准确列出该类别中的冲突。
            只返回以下 JSON，不要 Markdown 或其他文字：
            {
              "reference":{"passed":true,"problem":""},
              "inventory":{"passed":true,"problem":""},
              "authorization":{"passed":true,"problem":""},
              "decision":{"passed":true,"problem":""},
              "time":{"passed":true,"problem":""},
              "state":{"passed":true,"problem":""}
            }
            """;

    private static final String ACTION_CONTRACT_PROMPT = """
            你是《问仙录》的行动事实解析 Agent。你不续写剧情、不判定胜负，只把玩家本轮自然语言行动解析成
            可由服务端校验的稳定实体引用和前置条件。facts 是本轮开始前的唯一权威事实。

            规则：
            0. facts.coreState 包含玩家本轮开始时已确认的地点与状态。action 只是观察当前地点、
               建筑、水井、道路或景物时，不得因 entityIndex 中没有单独实体就报 UNRESOLVED，也不得把它创建成 ITEM。
               只有确实需要在以后回合稳定引用该场景对象时才返回 NEW LANDMARK；普通观察可不返回 reference。
               屋檐、门框、墙角、路面等当前地点或已有 LANDMARK 的普通组成部分，玩家仅观察时不得返回 NEW；
               只有玩家明确要求长期标记，或它已经成为可独立前往、交互并影响后续事件的地点时才可返回 NEW LANDMARK。
            1. action 中每一个作为独立剧情对象被查看、使用、取得或处置的物品、人物或能力都必须放入 references，
               查看但不消耗也不能省略。mention 必须保留玩家原话中的对象短语，canonicalName 是解析后的标准名称。
               “放入行囊、拿在手中、留在屋内/地面”等语句里的容器、身体部位和普通方位只表达保管关系，
               不是新剧情实体；除非 facts 已有稳定实体，或玩家明确把它本身作为独立对象取得/处置，否则不要为
               行囊、手、衣袖、地面、屋内等创建 reference，更不能标成 NEW ITEM。
               需要跨回合追踪的水井、屋舍、石桥、山门等场景对象使用 LANDMARK；不需要跨回合追踪的普通环境细节可以不返回 reference。
               LANDMARK 不是物品，expectedCustody 必须为 null，不得进入 itemTransitions；只有可被取得、携带、消耗或转交的对象才是 ITEM。
            2. resolution=EXISTING 仅用于 facts.entityIndex 中能唯一对应的实体，必须复制稳定 entityId、entityType 和名称；
               resolution=NEW 仅用于玩家在本轮明确遇见、拾取或创造的新对象，entityId=null；玩家把不存在的对象当成
               当前已持有或已认识时使用 UNRESOLVED，并令 conflict.present=true。绝不能按类别相近把瓦片绑定成石子或玉简。
            3. itemActionDetected 表示本轮会改变物品的总数量或保管者，包括明确拾取并带走的新物品。查看、鉴定，或使用后
               仍原样保留的已有物品不产生持久变化，但仍必须返回 EXISTING reference。
            4. 玩家消耗、赠送、出售、丢弃、存放、取回、夺回已有物品，且会改变总数量或保管者时，
               itemActionDetected=true，并为每件发生持久变化的已有物品返回 itemTransitions。
               玩家拾取并放入行囊的新物品不写 itemTransitions，而是在 NEW reference.expectedCustody 中写 PLAYER；
               只是一闪而过、不需要跨回合追踪的新环境细节，expectedCustody=null。
            5. expectedFrom 表达玩家原话中行动所依赖或声称的起始保管者；target 表达该稳定物品实体在行动成功后的
               最终保管者。不要为了让契约与 facts 匹配而把玩家声称的起始保管者改成客观事实：玩家说“从行囊取出、
               使用、吃下”就是依赖 PLAYER，即使 facts 显示已经移除，也必须保留 PLAYER，让服务端发现冲突。
               reference.expectedCustody 同样只表达玩家行动明确依赖的起始保管者；没有表达归属前提时可为 null，
               不能机械复制 facts。发生 itemTransitions 时两者必须一致。“请某人还给我”开始前依赖 CHARACTER，
               结束后才由 PLAYER 保管，不能因为句中出现“给我”就把 expectedCustody 写成 PLAYER。
               PLAYER 的 holderRef=null；CHARACTER 必须使用 facts 中人物稳定 ID；LOCATION 可使用当前地点文字；
               NONE 表示消耗或毁去；无法确认新人物稳定 ID 时使用 UNKNOWN。
               已知堆叠物品只消耗或转移一部分、剩余部分仍在玩家手中时，target 必须是 PLAYER，quantity 填本轮处置数量；
               不要因为其中一枚被消耗就把整个物品实体标成 NONE。
            6. 如果 action 明确声称“之前已经赠送/卖出/取得/杀死”等事实，或者依赖“师父、道侣、盟友、仇敌”等
               已经成立的人物关系，但 facts 与该前提冲突，
               conflict.present=true，reason 说明当前事实，correction 给出不替玩家决定的可行改写。
               同理，玩家把已移除、已消耗、已赠予或留在地点的物品说成仍在行囊时必须报冲突，不能继续生成剧情，
               也不能把 expectedFrom 改写成 facts 中的实际保管者来绕过校验。
            7. 不因为行动困难、境界不足或结果未知而报冲突；这些属于后续剧情。只检查已经发生的客观前提。
               characterPreconditions 只能复制 facts 中非空的 lifecycle 与 relationship.label；facts 为 UNKNOWN 时可填 UNKNOWN，
               facts 没有对应字段时必须填 null，不得根据人物 status 或 summary 猜成 ACTIVE、DEAD 或某种关系。
            8. stopBoundary 只概括玩家明确要求的停止点；没有则为空。

            只返回 JSON，不要 Markdown：
            {
              "intentSummary":"",
              "itemActionDetected":false,
              "references":[{
                "mention":"玩家原话中的对象短语",
                "resolution":"EXISTING|NEW|UNRESOLVED",
                "entityId":null,
                "entityType":"ITEM|CHARACTER|ABILITY|LANDMARK",
                "canonicalName":"标准名称",
                "role":"SOURCE|TARGET|MENTION",
                "expectedCustody":{"holderType":"PLAYER|CHARACTER|LOCATION|NONE|UNKNOWN","holderRef":null}
              }],
              "itemTransitions":[{
                "itemId":"",
                "expectedFrom":{"holderType":"PLAYER|CHARACTER|LOCATION|NONE|UNKNOWN","holderRef":null},
                "target":{"holderType":"PLAYER|CHARACTER|LOCATION|NONE|UNKNOWN","holderRef":null},
                "quantity":null
              }],
              "characterPreconditions":[{"characterId":"","requiredLifecycle":"ACTIVE|MISSING|DORMANT|DEAD|UNKNOWN","requiredRelationship":null}],
              "stopBoundary":"",
              "conflict":{"present":false,"reason":"","correction":""}
            }
            """;

    private final StoryModelGateway gateway;
    private final StoryModelInvocationRecorder invocations;
    private final ObjectMapper mapper;
    private final ObjectMapper tolerantMapper;
    private final StoryPatchValidator validator;
    private final String flashReviewModel;

    @Autowired
    public ModelBackedStoryTurnAgent(StoryModelGateway gateway, StoryModelInvocationRecorder invocations,
                                     ObjectMapper mapper,
                                     @Value("${wenxian.story.flash-review-model:deepseek-v4-flash}")
                                     String flashReviewModel) {
        this(gateway, invocations, mapper, new StoryPatchValidator(), flashReviewModel);
    }

    public ModelBackedStoryTurnAgent(StoryModelGateway gateway, ObjectMapper mapper) {
        this(gateway, StoryModelInvocationRecorder.NOOP, mapper, new StoryPatchValidator(),
                "deepseek-v4-flash");
    }

    ModelBackedStoryTurnAgent(StoryModelGateway gateway, StoryModelInvocationRecorder invocations,
                              ObjectMapper mapper, StoryPatchValidator validator) {
        this(gateway, invocations, mapper, validator, "deepseek-v4-flash");
    }

    ModelBackedStoryTurnAgent(StoryModelGateway gateway, StoryModelInvocationRecorder invocations,
                              ObjectMapper mapper, StoryPatchValidator validator, String flashReviewModel) {
        this.gateway = gateway;
        this.invocations = invocations == null ? StoryModelInvocationRecorder.NOOP : invocations;
        this.mapper = mapper;
        this.tolerantMapper = mapper.copy()
                .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)
                .enable(JsonReadFeature.ALLOW_UNESCAPED_CONTROL_CHARS.mappedFeature());
        this.validator = validator;
        this.flashReviewModel = flashReviewModel == null || flashReviewModel.isBlank()
                ? "deepseek-v4-flash" : flashReviewModel.strip();
    }

    @Override
    public Generated generate(StoryPromptContext context, StoryModelRoute route) {
        return generate(context, route, null);
    }

    @Override
    public Generated generate(StoryPromptContext context, StoryModelRoute route, InvocationContext invocation) {
        if (context == null) throw new IllegalArgumentException("回合推演缺少命卷上下文");
        if (context.action() == null || context.action().playerAction() == null
                || context.action().playerAction().isBlank()) {
            throw new IllegalArgumentException("玩家行动不能为空");
        }
        if (route == null) throw new IllegalArgumentException("回合推演缺少模型路由");

        List<StoryModelGateway.Result> attempts = new ArrayList<>();
        StoryModelGateway.Result first = call(route, invocation, "STORY_TURN",
                SYSTEM_PROMPT, userPrompt(context), 0.7);
        attempts.add(first);
        try {
            return new Generated(parse(first.text()), attempts);
        } catch (JsonProcessingException | IllegalArgumentException firstError) {
            StoryModelGateway.Result repaired = call(route, invocation, "STORY_TURN_REPAIR", SYSTEM_PROMPT,
                    repairPrompt(first.text(), firstError.getMessage()), 0.1);
            attempts.add(repaired);
            try {
                return new Generated(parse(repaired.text()), attempts);
            } catch (JsonProcessingException | IllegalArgumentException repairedError) {
                throw new IllegalArgumentException("推演结果未能形成可提交的状态提案", repairedError);
            }
        }
    }

    @Override
    public ResolvedAction resolveAction(StoryPromptContext context, StoryModelRoute route,
                                        InvocationContext invocation) {
        if (context == null || context.action() == null) {
            throw new IllegalArgumentException("行动事实解析缺少命卷上下文");
        }
        if (route == null) throw new IllegalArgumentException("行动事实解析缺少模型路由");
        StoryModelRoute contractRoute = flashReviewRoute(route);
        StoryModelGateway.Result result = call(contractRoute, invocation, "STORY_ACTION_CONTRACT_FLASH",
                ACTION_CONTRACT_PROMPT, actionContractPayload(context), 0.0);
        try {
            StoryActionContract contract = tolerantMapper.readValue(rawJson(result.text()), StoryActionContract.class);
            return new ResolvedAction(contract, List.of(result));
        } catch (JsonProcessingException error) {
            throw new IllegalArgumentException("行动事实解析未返回有效结果", error);
        }
    }

    @Override
    public Generated repairRejected(StoryPromptContext context, StoryModelRoute route,
                                    InvocationContext invocation, StoryPatch rejected, String problem) {
        if (context == null || rejected == null) throw new IllegalArgumentException("语义修复缺少原始提案或上下文");
        if (route == null) throw new IllegalArgumentException("语义修复缺少模型路由");
        List<StoryModelGateway.Result> attempts = new ArrayList<>();
        StoryModelGateway.Result repaired = call(route, invocation, "STORY_TURN_SEMANTIC_REPAIR", SYSTEM_PROMPT,
                semanticRepairPrompt(context, rejected, problem), 0.1);
        attempts.add(repaired);
        try {
            return new Generated(parse(repaired.text()), attempts);
        } catch (JsonProcessingException | IllegalArgumentException firstError) {
            StoryModelGateway.Result formatRepair = call(route, invocation,
                    "STORY_TURN_SEMANTIC_REPAIR_FORMAT", SYSTEM_PROMPT,
                    repairPrompt(repaired.text(), firstError.getMessage()), 0.1);
            attempts.add(formatRepair);
            try {
                return new Generated(parse(formatRepair.text()), attempts);
            } catch (JsonProcessingException | IllegalArgumentException secondError) {
                throw new IllegalArgumentException("语义修复仍未形成可提交的状态提案", secondError);
            }
        }
    }

    @Override
    public Generated regenerateFreshNarrative(StoryPromptContext context, StoryModelRoute route,
                                              InvocationContext invocation, StoryPatch rejected,
                                              String problem) {
        if (context == null) throw new IllegalArgumentException("正文重新生成缺少命卷上下文");
        if (route == null) throw new IllegalArgumentException("正文重新生成缺少模型路由");
        List<StoryModelGateway.Result> attempts = new ArrayList<>();
        StoryModelGateway.Result regenerated = call(route, invocation, "STORY_TURN_FRESH_REGENERATION",
                SYSTEM_PROMPT, freshRegenerationPrompt(context, problem), 0.7);
        attempts.add(regenerated);
        try {
            return new Generated(parse(regenerated.text()), attempts);
        } catch (JsonProcessingException | IllegalArgumentException firstError) {
            StoryModelGateway.Result formatRepair = call(route, invocation,
                    "STORY_TURN_FRESH_REGENERATION_REPAIR", SYSTEM_PROMPT,
                    repairPrompt(regenerated.text(), firstError.getMessage()), 0.1);
            attempts.add(formatRepair);
            try {
                return new Generated(parse(formatRepair.text()), attempts);
            } catch (JsonProcessingException | IllegalArgumentException secondError) {
                throw new IllegalArgumentException("正文重新生成仍未形成可提交的状态提案", secondError);
            }
        }
    }

    @Override
    public Review review(StoryPromptContext context, StoryModelRoute route,
                         InvocationContext invocation, StoryPatch proposed) {
        if (context == null || proposed == null) throw new IllegalArgumentException("回合审阅缺少上下文或候选提案");
        if (route == null) throw new IllegalArgumentException("回合审阅缺少模型路由");
        Map<String, Object> payload = reviewPayload(context, proposed);
        StoryModelRoute reviewRoute = flashReviewRoute(route);
        StoryModelGateway.Result result;
        try {
            result = call(reviewRoute, invocation, "STORY_TURN_REVIEW_FLASH", REVIEW_PROMPT,
                    mapper.writeValueAsString(payload), 0.0);
            ReviewResponse response = tolerantMapper.readValue(rawJson(result.text()), ReviewResponse.class);
            List<String> problems = response.problems();
            return new Review(problems.isEmpty(), String.join("；", problems), List.of(result));
        } catch (JsonProcessingException error) {
            throw new IllegalArgumentException("回合审阅未返回有效结果", error);
        }
    }

    /**
     * 审阅只发送当前权威事实和候选变化，不重复发送旧正文、滚动摘要和人物主观记忆。
     * 这既保持审阅调用独立，也避免用更长上下文稀释玩家授权与事实冲突。
     */
    private Map<String, Object> reviewPayload(StoryPromptContext context, StoryPatch proposed) {
        Map<String, Object> facts = new LinkedHashMap<>();
        facts.put("worldClock", context.objective().worldClock());
        facts.put("protagonist", context.objective().protagonist());
        facts.put("currentInventory", context.objective().currentInventory());
        facts.put("entityIndex", context.objective().entityIndex());
        facts.put("worldFacts", context.objective().worldFacts());
        facts.put("pendingDecision", context.objective().pendingDecision());

        Map<String, Object> candidate = new LinkedHashMap<>();
        candidate.put("title", proposed.title());
        candidate.put("narrative", proposed.narrative());
        candidate.put("time", proposed.time());
        candidate.put("coreState", proposed.coreState());
        candidate.put("entities", proposed.entities());
        candidate.put("worldFacts", proposed.worldFacts());
        candidate.put("memories", proposed.memories());
        candidate.put("decisionCheckpoint", proposed.decisionCheckpoint());
        candidate.put("rollingSummary", proposed.rollingSummary());

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("facts", facts);
        payload.put("action", context.action());
        payload.put("proposed", candidate);
        return payload;
    }

    private String actionContractPayload(StoryPromptContext context) {
        Map<String, Object> facts = new LinkedHashMap<>();
        facts.put("worldClock", context.objective().worldClock());
        facts.put("coreState", context.objective().protagonist());
        facts.put("protagonist", context.objective().protagonist());
        facts.put("currentInventory", context.objective().currentInventory());
        facts.put("entityIndex", context.objective().entityIndex());
        facts.put("relatedEntities", context.objective().relatedEntities());
        facts.put("pendingDecision", context.objective().pendingDecision());
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("facts", facts);
        payload.put("action", context.action().playerAction());
        try {
            return mapper.writeValueAsString(payload);
        } catch (JsonProcessingException error) {
            throw new IllegalStateException("行动事实解析上下文无法序列化", error);
        }
    }

    /** DeepSeek 回合无论由 Flash 还是 Pro 生成，都交给一次全新的 Flash 请求独立审阅。 */
    private StoryModelRoute flashReviewRoute(StoryModelRoute generationRoute) {
        if (!"DEEPSEEK".equalsIgnoreCase(generationRoute.provider())) return generationRoute;
        return new StoryModelRoute(generationRoute.baseUrl(), generationRoute.apiKey(), generationRoute.provider(),
                generationRoute.adapterType(), flashReviewModel, generationRoute.credentialSource());
    }

    private StoryModelGateway.Result call(StoryModelRoute route, InvocationContext invocation, String operation,
                                          String systemPrompt, String userPrompt, double temperature) {
        long started = System.nanoTime();
        try {
            StoryModelGateway.Result result = gateway.generate(new StoryModelGateway.Request(
                    route.baseUrl(), route.apiKey(), route.provider(), route.adapterType(), route.model(),
                    systemPrompt, userPrompt, temperature));
            invocations.succeeded(invocation, operation, route, result);
            return result;
        } catch (RuntimeException failure) {
            invocations.failed(invocation, operation, route,
                    (System.nanoTime() - started) / 1_000_000L, failure);
            throw failure;
        }
    }

    private StoryPatch parse(String raw) throws JsonProcessingException {
        StoryPatch patch = tolerantMapper.readValue(rawJson(raw), StoryPatch.class);
        validator.validate(patch);
        return patch;
    }

    private String userPrompt(StoryPromptContext context) {
        Map<String, Object> sections = new LinkedHashMap<>();
        sections.put("systemConstraints", context.system());
        sections.put("objective", context.objective());
        sections.put("cognition", context.cognition());
        sections.put("memory", context.memory());
        sections.put("narrative", context.narrative());
        sections.put("action", context.action());
        try {
            return mapper.writeValueAsString(sections);
        } catch (JsonProcessingException error) {
            throw new IllegalStateException("回合推演上下文无法序列化", error);
        }
    }

    private String repairPrompt(String invalidOutput, String problem) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("validationProblem", problem == null ? "结构校验失败" : problem);
        payload.put("invalidOutput", invalidOutput == null ? "" : invalidOutput);
        try {
            return REPAIR_PROMPT + "\n" + mapper.writeValueAsString(payload);
        } catch (JsonProcessingException error) {
            throw new IllegalStateException("格式修复请求无法序列化", error);
        }
    }

    private String semanticRepairPrompt(StoryPromptContext context, StoryPatch rejected, String problem) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("validationProblem", problem == null ? "状态提案违反事实边界" : problem);
        payload.put("objective", context.objective());
        payload.put("action", context.action());
        payload.put("rejectedProposal", rejected);
        try {
            return SEMANTIC_REPAIR_PROMPT + "\n" + mapper.writeValueAsString(payload);
        } catch (JsonProcessingException error) {
            throw new IllegalStateException("语义修复请求无法序列化", error);
        }
    }

    private String freshRegenerationPrompt(StoryPromptContext context, String problem) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("validationProblem", problem == null ? "候选正文重复" : problem);
        payload.put("systemConstraints", context.system());
        payload.put("objective", context.objective());
        payload.put("cognition", context.cognition());
        payload.put("memory", context.memory());
        payload.put("action", context.action());
        try {
            return FRESH_REGENERATION_PROMPT + "\n" + mapper.writeValueAsString(payload);
        } catch (JsonProcessingException error) {
            throw new IllegalStateException("正文重新生成请求无法序列化", error);
        }
    }

    private String rawJson(String raw) {
        if (raw == null) return "{}";
        String value = raw.strip();
        if (value.startsWith("```")) {
            int firstLine = value.indexOf('\n');
            int lastFence = value.lastIndexOf("```");
            if (firstLine > 0 && lastFence > firstLine) value = value.substring(firstLine + 1, lastFence).strip();
        }
        int start = value.indexOf('{');
        int end = value.lastIndexOf('}');
        return start >= 0 && end > start ? value.substring(start, end + 1) : value;
    }

    private record ReviewResponse(ReviewCheck reference, ReviewCheck inventory, ReviewCheck authorization,
                                  ReviewCheck decision, ReviewCheck time, ReviewCheck state) {
        private List<String> problems() {
            List<String> values = new ArrayList<>();
            collect(values, "实体引用", reference);
            collect(values, "物品", inventory);
            collect(values, "玩家授权", authorization);
            collect(values, "玩家决定", decision);
            collect(values, "时间", time);
            collect(values, "状态", state);
            return List.copyOf(values);
        }

        private void collect(List<String> values, String label, ReviewCheck check) {
            if (check == null || check.passed() == null) {
                throw new IllegalArgumentException("回合审阅缺少“" + label + "”检查结果");
            }
            if (check.passed()) return;
            String detail = check.problem() == null ? "" : check.problem().strip();
            values.add(label + "：" + (detail.isBlank() ? "审阅未通过" : detail));
        }
    }

    private record ReviewCheck(Boolean passed, String problem) {}
}
