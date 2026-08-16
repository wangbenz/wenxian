package com.wenxian.game.story.agent;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.json.JsonReadFeature;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.wenxian.game.story.domain.StoryPatch;
import com.wenxian.game.story.domain.StoryActionContract;
import com.wenxian.game.story.domain.StoryDecisionEnvelope;
import com.wenxian.game.story.domain.StoryPatchNormalizer;
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
            1. objective 是当前唯一客观事实；其中 initialContract 是玩家创建命卷时确认的根源，sourceExcerpts 是
               本轮可以追溯的开场、上一回合或已点击系统选项。cognition 是人物所知或所信；memory、narrative 只是叙事材料；
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
               resolution=ESTABLISHED 表示对象已由 evidenceRefs 指向的根源、已提交上一回或当前系统选项确立，虽然尚无
               永久实体 ID，仍可被本轮正常交谈、追踪、攻击或使用。若本轮使其产生需要跨回合保持的生死、关系、能力、
               数量或归属变化，必须用 canonicalName 创建唯一长期实体；普通路人或一次性环境细节无需强行建档。
               尤其当 factContract.itemActionDetected=true 且物品为 ESTABLISHED 时，必须 CREATE 同 canonicalName 的唯一
               ITEM，并记录变化后的 quantity 与 custody；否则根源文本会被后续回合误读成仍然持有。
               resolution=UNRESOLVED 且 actionMode=QUERY/ATTEMPT 表示玩家正在探索尚未确认的可能，不能把它当作现有事实，
               但也不得仅因未知而拒绝行动；由本轮剧情裁定能否找到或实现，并只在结果真正成立后创建事实。
               不得把玩家提到的新对象替换成名称或含义不同的历史实体。
               每个 CREATE 实体都必须填写非空的 name、status 和 summary；其中 summary 只记录需要跨回合保持的
               当前事实，不要复制整段正文。UPDATE 未变化的字段才可以返回 null。
               referenceKey 仅允许 CREATE 使用，并且只在晋升 factContract 的 NEW/ESTABLISHED 回合内引用时填写；
               UPDATE/HIDE/RESTORE 必须返回空字符串，不能复制已有引用的 referenceKey。
               UPDATE 已有实体时，普通称谓变化、地点细节或模型措辞不得改变 name。只有玩家明确为该实体改名，或本轮
               已完成剧情明确形成新称呼时，才填写新 name 和 nameChange={explicit:true,reason:"具体依据"}；稳定 ID 不变。
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
               factContract.agencyBoundary 是独立解析得到的玩家授权边界：authorizedActions 是唯一可由主角主动执行的
               行为；prohibitedActions 不得发生；unresolvedDecisions 必须继续留给玩家；requiredEndState 是正文结尾必须
               保持的位置或状态。不得给主角补写玩家未表达的动机、立场、回答、路线选择或下一步计划。
            4. 物品数量已知时，使用、出售、赠予或获得后必须返回准确的新总量；数量未知就保持 known=false、value=null，
               不得自动记为1。能力依次区分 KNOWN（已知其存在）、LEARNING（正在学习）、MASTERED（已经掌握）、
               TEMPORARILY_BLOCKED（仍掌握但暂时不能施展）和 LOST（已经失去）；UNKNOWN 只兼容旧数据，不能用于新增能力。
               从玉简、秘籍等物品中学到能力时，来源物品仍是 ITEM，功法或能力必须使用独立 ABILITY 实体；可在能力
               attributes 中记录 sourceEntityId、sourceEntityType 和 sourceReason，不得复用来源物品的稳定 ID。
               字段必须与实体类型对应：ITEM 才能填写 quantity、inPossession、custody；ABILITY 才能填写 abilityState；
               CHARACTER 才能填写 lifecycle、relationship；其他类型的这些专用字段必须为 null。
            5. 人物为 DEAD 时不得无依据复活。若 action 明确允许修订且有充分依据，生命周期变化必须写 changeReason。
               人物关系使用 relationship 单独提交；关系名称仍可按剧情自由描述，但已有关系发生变化时必须写
               changeReason，且不得只改 attributes.relationship 来绕过关系审计。
               人物 status 与 summary 只能描述人物自身近况，不得重复记录某件物品由谁保管。物品保管的唯一客观来源
               是 ITEM.custody；即使旧人物文本曾提到保管物品，也不能用它覆盖或补写 custody。
               当 factContract.itemTransitions.target.holderRef 为 contract-ref:rN 时，表示该人物来自初始设定、来源片段
               或本轮新出现，尚无正式 ID。必须同时 CREATE 唯一 CHARACTER，令其 referenceKey=rN，并让物品补丁继续
               使用同一个 contract-ref:rN；不得改绑为 entityIndex 中另一个已有角色。服务端提交时会统一换成正式 ID。
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
                "referenceKey":"仅当本实体对应 factContract 中的回合内引用时填写，否则为空",
                "quantity":{"known":true,"value":1},"inPossession":true,
                "custody":{"holderType":"PLAYER|CHARACTER|LOCATION|NONE|UNKNOWN","holderRef":null},
                "abilityState":"KNOWN|LEARNING|MASTERED|TEMPORARILY_BLOCKED|LOST|UNKNOWN",
                "lifecycle":{"state":"ACTIVE|MISSING|DORMANT|DEAD|UNKNOWN","changeReason":null},
                "relationship":{"label":"当前关系名称","changeReason":"本轮关系改变依据或null"},
                "nameChange":{"explicit":false,"reason":"仅在玩家明确改名或本轮明确形成新称呼时填写"}}],
              "worldFacts":[{"operation":"EXPAND|TRANSFORM|REVISE","factId":null,"statement":"","reason":null}],
              "knowledge":[{"subjectRef":"","topicRef":"","summary":"","evidenceRefs":[]}],
              "memories":[{"memoryType":"EVENT|PROMISE|CLUE|RELATIONSHIP","title":"","summary":"","importance":"KEY|NORMAL","evidenceRefs":[]}],
              "decisionCheckpoint":{"required":false,"prompt":null},
              "choices":[{"title":"","description":""}],"rollingSummary":""
            }
            """;

    private static final String REPAIR_PROMPT = """
            下面是你刚才返回但未通过 JSON/结构校验的结果。只修复格式和契约字段，不新增剧情、事实或决定，
            不需要重新读取完整上下文。CREATE 必须同时有非空 name、status、summary；只为了氛围添加且无法
            满足契约的可选新实体应直接从列表删除，不得为修一个错误又伪造保管者或稳定 ID。
            只返回修复后的单个 JSON 对象，不要 Markdown。
            """;

    private static final String OUTCOME_PLAN_PROMPT = """
            你是《问仙录》的回合结果规划 Agent。你不写供玩家阅读的完整剧情，只提出本轮结束时需要落定的
            最小事实计划。objective 是回合开始前的唯一客观事实，action.factContract 是已经通过服务端校验的
            玩家授权边界。计划仍不是正式事实，后续还会经过 Reducer 预校验和事务提交。

            目标不是把开放剧情写成规则游戏，而是先回答四件事：玩家明确做的行动产生了什么直接结果、实际经过
            多久、结束时在哪里、哪些长期事实确实改变。尚未发生的可能继续开放，不要提前规划后续章节。

            严格规则：
            1. 只执行 action 授权的行动及直接后果。新的重大不可逆决定必须停下并设置
               decisionCheckpoint.required=true；不得替玩家决定加入、结盟、立誓、处决、放弃或下一步路线。
            2. outcome 必须先明确本轮授权行动是 SUCCEEDED（目标达成）、PARTIAL（形成可延续进展）还是 FAILED
               （本轮没有形成目标结果），summary 只写直接结果。narrative 字段不是正文，只写一段不超过180字的
               outcomeSummary，准确列出已完成结果和停止点；
               title 是结果计划短标题；choices 必须返回空数组。rollingSummary 只更新已经发生的长期摘要。
            3. 玩家明确时间以 action.semanticDuration、explicitDurationMinutes 和 expectedEndClock 为准。
               完整执行时 elapsedMinutes 必须等于 explicitDurationMinutes；提前中断才使用 INTERRUPTED 并写原因。
               “明日、半日、一个月、三年”等是行动授权，不得因为模型偏好缩成普通一回合。
            4. coreState.location 必须是本轮结束位置，而不是开场地点。重要同行人物若随主角移动或被明确安置，
               使用其稳定 entityId 返回 CHARACTER UPDATE，并在 attributes.lastKnownLocation 记录结束位置；
               不要因为只更新地点而重写人物姓名、关系或生命周期。
            5. 已有实体只用 objective.entityIndex 的稳定 entityId；新增长期实体用 CREATE。物品数量、保管者，
               人物生命周期和关系，能力掌握状态，都必须与 action.factContract 和直接结果一致。不得从旧正文、
               摘要或愿望恢复已经失去的物品、死亡人物或未掌握能力。
               referenceKey 只能用于本回合 CREATE 的新实体；UPDATE/HIDE/RESTORE 既有实体必须使用稳定
               entityId 且 referenceKey=""。
            6. 探索未知、尝试突破或挑战强敌可以成功、失败或中断；不要因为结果尚未知就拒绝。玩家输入表达的是
               尝试时，结果由本计划裁定，但爽点、机缘和意外只要不违背已确认事实都允许发生。
               factContract 中 resultTarget=true 的 ABILITY 是玩家本轮试图获得或推进的能力：SUCCEEDED 必须用相同
               referenceKey 创建/更新为 MASTERED；PARTIAL 必须创建/更新为 KNOWN 或 LEARNING；FAILED 不得创建该能力。
               来源玉简、秘籍或师承仍保持自己的 ITEM/CHARACTER 类型，不能代替独立能力实体。
            7. 只保存跨回合确有价值的实体、事实、记忆和关系。普通路人、景物构件、短暂情绪和招式过程不建档。
               新人物、物品、能力的专用字段规则与 objective 中的类型约束保持一致。
               所有 CREATE 必须同时填写非空 name、status、summary。新 ITEM 只在确有跨回合意义时建档；
               由玩家带走就使用 custody.holderType=PLAYER。只有 objective 已有稳定地点 ID，或本回合同时以
               referenceKey 创建了保管者时，才能使用 LOCATION/CHARACTER；普通线索可用 memory 记录，
               不要把每一张纸条、痕迹或场景构件创建成 ITEM。

            只返回一个符合 StoryPatch 结构的 JSON，不要 Markdown 或说明：
            {
              "title":"结果计划短标题","narrative":"不超过180字的结果摘要",
              "outcome":{"resolution":"SUCCEEDED|PARTIAL|FAILED","summary":"本轮直接结果"},
              "time":{"elapsedMinutes":0,"resolution":"COMPLETE|INTERRUPTED","interruptionReason":null},
              "coreState":{"location":null,"condition":null,"realm":null,"lifeGoal":null},
              "entities":[{"operation":"CREATE|UPDATE|HIDE|RESTORE","entityId":null,
                "entityType":"ITEM|ABILITY|CHARACTER|LANDMARK或既有类型","name":null,"status":null,
                "summary":null,"attributes":null,
                "quantity":{"known":true,"value":1},"inPossession":null,
                "custody":{"holderType":"PLAYER|CHARACTER|LOCATION|NONE|UNKNOWN","holderRef":null},
                "abilityState":"KNOWN|LEARNING|MASTERED|TEMPORARILY_BLOCKED|LOST或null",
                "lifecycle":{"state":"ACTIVE|MISSING|DORMANT|DEAD|UNKNOWN","changeReason":null},
                "relationship":{"label":"当前关系","changeReason":null},
                "nameChange":{"explicit":true,"reason":"明确改名依据"},"referenceKey":""}],
              "worldFacts":[{"operation":"EXPAND|TRANSFORM|REVISE","factId":null,
                "statement":"长期事实","reason":null}],
              "knowledge":[{"subjectRef":"稳定主体ID","topicRef":"稳定对象ID",
                "summary":"认知摘要","evidenceRefs":[]}],
              "memories":[{"memoryType":"EVENT|PROMISE|CLUE|RELATIONSHIP","title":"",
                "summary":"","importance":"KEY|NORMAL","evidenceRefs":[]}],
              "decisionCheckpoint":{"required":false,"prompt":null},"choices":[],"rollingSummary":""
            }
            所有嵌套字段必须使用系统契约中的对象结构，不能用自然语言字符串代替。
            """;

    private static final String OUTCOME_PLAN_REPAIR_PROMPT = """
            下面的最小结果计划没有通过结构、稳定引用、玩家授权或 Reducer 事实边界。只修复计划本身，
            不写完整正文，不扩展后续剧情，不改变玩家行动。保留没有问题的直接结果；若问题只是可选字段跨类型，
            删除该可选字段。必须保留或修正 outcome，并确保 resultTarget 能力的掌握状态与 outcome 一致。
            只返回修复后的完整 StoryPatch JSON。
            """;

    private static final String NARRATIVE_RENDER_PROMPT = """
            你是《问仙录》的回合叙事 Agent。validatedOutcome 是本轮已经通过服务端预校验的唯一结果计划；
            你只能把它演绎成第二人称“你”的玩家可见剧情，无权增删时间、地点、物品、数量、人物生死与关系、
            能力、境界、世界事实或重大决定。

            规则：
            1. 正面写出 action 的执行过程，并完整覆盖 validatedOutcome.outcome 中的成功、部分成功或失败结论、
               实际时间尺度和最终停止位置。不得把 FAILED 写成成功，不得把 PARTIAL 写成已经完全掌握。
            2. 可以自由补充氛围、感官、对话、阻力和不需要长期保存的细节，但不能制造计划外的长期事实。
            3. 不得替玩家执行 action 之后的新行动，不得补写玩家未表达的动机、承诺、路线和下一步计划。
               到新的重大决定前停止，把选择交给玩家。
            4. 选项是尚未发生的建议，不得当作本轮已完成事实。提供2至4个方向不同、表达清楚的行动；
               若存在 required 决定，选项围绕该决定。不得重复上一回正文。
            5. 篇幅服从本轮内容：普通回合保持沉浸感，短促动作可以更短，长期闭关可以跨场景概述；
               不用未授权行动凑字数。rollingSummary 只能概括本轮已经发生且后续需要承接的内容。

            只返回以下 JSON，不要返回事实补丁、Markdown 或解释：
            {"title":"","narrative":"","choices":[{"title":"","description":""}],"rollingSummary":""}
            """;

    private static final String NARRATIVE_REPAIR_PROMPT = """
            下面的正文没有完整覆盖 validatedOutcome，或加入了计划外长期事实、替玩家作出决定、越过停止边界。
            validatedOutcome 不得修改。只重写 title、narrative、choices、rollingSummary，使正文严格演绎既定结果，
            同时保留自然、沉浸和爽文空间。只返回表现层 JSON，不要事实补丁或解释。
            """;

    private static final String OUTCOME_COVERAGE_REVIEW_PROMPT = """
            你是独立的可信回合覆盖审阅 Agent。acceptedOutcome 是已经预校验的唯一结果计划，rendered 是玩家将看到的
            表现层正文。你不续写、不修正事实，只检查正文是否忠实演绎计划。

            分别检查：
            1. coverage：正文是否明确覆盖玩家行动、acceptedOutcome.outcome 的明确结果、时间尺度、最终地点和计划中的重要实体变化；
            2. contradiction：正文是否与计划或回合开始前权威事实矛盾；尤其检查是否把 FAILED 写成成功、把
               PARTIAL 写成完全掌握，或声称学会了 entities 中没有对应能力状态的功法；
            3. extraDurableFacts：正文是否新增计划中没有的物品取得/失去、数量、人物生死与关系、能力、境界、
               世界规则、长期承诺等跨回合事实；纯氛围和一次性细节不算；
            4. agency：正文是否替玩家执行计划之后的新行动、作出重大决定、补写动机，或越过 required 停止点。

            少量钟点差异、文风、难度、战力平衡和普通场景细节不得作为拒绝理由。每项必须返回 passed 和 problem。
            repairTarget 必须指出问题来源：全部通过为 NONE；acceptedOutcome 本身遗漏行动结果、结束地点、语义时间或包含
            越权事实时为 PLAN；结果计划正确但 rendered 漏写、矛盾、增加事实或越权时为 NARRATIVE。不能让正文修复
            掩盖结构计划缺项，也不能因为纯正文措辞问题重新改变既定事实。
            只返回 JSON：
            {"coverage":{"passed":true,"problem":""},
             "contradiction":{"passed":true,"problem":""},
             "extraDurableFacts":{"passed":true,"problem":""},
             "agency":{"passed":true,"problem":""},"repairTarget":"NONE|PLAN|NARRATIVE"}
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
            上一份候选正文未通过玩家授权、决定边界或正文新鲜度审阅，不能在原文上局部修补。
            请从 objective 的当前状态与 action 重新构思一个独立新回合。不得回忆、复述或改写上一回合正文；
            必须正面展示 action 引发的对话、反应或直接结果，并在玩家指定的停止边界结束。
            action.factContract 与 objective 仍是唯一权威事实；不得为了让正文显得不同而新增未授权行动。
            返回符合系统完整 JSON 契约的单个对象，不要 Markdown、解释或额外文字。
            """;

    private static final String FACT_PROJECTION_PROMPT = """
            你是《问仙录》的可信事实投影 Agent。你不续写、润色或改写剧情，只从 narrativeDraft 已经完成的
            本轮过程与结果中提取需要跨回合保持的变化。facts 是回合开始前的权威事实，action 是玩家授权与行动契约。

            严格规则：
            1. title、narrative 和 choices 必须原样返回；服务端还会用草稿覆盖这些字段，禁止借投影改变剧情。
            2. 只保存 narrativeDraft 已经明确发生的结果。愿望、尝试、猜测、NPC 说法和推荐选项不是既成事实。
            3. 既有实体只能复制 facts.entityIndex 中的稳定 entityId 与 entityType。名称措辞不同不能创建同名实体；
               UPDATE 普通称谓变化时 name=null。只有剧情明确完成改名才返回新 name，且
               nameChange={"explicit":true,"reason":"改名依据"}。
            4. 新增长期实体使用 CREATE、entityId=null，并且 name、status、summary 三项都必须是非空字符串；
               UPDATE/HIDE/RESTORE 才可以把未变化的字段设为 null。普通路人、场景装饰和一次性细节不建档。
               action.factContract 的 NEW/ESTABLISHED 引用需要晋升时，使用相同 canonicalName 与 referenceKey。
               referenceKey 只允许上述 CREATE 使用；UPDATE/HIDE/RESTORE 必须返回空字符串。
            5. ITEM 必须记录实际 quantity、custody 与 inPossession；数量未知保持 known=false、value=null。
               custody 只能为 null 或对象 {"holderType":"PLAYER|CHARACTER|LOCATION|NONE|UNKNOWN","holderRef":null}，
               禁止返回“主角”“玩家”“某人”等字符串。CHARACTER、ABILITY、LANDMARK 的 custody 必须为 null。
               能力必须是独立 ABILITY，状态只允许 KNOWN、LEARNING、MASTERED、TEMPORARILY_BLOCKED、LOST；
               来源物品仍是 ITEM，不能复用物品 ID。可在能力 attributes 记录 sourceEntityId/sourceEntityType/sourceReason。
               专用字段不可跨类型：ITEM 不能填写 abilityState、lifecycle、relationship；ABILITY 不能填写物品保管或人物关系；
               CHARACTER 才能填写 lifecycle、relationship；LANDMARK 的上述专用字段均为 null。
            6. 人物生死使用 lifecycle，关系使用 relationship；已经死亡者没有明确复活事件时保持死亡。
               关系或生命周期改变必须保存具体 changeReason。
            7. time 只记录草稿实际经过时长。action.semanticDuration 是玩家授权的语义尺度，优先保持场景、时段、日、月、年
               的量级；action.explicitDurationMinutes 只是服务端兼容旧投影的换算值，不是供你重新推理的精密时钟。
               完整执行时兼容分钟必须原样复制，提前结束使用 INTERRUPTED 并说明原因，不得因分钟级误差改写剧情。
               coreState 只返回真正变化的字段，未变化返回 null。
            8. worldFacts：新增空白设定用 EXPAND；普通世界发展用 TRANSFORM 并引用稳定 factId；玩家明确修订用 REVISE、
               引用 factId 并记录 reason。世界模板是软起点，不能覆盖该命卷已经确认的经历。
            9. memories 和 rollingSummary 只概括已发生的经历，不得写入玩家尚未作出的决定、动机或下一步计划。
               decisionCheckpoint 只在剧情确实停在需要玩家决定的位置时 required=true。

            只返回单个 JSON，不要 Markdown、说明或额外文字：
            {
              "title":"原样复制","narrative":"原样复制",
              "time":{"elapsedMinutes":0,"resolution":"COMPLETE|INTERRUPTED","interruptionReason":null},
              "coreState":{"location":null,"condition":null,"realm":null,"lifeGoal":null},
              "entities":[{"operation":"CREATE|UPDATE|HIDE|RESTORE","entityId":null,
                "entityType":"ITEM|ABILITY|CHARACTER|LANDMARK或既有类型",
                "name":"CREATE时必填，更新时未变化可为null","status":"CREATE时必填，更新时未变化可为null",
                "summary":"CREATE时必填，更新时未变化可为null","attributes":null,
                "quantity":{"known":true,"value":1},"inPossession":null,
                "custody":{"holderType":"PLAYER|CHARACTER|LOCATION|NONE|UNKNOWN","holderRef":null},
                "abilityState":"KNOWN|LEARNING|MASTERED|TEMPORARILY_BLOCKED|LOST或null",
                "lifecycle":{"state":"ACTIVE|MISSING|DORMANT|DEAD|UNKNOWN","changeReason":null},
                "relationship":{"label":"关系名称","changeReason":null},
                "nameChange":{"explicit":true,"reason":"明确改名依据"},"referenceKey":""}],
              "worldFacts":[{"operation":"EXPAND|TRANSFORM|REVISE","factId":null,
                "statement":"需要长期保持的世界事实","reason":null}],
              "knowledge":[{"subjectRef":"认知主体稳定ID","topicRef":"认知对象稳定ID",
                "summary":"该人物知道或相信的内容","evidenceRefs":[]}],
              "memories":[{"memoryType":"EVENT|PROMISE|CLUE|RELATIONSHIP","title":"记忆标题",
                "summary":"已发生经历摘要","importance":"KEY|NORMAL","evidenceRefs":[]}],
              "decisionCheckpoint":{"required":false,"prompt":null},
              "choices":[{"title":"原样复制","description":"原样复制"}],
              "rollingSummary":"只概括已经发生并需要延续的内容"
            }
            上述嵌套字段没有变化时返回 null 或 []，但一旦返回就必须保持对象结构；quantity 禁止直接返回数字，
            其他嵌套结构也禁止简写：不得把 custody、quantity、
            lifecycle、relationship、nameChange、worldFacts、knowledge 或 memories 简写成自然语言字符串。
            """;

    private static final String REVIEW_PROMPT = """
            你是《问仙录》的独立 Flash 回合审阅 Agent。你只审核候选，不续写剧情，也不替候选补充解释。
            facts 是提交前的权威事实，action 是玩家本轮唯一授权，proposed 是尚未提交的候选结果。

            必须逐项完成四类重大事实检查，不能因为发现第一处问题就跳过其余类别：
            1. reference：action 中明确提到的物品、人物、能力或需要跨回合追踪的场景地标是否都被
               factContract.references 覆盖；玩家原话、标准名称、
               EXISTING/ESTABLISHED/NEW/UNRESOLVED 解析、证据引用和稳定 ID 是否对应，是否把新瓦片之类对象误绑成历史石子、玉简等其他实体。
               水井、屋舍、石桥、山门等无法随身携带的场景对象必须是 LANDMARK 或不持久化，不得当作 ITEM、
               不得进入 currentInventory；只有玩家取得或可被人物、地点保管的可转移对象才是 ITEM。
            2. inventory：候选是否使用、持有、赠送、出售或获得了 facts 未支持的物品；数量和处置是否一致。
            3. time：语义时间尺度、行动是否完整或中断与 action 的时间契约是否一致。分钟是内部兼容投影，
               不得因少量分钟差异拒绝；日、月、年量级或完整/中断结果冲突才拒绝。
            4. state：地点、境界、能力掌握、人物生死、待确认决定及 proposed 的结构化变化是否前后一致。
               ITEM.custody 是物品保管的唯一客观来源。人物 status/summary 若声称持有、归还或失去某物，但与 custody
               不一致，必须失败；也不得要求人物 status/summary 重复保存 custody 已经表达的事实。
               同一处场景及其普通组成部分若被拆成多个重复 LANDMARK，也必须判定失败；普通观察最多形成一个
               有长期引用价值的场景实体。
               若候选仅因观察而把屋檐、门框、墙角、路面等现有地点组成部分新建为 LANDMARK，且玩家没有明确
               要求标记、该对象也没有成为可独立前往或影响后续事件的地点，必须判定失败。

            proposed.coreState 等结构是“变化补丁”，字段为 null 表示保持 facts 原值，不得据此判定缺失或冲突。
            facts.currentInventory 只证明回合开始时的持有状态，不代表本轮永远不能获得新物品；
            actionMode=ATTEMPT 且 UNRESOLVED 的对象允许由本轮实际结果确立。应检查发现、取得或失去是否由正文实际发生、
            是否属于 authorizedActions 的直接结果，以及结构补丁是否记录了最终状态，不能仅因对象起初不在 facts 就拒绝。
            玩家决定由后续专用审阅负责，本阶段不要因主角查看手掌、伤口、行动结果或短暂思考拒绝事实提案。
            不要评价文风、篇幅、难度或战力平衡。
            每项都必须返回 passed 与 problem。通过时 problem 为空；不通过时准确列出该类别中的冲突。
            只返回以下 JSON，不要 Markdown 或其他文字：
            {
              "reference":{"passed":true,"problem":""},
              "inventory":{"passed":true,"problem":""},
              "time":{"passed":true,"problem":""},
              "state":{"passed":true,"problem":""}
            }
            """;

    private static final String ACTION_CONTRACT_PROMPT = """
            你是《问仙录》的行动事实解析 Agent。你不续写剧情、不判定胜负，只把玩家本轮自然语言行动解析成
            可由服务端校验的稳定实体引用和前置条件。facts 是本轮开始前的唯一权威事实；initialContract、
            sourceExcerpts 和 selectedChoice 都是可引用来源，但它们不会自动让其中每个名词成为永久实体。

            规则：
            0. facts.coreState 包含玩家本轮开始时已确认的地点与状态。action 只是观察当前地点、
               建筑、水井、道路或景物时，不得因 entityIndex 中没有单独实体就报 UNRESOLVED，也不得把它创建成 ITEM。
               只有确实需要在以后回合稳定引用该场景对象时才返回 NEW LANDMARK；普通观察可不返回 reference。
               屋檐、门框、墙角、路面等当前地点或已有 LANDMARK 的普通组成部分，玩家仅观察时不得返回 NEW；
               只有玩家明确要求长期标记，或它已经成为可独立前往、交互并影响后续事件的地点时才可返回 NEW LANDMARK。
            1. action 中每一个作为独立剧情对象被查看、使用、取得或处置的物品、人物或能力都必须放入 references，
               查看但不消耗也不能省略。mention 必须保留玩家原话中的对象短语，canonicalName 是解析后的标准名称。
               每条 reference 必须分配本次契约内唯一的 referenceKey（r1、r2……），只用于本回合交叉引用，
               不得把它当成数据库实体 ID。
               “放入行囊、拿在手中、留在屋内/地面”等语句里的容器、身体部位和普通方位只表达保管关系，
               不是新剧情实体；除非 facts 已有稳定实体，或玩家明确把它本身作为独立对象取得/处置，否则不要为
               行囊、手、衣袖、地面、屋内等创建 reference，更不能标成 NEW ITEM。
               需要跨回合追踪的水井、屋舍、石桥、山门等场景对象使用 LANDMARK；不需要跨回合追踪的普通环境细节可以不返回 reference。
               LANDMARK 不是物品，expectedCustody 必须为 null，不得进入 itemTransitions；只有可被取得、携带、消耗或转交的对象才是 ITEM。
               persistence=DURABLE 表示已有长期实体；TRANSIENT 表示只服务本轮的普通路人、摊主、溪水、街道组成部分，
               不需要稳定 ID，也不应进入人物或地标档案；PROMOTE_ON_CHANGE 表示当前尚无长期身份，但发生物品保管、
               生死、关系或能力掌握等长期变化后需要晋升建档。TRANSIENT 不得声明 expectedCustody 或 resultTarget。
               resolution 与 persistence 是两个独立字段：resolution 只能是 EXISTING、ESTABLISHED、NEW、UNRESOLVED，
               绝不能把 TRANSIENT、DURABLE 或 PROMOTE_ON_CHANGE 填入 resolution。
               例如本轮第一次出现、无需长期保存的普通摊主，应写 resolution=NEW、persistence=TRANSIENT；
               不是 resolution=TRANSIENT。
               当前地点及其普通组成部分不需要作为稳定实体返回；如果模型仍需保留引用，必须使用 TRANSIENT。
            2. resolution=EXISTING 仅用于 facts.entityIndex 中能唯一对应的实体，必须复制稳定 entityId、entityType 和名称；
               resolution=ESTABLISHED 用于对象明确来自 facts.initialContract、facts.sourceExcerpts 或 selectedChoice，
               但尚无稳定实体 ID；entityId=null，并在 evidenceRefs 中逐项复制对应 sourceExcerpts.ref。不得只凭相似名字引用来源。
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
               PLAYER 的 holderRef=null；CHARACTER 如果是 EXISTING 人物，holderRef 使用 facts 中稳定 ID；如果是
               ESTABLISHED 或 NEW 人物，holderRef=null，并把该人物 referenceKey 写入 targetReferenceKey；LOCATION
               可使用当前地点文字；NONE 表示消耗或毁去。不得把目标人物绑定为 references 中没有提及的其他人物。
               已知堆叠物品只消耗或转移一部分、剩余部分仍在玩家手中时，target 必须是 PLAYER，quantity 填本轮处置数量；
               不要因为其中一枚被消耗就把整个物品实体标成 NONE。
            6. 先设置 actionMode：QUERY 表示询问、查看或调查；ATTEMPT 表示寻找、战斗、修炼、许愿、取得等尚待裁定的尝试；
               ASSERT_FACT 表示玩家把“已经拥有、已经认识、此前已经发生”等内容当作行动前提；AMEND_FACT 表示玩家要求
               直接改写已确认事实。QUERY/ATTEMPT 遇到未知对象时可以使用 UNRESOLVED 且 conflict.present=false，交给剧情
               裁定结果；不能因为目标未知、行动困难或境界不足而拒绝。ASSERT_FACT 的前提没有 EXISTING 或 ESTABLISHED
               依据时必须 conflict.present=true。AMEND_FACT 必须提示改用修正命卷。
               再设置 actionScope：DO_NOW 表示当前行动；ADVANCE_FUTURE 表示玩家明确授权“数日后、闭关三年”等未来推进；
               ASSERT_PAST 表示玩家声称过去已经发生；REVISE_WORLD 表示明确调整当前世界设定；CORRECT_FACT 表示修正已经落定
               的命卷事实。ADVANCE_FUTURE 不能仅因当前日期尚早而报冲突；action.explicitDurationMinutes 与
               action.expectedEndClock 非空时是服务端已解析的授权时间。类似“闭关三年突破元婴”中，三年是时间授权，
               突破元婴只是待剧情裁定的目标，不是既成事实。
               resultTarget=true 只标记玩家本轮希望取得、学会或推进的长期目标。玩家“学习/参悟/自创某能力”时，
               除了来源玉简、秘籍或人物引用，还必须为能力本身单独返回 ABILITY 引用，设置 resultTarget=true、
               persistence=PROMOTE_ON_CHANGE；不得因为尚未掌握而报冲突。直接“施展已经掌握的能力”只是依赖既有能力，
               resultTarget=false；没有可信记录时按事实前提冲突处理。
            7. 如果 action 明确声称“之前已经赠送/卖出/取得/杀死”等事实，或者依赖“师父、道侣、盟友、仇敌”等
               已经成立的人物关系，但 facts 与该前提冲突，
               conflict.present=true，reason 说明当前事实，correction 给出不替玩家决定的可行改写。
               同理，玩家把已移除、已消耗、已赠予或留在地点的物品说成仍在行囊时必须报冲突，不能继续生成剧情，
               也不能把 expectedFrom 改写成 facts 中的实际保管者来绕过校验。
            8. 不因为行动困难、境界不足或结果未知而报冲突；这些属于后续剧情。只检查已经发生的客观前提。
               characterPreconditions 只能复制 facts 中非空的 lifecycle 与 relationship.label；facts 为 UNKNOWN 时可填 UNKNOWN，
               facts 没有对应字段时必须填 null，不得根据人物 status 或 summary 猜成 ACTIVE、DEAD 或某种关系。
            9. stopBoundary 只概括玩家明确要求的停止点；没有则为空。
            10. agencyBoundary 必须完整解释玩家授权，不判断行动能否成功：
               authorizedActions 逐项列出主角本轮可以主动执行的行为；prohibitedActions 列出玩家明确禁止或由“只/仅”
               排除的行为；unresolvedDecisions 列出玩家明确要求保留、或行动完成后仍应由玩家决定的问题；
               requiredEndState 写玩家要求正文结束时保持的位置或状态。不得把模型认为合理的动机或下一步补进去。

            只返回 JSON，不要 Markdown：
            {
              "intentSummary":"",
              "actionMode":"QUERY|ATTEMPT|ASSERT_FACT|AMEND_FACT",
              "actionScope":"DO_NOW|ADVANCE_FUTURE|ASSERT_PAST|REVISE_WORLD|CORRECT_FACT",
              "itemActionDetected":false,
              "references":[{
                "mention":"玩家原话中的对象短语",
                "resolution":"EXISTING|ESTABLISHED|NEW|UNRESOLVED",
                "entityId":null,
                "entityType":"ITEM|CHARACTER|ABILITY|LANDMARK",
                "canonicalName":"标准名称",
                "role":"SOURCE|TARGET|MENTION",
                "expectedCustody":{"holderType":"PLAYER|CHARACTER|LOCATION|NONE|UNKNOWN","holderRef":null},
                "evidenceRefs":[],
                "referenceKey":"r1",
                "persistence":"DURABLE|TRANSIENT|PROMOTE_ON_CHANGE",
                "resultTarget":false
              }],
              "itemTransitions":[{
                "itemId":"",
                "expectedFrom":{"holderType":"PLAYER|CHARACTER|LOCATION|NONE|UNKNOWN","holderRef":null},
                "target":{"holderType":"PLAYER|CHARACTER|LOCATION|NONE|UNKNOWN","holderRef":null},
                "quantity":null,
                "targetReferenceKey":"目标为ESTABLISHED或NEW人物时填写对应referenceKey，否则为空"
              }],
              "characterPreconditions":[{"characterId":"","requiredLifecycle":"ACTIVE|MISSING|DORMANT|DEAD|UNKNOWN","requiredRelationship":null}],
              "agencyBoundary":{"authorizedActions":[],"prohibitedActions":[],"unresolvedDecisions":[],"requiredEndState":""},
              "stopBoundary":"",
              "conflict":{"present":false,"reason":"","correction":""}
            }
            """;

    private static final String ACTION_CONTRACT_REPAIR_PROMPT = """
            下面的行动事实契约存在结构性引用错误。请根据同一份 facts、action 与 selectedChoice 只修复契约，
            不续写剧情、不改变玩家行动，也不把事实冲突伪装成可执行。
            特别检查：玩家提到的人物是否来自 initialContract/sourceExcerpts；ESTABLISHED 是否复制了有效 evidenceRefs；
            每条 referenceKey 是否唯一；物品交给 ESTABLISHED/NEW 人物时，target.holderRef 必须为空并使用
            targetReferenceKey；不得猜测另一个已有角色的稳定 ID。普通摊主、路人、当前地点和一次性场景对象使用
            TRANSIENT，不得因为缺少稳定 ID 伪装成 EXISTING；学习能力时必须把能力本身标为非临时 resultTarget。
            如果 validationProblem 是物品起始保管状态冲突，必须重新阅读玩家原话：expectedCustody/expectedFrom 只表达
            行动开始前玩家实际依赖的保管者，赠送自己当前持有的物品时应为 PLAYER；不得为迎合 facts 把玩家明确声称的
            起始保管者改成当前记录。若玩家确实声称了与 facts 冲突的旧归属，保留该声明并设置 conflict.present=true。
            只返回符合行动事实解析完整 JSON 契约的单个对象，不要 Markdown 或解释。
            """;

    private static final String ACTION_CONTRACT_FORMAT_REPAIR_PROMPT = """
            上一次行动契约无法按给定 JSON 协议解析。只修正 JSON、枚举字段或字段归属，不改变玩家行动语义，
            不新增事实、不续写剧情。特别注意 resolution 只允许 EXISTING、ESTABLISHED、NEW、UNRESOLVED；
            persistence 才允许 DURABLE、TRANSIENT、PROMOTE_ON_CHANGE。返回完整行动契约 JSON，不要说明或 Markdown。
            """;

    private static final String AGENCY_REVIEW_PROMPT = """
            你是《问仙录》的玩家授权复核 Agent。你不续写剧情，只检查候选是否替玩家行动或决定。
            action.playerAction 是玩家原话；action.factContract.agencyBoundary 是行动解析 Agent 给出的授权边界；
            proposed 是待提交候选。

            必须先独立阅读 proposed.narrative、outcome、entities、worldFacts、memories、rollingSummary、
            decisionCheckpoint 和 coreState，完整提取：
            - chronologicalAgencyEvents：按正文发生顺序列出主角行为与需要玩家决定的场景，不能合并或跳过中段；
            - protagonistActions：正文中主角实际做出的主动行为，包括移动、返程、回答、取放物品、追问、修炼和战斗；
            - protagonistDecisions：已经替主角确定的路线、目标、承诺、态度和下一步计划；
            - protagonistMotives：正文给主角补写的原因、心理立场或价值判断；
            - endingState：正文最后主角所处地点和静止状态。
            - firstRequiredStopOccurrence：action 要求“遇到某情形就停下”时，候选第一次出现该情形的位置；没有则为空；
            - actionsAfterRequiredStop：第一次应停止之后，正文仍让主角执行的全部行为。

            然后逐项与 agencyBoundary 比较。authorizedActions 之外的新增主动行为、prohibitedActions 中的行为、
            被提前回答的 unresolvedDecisions、或不满足 requiredEndState 的结尾，都必须 passed=false。
            若 firstRequiredStopOccurrence 非空且 actionsAfterRequiredStop 非空，只有其中全部属于下述“自然收尾”时才可
            ACCEPT_WITH_NARRATIVE_TAIL；只要包含一项新的重大行动或决定，就必须 REPAIR_DECISION。候选不能在第一次选择点
            写“等待决定”，下一段却替玩家选择其中一条路继续行动，再停在更晚的选择点。
            NPC 的自主回应、环境变化和已授权行为的直接身体结果不算替玩家决定。不能因为候选最后声称“等待决定”
            就忽略正文此前已经发生的返程、回答、承诺或路线选择。

            自然收尾必须是已授权行动或其直接结果在当前场景内的必要整理，而不是借“收尾”开启另一件事。
            以下属于自然收尾，不能判为越权：查看自己的手掌、伤口或刚发生的结果；收回本轮已授权行动刚刚使用且
            归属未改变的物品；
            退到当前场景内的安全距离；站稳、调息、呼吸；短暂惊讶、犹豫、思考但没有形成路线、承诺或后续计划。
            若玩家用“只……”“不……”“也不做其他事情”等限定关闭了行动范围，候选另行取出或摆弄本轮未使用的物品、
            回忆新的线索、开始考虑后续计划，都不是自然收尾，必须 REPAIR_DECISION。
            出发去新地点、开始下一场战斗或调查、接受交易或拜师、消耗重要物品、开始新的长期修炼、确定新目标，
            才是必须交还玩家的重大决定。

            outcome 必须返回以下之一：
            - ACCEPT：没有越权；
            - ACCEPT_WITH_NARRATIVE_TAIL：只包含上述自然收尾；
            - REPAIR_DECISION：确实替玩家执行了新的重大行动或决定。

            只返回 JSON，不要 Markdown：
            {
              "chronologicalAgencyEvents":[],
              "protagonistActions":[],
              "protagonistDecisions":[],
              "protagonistMotives":[],
              "endingState":"",
              "firstRequiredStopOccurrence":"",
              "actionsAfterRequiredStop":[],
              "outcome":"ACCEPT|ACCEPT_WITH_NARRATIVE_TAIL|REPAIR_DECISION",
              "passed":true,
              "problem":""
            }
            """;

    private final StoryModelGateway gateway;
    private final StoryModelInvocationRecorder invocations;
    private final ObjectMapper mapper;
    private final ObjectMapper tolerantMapper;
    private final StoryPatchNormalizer normalizer = new StoryPatchNormalizer();
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
    public boolean supportsOutcomeFirst() {
        return true;
    }

    @Override
    public PlannedOutcome planOutcome(StoryPromptContext context, StoryModelRoute route,
                                      InvocationContext invocation) {
        requireContext(context, route, "结果计划");
        StoryModelRoute planningRoute = flashReviewRoute(route);
        List<StoryModelGateway.Result> attempts = new ArrayList<>();
        StoryModelGateway.Result result = call(planningRoute, invocation, "STORY_OUTCOME_PLAN_FLASH",
                OUTCOME_PLAN_PROMPT, userPrompt(context), 0.1);
        attempts.add(result);
        try {
            return new PlannedOutcome(parse(result.text()), attempts);
        } catch (JsonProcessingException firstError) {
            StoryModelGateway.Result repaired = call(planningRoute, invocation,
                    "STORY_OUTCOME_PLAN_FORMAT_REPAIR_FLASH", OUTCOME_PLAN_PROMPT,
                    repairPrompt(result.text(), firstError.getMessage()), 0.0);
            attempts.add(repaired);
            try {
                return new PlannedOutcome(parse(repaired.text()), attempts);
            } catch (JsonProcessingException | IllegalArgumentException secondError) {
                throw new IllegalArgumentException("最小结果计划未形成可校验结果", secondError);
            }
        } catch (IllegalArgumentException firstError) {
            StoryModelGateway.Result repaired = call(planningRoute, invocation,
                    "STORY_OUTCOME_PLAN_CONTRACT_REPAIR_FLASH", OUTCOME_PLAN_PROMPT,
                    invalidOutcomePlanRepairPayload(context, result.text(), firstError.getMessage()), 0.0);
            attempts.add(repaired);
            try {
                return new PlannedOutcome(parse(repaired.text()), attempts);
            } catch (JsonProcessingException | IllegalArgumentException secondError) {
                throw new IllegalArgumentException("最小结果计划未形成可校验结果", secondError);
            }
        }
    }

    @Override
    public PlannedOutcome repairOutcomePlan(StoryPromptContext context, StoryModelRoute route,
                                            InvocationContext invocation, StoryPatch rejected,
                                            String problem) {
        requireContext(context, route, "结果计划修复");
        if (rejected == null) throw new IllegalArgumentException("结果计划修复缺少原计划");
        StoryModelRoute planningRoute = flashReviewRoute(route);
        StoryModelGateway.Result result = call(planningRoute, invocation,
                "STORY_OUTCOME_PLAN_SEMANTIC_REPAIR_FLASH", OUTCOME_PLAN_PROMPT,
                outcomePlanRepairPayload(context, rejected, problem), 0.0);
        try {
            return new PlannedOutcome(parse(result.text()), List.of(result));
        } catch (JsonProcessingException | IllegalArgumentException error) {
            throw new IllegalArgumentException("结果计划自动整理后仍不符合可信协议", error);
        }
    }

    @Override
    public RenderedNarrative renderNarrative(StoryPromptContext context, StoryModelRoute route,
                                             InvocationContext invocation, StoryPatch acceptedPlan) {
        requireContext(context, route, "正文演绎");
        if (acceptedPlan == null) throw new IllegalArgumentException("正文演绎缺少已校验结果计划");
        List<StoryModelGateway.Result> attempts = new ArrayList<>();
        StoryModelGateway.Result result = call(route, invocation, "STORY_NARRATIVE_RENDER",
                NARRATIVE_RENDER_PROMPT, narrativePayload(context, acceptedPlan), 0.7);
        attempts.add(result);
        try {
            return parseNarrative(result.text(), attempts);
        } catch (JsonProcessingException | IllegalArgumentException firstError) {
            StoryModelGateway.Result repaired = call(route, invocation, "STORY_NARRATIVE_FORMAT_REPAIR",
                    NARRATIVE_RENDER_PROMPT, repairPrompt(result.text(), firstError.getMessage()), 0.1);
            attempts.add(repaired);
            try {
                return parseNarrative(repaired.text(), attempts);
            } catch (JsonProcessingException | IllegalArgumentException secondError) {
                throw new IllegalArgumentException("结果计划正文未形成可展示内容", secondError);
            }
        }
    }

    @Override
    public RenderedNarrative repairNarrative(StoryPromptContext context, StoryModelRoute route,
                                             InvocationContext invocation, StoryPatch acceptedPlan,
                                             RenderedNarrative rejected, String problem) {
        requireContext(context, route, "正文修复");
        if (acceptedPlan == null || rejected == null) throw new IllegalArgumentException("正文修复缺少计划或原正文");
        StoryModelGateway.Result result = call(route, invocation, "STORY_NARRATIVE_COVERAGE_REPAIR",
                NARRATIVE_RENDER_PROMPT, narrativeRepairPayload(context, acceptedPlan, rejected, problem), 0.3);
        try {
            return parseNarrative(result.text(), List.of(result));
        } catch (JsonProcessingException | IllegalArgumentException error) {
            throw new IllegalArgumentException("正文自动整理后仍未覆盖结果计划", error);
        }
    }

    @Override
    public Review reviewOutcomeCoverage(StoryPromptContext context, StoryModelRoute route,
                                        InvocationContext invocation, StoryPatch acceptedPlan,
                                        RenderedNarrative narrative) {
        requireContext(context, route, "结果覆盖审阅");
        if (acceptedPlan == null || narrative == null) throw new IllegalArgumentException("结果覆盖审阅缺少候选");
        StoryModelRoute reviewRoute = flashReviewRoute(route);
        List<StoryModelGateway.Result> attempts = new ArrayList<>();
        StoryModelGateway.Result result = call(reviewRoute, invocation, "STORY_OUTCOME_COVERAGE_REVIEW_FLASH",
                OUTCOME_COVERAGE_REVIEW_PROMPT, coverageReviewPayload(context, acceptedPlan, narrative), 0.0);
        attempts.add(result);
        try {
            return coverageReview(result.text(), attempts);
        } catch (JsonProcessingException | IllegalArgumentException firstError) {
            StoryModelGateway.Result repaired = call(reviewRoute, invocation,
                    "STORY_OUTCOME_COVERAGE_REVIEW_REPAIR_FLASH", OUTCOME_COVERAGE_REVIEW_PROMPT,
                    repairPrompt(result.text(), firstError.getMessage()), 0.0);
            attempts.add(repaired);
            try {
                return coverageReview(repaired.text(), attempts);
            } catch (JsonProcessingException | IllegalArgumentException secondError) {
                throw new IllegalArgumentException("结果覆盖审阅未返回有效结论", secondError);
            }
        }
    }

    @Override
    public Review reviewOutcomePlanAgency(StoryPromptContext context, StoryModelRoute route,
                                          InvocationContext invocation, StoryPatch acceptedPlan) {
        requireContext(context, route, "结果计划授权复核");
        if (acceptedPlan == null) throw new IllegalArgumentException("结果计划授权复核缺少候选");
        StoryModelRoute reviewRoute = flashReviewRoute(route);
        StoryModelGateway.Result result = call(reviewRoute, invocation,
                "STORY_OUTCOME_PLAN_AGENCY_REVIEW_FLASH", AGENCY_REVIEW_PROMPT,
                json(agencyReviewPayload(context, acceptedPlan), "结果计划授权复核请求无法序列化"), 0.0);
        try {
            AgencyReviewResponse agency = tolerantMapper.readValue(rawJson(result.text()),
                    AgencyReviewResponse.class);
            agency.validate();
            String extractedBoundaryProblem = agency.extractedBoundaryProblem();
            ReviewOutcome outcome = agency.reviewOutcome();
            boolean accepted = (outcome == ReviewOutcome.ACCEPT
                    || outcome == ReviewOutcome.ACCEPT_WITH_NARRATIVE_TAIL)
                    && extractedBoundaryProblem.isBlank();
            String problem = extractedBoundaryProblem.isBlank() ? agency.problem() : extractedBoundaryProblem;
            return new Review(accepted ? outcome : ReviewOutcome.REPAIR_DECISION,
                    accepted ? "" : problem.isBlank() ? "结果计划越过玩家授权边界" : problem,
                    List.of(result));
        } catch (JsonProcessingException | IllegalArgumentException error) {
            throw new IllegalArgumentException("结果计划授权复核未返回有效结论", error);
        }
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
    public ProjectedFacts projectFacts(StoryPromptContext context, StoryModelRoute route,
                                       InvocationContext invocation, StoryPatch narrativeDraft) {
        if (context == null || narrativeDraft == null) {
            throw new IllegalArgumentException("事实投影缺少命卷上下文或剧情草稿");
        }
        if (route == null) throw new IllegalArgumentException("事实投影缺少模型路由");
        StoryModelRoute projectionRoute = flashReviewRoute(route);
        List<StoryModelGateway.Result> attempts = new ArrayList<>();
        StoryModelGateway.Result result = call(projectionRoute, invocation, "STORY_FACT_PROJECTION_FLASH",
                FACT_PROJECTION_PROMPT, factProjectionPayload(context, narrativeDraft), 0.0);
        attempts.add(result);
        try {
            return new ProjectedFacts(withNarrativeDraft(context, narrativeDraft, parse(result.text())), attempts);
        } catch (JsonProcessingException | IllegalArgumentException firstError) {
            StoryModelGateway.Result repaired = call(projectionRoute, invocation,
                    "STORY_FACT_PROJECTION_REPAIR_FLASH", FACT_PROJECTION_PROMPT,
                    repairPrompt(result.text(), firstError.getMessage()), 0.0);
            attempts.add(repaired);
            try {
                return new ProjectedFacts(withNarrativeDraft(context, narrativeDraft, parse(repaired.text())), attempts);
            } catch (JsonProcessingException | IllegalArgumentException secondError) {
                throw new IllegalArgumentException("可信事实投影未形成可校验结果", secondError);
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
        List<StoryModelGateway.Result> attempts = new ArrayList<>();
        StoryModelGateway.Result result = call(contractRoute, invocation, "STORY_ACTION_CONTRACT_FLASH",
                ACTION_CONTRACT_PROMPT, actionContractPayload(context), 0.0);
        attempts.add(result);
        try {
            StoryActionContract contract = tolerantMapper.readValue(rawJson(result.text()), StoryActionContract.class);
            return new ResolvedAction(contract, attempts);
        } catch (JsonProcessingException firstError) {
            StoryModelGateway.Result repaired = call(contractRoute, invocation,
                    "STORY_ACTION_CONTRACT_FORMAT_REPAIR_FLASH", ACTION_CONTRACT_PROMPT,
                    actionContractFormatRepairPayload(context, result.text(), firstError.getMessage()), 0.0);
            attempts.add(repaired);
            try {
                StoryActionContract contract = tolerantMapper.readValue(
                        rawJson(repaired.text()), StoryActionContract.class);
                return new ResolvedAction(contract, attempts);
            } catch (JsonProcessingException secondError) {
                throw new IllegalArgumentException("行动事实解析在自动整理后仍未返回有效结果", secondError);
            }
        }
    }

    @Override
    public ResolvedAction repairActionContract(StoryPromptContext context, StoryModelRoute route,
                                               InvocationContext invocation,
                                               StoryActionContract rejected, String problem) {
        if (context == null || rejected == null) throw new IllegalArgumentException("行动契约修复缺少上下文");
        if (route == null) throw new IllegalArgumentException("行动契约修复缺少模型路由");
        StoryModelRoute contractRoute = flashReviewRoute(route);
        StoryModelGateway.Result result = call(contractRoute, invocation, "STORY_ACTION_CONTRACT_REPAIR_FLASH",
                ACTION_CONTRACT_PROMPT, actionContractRepairPayload(context, rejected, problem), 0.0);
        try {
            StoryActionContract contract = tolerantMapper.readValue(rawJson(result.text()), StoryActionContract.class);
            return new ResolvedAction(contract, List.of(result));
        } catch (JsonProcessingException error) {
            throw new IllegalArgumentException("行动事实契约修复未返回有效结果", error);
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
            List<String> problems = response.factProblems();
            if (!problems.isEmpty()) {
                return new Review(ReviewOutcome.REPAIR_FACTS, String.join("；", problems), List.of(result));
            }
            StoryModelGateway.Result agencyResult = call(reviewRoute, invocation,
                    "STORY_TURN_AGENCY_REVIEW_FLASH", AGENCY_REVIEW_PROMPT,
                    mapper.writeValueAsString(agencyReviewPayload(context, proposed)), 0.0);
            AgencyReviewResponse agency = tolerantMapper.readValue(rawJson(agencyResult.text()),
                    AgencyReviewResponse.class);
            agency.validate();
            String extractedBoundaryProblem = agency.extractedBoundaryProblem();
            ReviewOutcome outcome = agency.reviewOutcome();
            boolean agencyPassed = (outcome == ReviewOutcome.ACCEPT
                    || outcome == ReviewOutcome.ACCEPT_WITH_NARRATIVE_TAIL)
                    && extractedBoundaryProblem.isBlank();
            String problem = !extractedBoundaryProblem.isBlank()
                    ? extractedBoundaryProblem : agency.problem();
            String agencyProblem = agencyPassed
                    ? "" : "玩家授权：" + (problem.isBlank() ? "授权复核未通过" : problem);
            return new Review(agencyPassed ? outcome : ReviewOutcome.REPAIR_DECISION,
                    agencyProblem, List.of(result, agencyResult));
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
        facts.put("initialContract", context.objective().initialContract());
        facts.put("sourceExcerpts", context.objective().sourceExcerpts());

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
        payload.put("decisionEnvelope", StoryDecisionEnvelope.from(context.action().factContract()));
        payload.put("proposed", candidate);
        return payload;
    }

    /** 授权复核不携带世界设定和实体详情，只保留玩家原话、授权边界和候选中的主角行为。 */
    private Map<String, Object> agencyReviewPayload(StoryPromptContext context, StoryPatch proposed) {
        Map<String, Object> candidate = new LinkedHashMap<>();
        candidate.put("narrative", proposed.narrative());
        candidate.put("outcome", proposed.outcome());
        candidate.put("coreState", proposed.coreState());
        candidate.put("entities", proposed.entities());
        candidate.put("worldFacts", proposed.worldFacts());
        candidate.put("memories", proposed.memories());
        candidate.put("decisionCheckpoint", proposed.decisionCheckpoint());
        candidate.put("rollingSummary", proposed.rollingSummary());
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("action", context.action());
        payload.put("decisionEnvelope", StoryDecisionEnvelope.from(context.action().factContract()));
        payload.put("proposed", candidate);
        return payload;
    }

    private void requireContext(StoryPromptContext context, StoryModelRoute route, String operation) {
        if (context == null || context.action() == null || context.action().playerAction() == null
                || context.action().playerAction().isBlank()) {
            throw new IllegalArgumentException(operation + "缺少命卷上下文或玩家行动");
        }
        if (route == null) throw new IllegalArgumentException(operation + "缺少模型路由");
    }

    private String outcomePlanRepairPayload(StoryPromptContext context, StoryPatch rejected, String problem) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("instruction", OUTCOME_PLAN_REPAIR_PROMPT);
        payload.put("objective", context.objective());
        payload.put("action", context.action());
        payload.put("rejectedOutcome", rejected);
        payload.put("validationProblem", problem == null ? "结果计划未通过预校验" : problem.strip());
        return json(payload, "结果计划修复请求无法序列化");
    }

    /**
     * JSON 已能读取但契约字段不完整时，携带当前权威事实和行动重新整理计划。
     * 这与纯 JSON 格式修复分开，避免模型在缺少稳定引用上下文时反复返回同类错误。
     */
    private String invalidOutcomePlanRepairPayload(StoryPromptContext context, String rejected, String problem) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("instruction", OUTCOME_PLAN_REPAIR_PROMPT);
        payload.put("objective", context.objective());
        payload.put("action", context.action());
        payload.put("rejectedOutcome", rejected == null ? "" : rejected);
        payload.put("validationProblem", problem == null ? "结果计划契约字段不完整" : problem.strip());
        return json(payload, "结果计划契约修复请求无法序列化");
    }

    private String narrativePayload(StoryPromptContext context, StoryPatch acceptedPlan) {
        Map<String, Object> objective = new LinkedHashMap<>();
        objective.put("worldClock", context.objective().worldClock());
        objective.put("protagonist", context.objective().protagonist());
        objective.put("currentInventory", context.objective().currentInventory());
        objective.put("entityIndex", context.objective().entityIndex());
        objective.put("relatedEntities", context.objective().relatedEntities());
        objective.put("pendingDecision", context.objective().pendingDecision());
        objective.put("initialContract", context.objective().initialContract());
        objective.put("sourceExcerpts", context.objective().sourceExcerpts());

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("systemConstraints", context.system());
        payload.put("objective", objective);
        payload.put("cognition", context.cognition());
        payload.put("memory", context.memory());
        payload.put("previousNarrative", context.narrative());
        payload.put("action", context.action());
        payload.put("validatedOutcome", acceptedPlan);
        return json(payload, "结果计划正文请求无法序列化");
    }

    private String narrativeRepairPayload(StoryPromptContext context, StoryPatch acceptedPlan,
                                          RenderedNarrative rejected, String problem) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("instruction", NARRATIVE_REPAIR_PROMPT);
        payload.put("action", context.action());
        payload.put("validatedOutcome", acceptedPlan);
        payload.put("rejectedNarrative", Map.of(
                "title", rejected.title(),
                "narrative", rejected.narrative(),
                "choices", rejected.choices(),
                "rollingSummary", rejected.rollingSummary()));
        payload.put("reviewProblem", problem == null ? "正文未忠实覆盖结果计划" : problem.strip());
        return json(payload, "结果计划正文修复请求无法序列化");
    }

    private String coverageReviewPayload(StoryPromptContext context, StoryPatch acceptedPlan,
                                         RenderedNarrative narrative) {
        Map<String, Object> facts = new LinkedHashMap<>();
        facts.put("worldClock", context.objective().worldClock());
        facts.put("protagonist", context.objective().protagonist());
        facts.put("currentInventory", context.objective().currentInventory());
        facts.put("entityIndex", context.objective().entityIndex());
        facts.put("pendingDecision", context.objective().pendingDecision());
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("facts", facts);
        payload.put("action", context.action());
        payload.put("acceptedOutcome", acceptedPlan);
        payload.put("rendered", Map.of(
                "title", narrative.title(),
                "narrative", narrative.narrative(),
                "choices", narrative.choices(),
                "rollingSummary", narrative.rollingSummary()));
        return json(payload, "结果覆盖审阅请求无法序列化");
    }

    private RenderedNarrative parseNarrative(String raw, List<StoryModelGateway.Result> attempts)
            throws JsonProcessingException {
        NarrativeResponse response = tolerantMapper.readValue(rawJson(raw), NarrativeResponse.class);
        return new RenderedNarrative(response.title(), response.narrative(), response.choices(),
                response.rollingSummary(), attempts);
    }

    private Review coverageReview(String raw, List<StoryModelGateway.Result> attempts)
            throws JsonProcessingException {
        CoverageReviewResponse response = tolerantMapper.readValue(rawJson(raw), CoverageReviewResponse.class);
        List<String> problems = response.problems();
        CoverageRepairTarget target = response.repairTarget();
        if (problems.isEmpty()) {
            if (target != CoverageRepairTarget.NONE) {
                throw new IllegalArgumentException("结果覆盖审阅全部通过时 repairTarget 必须为 NONE");
            }
            return new Review(ReviewOutcome.ACCEPT, "", attempts);
        }
        if (target == null || target == CoverageRepairTarget.NONE) {
            throw new IllegalArgumentException("结果覆盖审阅拒绝时必须指出 PLAN 或 NARRATIVE 修复目标");
        }
        return new Review(target == CoverageRepairTarget.PLAN
                ? ReviewOutcome.REPAIR_FACTS : ReviewOutcome.REPAIR_DECISION,
                String.join("；", problems), attempts);
    }

    private String json(Object value, String errorMessage) {
        try {
            return mapper.writeValueAsString(value);
        } catch (JsonProcessingException error) {
            throw new IllegalStateException(errorMessage, error);
        }
    }

    private String actionContractPayload(StoryPromptContext context) {
        Map<String, Object> facts = new LinkedHashMap<>();
        facts.put("worldClock", context.objective().worldClock());
        facts.put("coreState", context.objective().protagonist());
        facts.put("currentInventory", context.objective().currentInventory());
        facts.put("entityIndex", context.objective().entityIndex());
        facts.put("relatedEntities", context.objective().relatedEntities());
        facts.put("pendingDecision", context.objective().pendingDecision());
        facts.put("initialContract", context.objective().initialContract());
        facts.put("sourceExcerpts", context.objective().sourceExcerpts());
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("systemConstraints", context.system());
        payload.put("facts", facts);
        payload.put("action", context.action());
        payload.put("selectedChoice", context.action().selectedChoice());
        try {
            return mapper.writeValueAsString(payload);
        } catch (JsonProcessingException error) {
            throw new IllegalStateException("行动事实解析上下文无法序列化", error);
        }
    }

    /** 事实投影只携带当前事实、行动契约和本轮草稿，不重复发送旧正文或完整主观记忆。 */
    private String factProjectionPayload(StoryPromptContext context, StoryPatch draft) {
        Map<String, Object> facts = new LinkedHashMap<>();
        facts.put("worldClock", context.objective().worldClock());
        facts.put("protagonist", context.objective().protagonist());
        facts.put("currentInventory", context.objective().currentInventory());
        facts.put("entityIndex", context.objective().entityIndex());
        facts.put("relatedEntities", context.objective().relatedEntities());
        facts.put("worldFacts", context.objective().worldFacts());
        facts.put("pendingDecision", context.objective().pendingDecision());
        facts.put("initialContract", context.objective().initialContract());
        facts.put("sourceExcerpts", context.objective().sourceExcerpts());

        Map<String, Object> narrative = new LinkedHashMap<>();
        narrative.put("title", draft.title());
        narrative.put("narrative", draft.narrative());
        narrative.put("choices", draft.choices());

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("systemConstraints", context.system());
        payload.put("facts", facts);
        payload.put("action", context.action());
        payload.put("timeContract", Map.of(
                "semanticDuration", context.action().semanticDuration() == null
                        ? "" : context.action().semanticDuration(),
                "explicitDurationMinutes", context.action().explicitDurationMinutes() == null
                        ? "" : context.action().explicitDurationMinutes(),
                "expectedEndClock", context.action().expectedEndClock() == null
                        ? "" : context.action().expectedEndClock()));
        payload.put("previousRollingSummary", context.narrative().rollingSummary());
        payload.put("narrativeDraft", narrative);
        try {
            return mapper.writeValueAsString(payload);
        } catch (JsonProcessingException error) {
            throw new IllegalStateException("事实投影上下文无法序列化", error);
        }
    }

    /** 叙事内容属于剧情草稿；事实投影无权借结构提取阶段改写玩家看到的正文与推荐行动。 */
    private StoryPatch withNarrativeDraft(StoryPromptContext context, StoryPatch draft, StoryPatch projected) {
        StoryPatch.TimePassage acceptedTime = projected.time();
        Long explicitDuration = context.action().explicitDurationMinutes();
        if (explicitDuration != null && acceptedTime != null
                && acceptedTime.resolution() == StoryPatch.TimeResolution.COMPLETE) {
            // 明确时长由服务端解析成唯一时间契约。事实模型只判断是否完整执行，不能因历法换算不同造成重试。
            acceptedTime = new StoryPatch.TimePassage(explicitDuration,
                    StoryPatch.TimeResolution.COMPLETE, null);
        }
        return new StoryPatch(draft.title(), draft.narrative(), acceptedTime, projected.coreState(),
                projected.entities(), projected.worldFacts(), projected.knowledge(), projected.memories(),
                projected.decisionCheckpoint(), draft.choices(), projected.rollingSummary(), projected.outcome());
    }

    private String actionContractRepairPayload(StoryPromptContext context,
                                               StoryActionContract rejected, String problem) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("instruction", ACTION_CONTRACT_REPAIR_PROMPT);
        try {
            payload.put("originalContext", mapper.readTree(actionContractPayload(context)));
        } catch (JsonProcessingException error) {
            throw new IllegalStateException("行动契约修复上下文无法序列化", error);
        }
        payload.put("rejectedContract", rejected);
        payload.put("validationProblem", problem == null ? "" : problem.strip());
        try {
            return mapper.writeValueAsString(payload);
        } catch (JsonProcessingException error) {
            throw new IllegalStateException("行动契约修复请求无法序列化", error);
        }
    }

    private String actionContractFormatRepairPayload(StoryPromptContext context,
                                                     String malformedContract, String problem) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("instruction", ACTION_CONTRACT_FORMAT_REPAIR_PROMPT);
        try {
            payload.put("originalContext", mapper.readTree(actionContractPayload(context)));
        } catch (JsonProcessingException error) {
            throw new IllegalStateException("行动契约格式修复上下文无法序列化", error);
        }
        payload.put("malformedContract", malformedContract == null ? "" : malformedContract.strip());
        payload.put("validationProblem", problem == null ? "" : problem.strip());
        try {
            return mapper.writeValueAsString(payload);
        } catch (JsonProcessingException error) {
            throw new IllegalStateException("行动契约格式修复请求无法序列化", error);
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
        StoryPatch patch = normalizer.normalize(tolerantMapper.readValue(rawJson(raw), StoryPatch.class));
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

    private record NarrativeResponse(String title, String narrative, List<StoryPatch.ChoiceDraft> choices,
                                     String rollingSummary) {}

    private record CoverageReviewResponse(ReviewCheck coverage, ReviewCheck contradiction,
                                          ReviewCheck extraDurableFacts, ReviewCheck agency,
                                          CoverageRepairTarget repairTarget) {
        private List<String> problems() {
            List<String> values = new ArrayList<>();
            collect(values, "结果覆盖", coverage);
            collect(values, "事实矛盾", contradiction);
            collect(values, "计划外长期事实", extraDurableFacts);
            collect(values, "玩家决定边界", agency);
            return List.copyOf(values);
        }

        private void collect(List<String> values, String label, ReviewCheck check) {
            if (check == null || check.passed() == null) {
                throw new IllegalArgumentException("结果覆盖审阅缺少“" + label + "”检查结果");
            }
            if (check.passed()) return;
            String detail = check.problem() == null ? "" : check.problem().strip();
            values.add(label + "：" + (detail.isBlank() ? "审阅未通过" : detail));
        }
    }

    private enum CoverageRepairTarget {
        NONE,
        PLAN,
        NARRATIVE
    }

    private record ReviewResponse(ReviewCheck reference, ReviewCheck inventory, ReviewCheck authorization,
                                  ReviewCheck decision, ReviewCheck time, ReviewCheck state) {
        private List<String> factProblems() {
            List<String> values = new ArrayList<>();
            collect(values, "实体引用", reference);
            collect(values, "物品", inventory);
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

    private record AgencyReviewResponse(List<String> chronologicalAgencyEvents,
                                        List<String> protagonistActions,
                                        List<String> protagonistDecisions,
                                        List<String> protagonistMotives,
                                        String endingState,
                                        String firstRequiredStopOccurrence,
                                        List<String> actionsAfterRequiredStop,
                                        String outcome,
                                        Boolean passed,
                                        String problem) {
        private AgencyReviewResponse {
            outcome = outcome == null ? "" : outcome.strip();
            problem = problem == null ? "" : problem.strip();
        }

        private void validate() {
            if (chronologicalAgencyEvents == null || protagonistActions == null || protagonistDecisions == null
                    || protagonistMotives == null || endingState == null || firstRequiredStopOccurrence == null
                    || actionsAfterRequiredStop == null || passed == null) {
                throw new IllegalArgumentException("玩家授权复核缺少完整行为提取结果");
            }
            if (!passed && problem.isBlank()) {
                throw new IllegalArgumentException("玩家授权复核拒绝时必须说明原因");
            }
            reviewOutcome();
        }

        private ReviewOutcome reviewOutcome() {
            if (outcome.isBlank()) return passed ? ReviewOutcome.ACCEPT : ReviewOutcome.REPAIR_DECISION;
            try {
                ReviewOutcome parsed = ReviewOutcome.valueOf(outcome);
                if (parsed != ReviewOutcome.ACCEPT
                        && parsed != ReviewOutcome.ACCEPT_WITH_NARRATIVE_TAIL
                        && parsed != ReviewOutcome.REPAIR_DECISION) {
                    throw new IllegalArgumentException("玩家授权复核返回了不适用的分类：" + outcome);
                }
                if (passed != (parsed != ReviewOutcome.REPAIR_DECISION)) {
                    throw new IllegalArgumentException("玩家授权复核分类与 passed 不一致");
                }
                return parsed;
            } catch (IllegalArgumentException error) {
                if (error.getMessage() != null && error.getMessage().startsWith("玩家授权复核")) throw error;
                throw new IllegalArgumentException("玩家授权复核返回了未知分类：" + outcome, error);
            }
        }

        /**
         * 复核模型已经逐段提取出“应停点后的行动”时，Java 只核对这组结构化结果自洽，
         * 不再依赖中文剧情关键词重新判断。该冲突按正文越权处理并走整段重新生成，而不是变成技术失败。
         */
        private String extractedBoundaryProblem() {
            if (firstRequiredStopOccurrence.isBlank() && !actionsAfterRequiredStop.isEmpty()) {
                return "授权复核列出了停止边界后的行动，但缺少首次应停止位置";
            }
            if (!firstRequiredStopOccurrence.isBlank() && !actionsAfterRequiredStop.isEmpty() && passed
                    && reviewOutcome() != ReviewOutcome.ACCEPT_WITH_NARRATIVE_TAIL) {
                return "候选在首次应停止位置之后仍替玩家继续行动";
            }
            return "";
        }
    }
}
