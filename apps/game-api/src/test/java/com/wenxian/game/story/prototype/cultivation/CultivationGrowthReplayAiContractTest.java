package com.wenxian.game.story.prototype.cultivation;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.wenxian.game.story.agent.ai.NarrativeWriterAi;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.service.AiServices;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import static com.wenxian.game.story.prototype.cultivation.CultivationGrowthPrototype.*;
import static com.wenxian.game.story.prototype.cultivation.CultivationGrowthReplay.*;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * 用进程内固定 ChatModel 验证真正的 LangChain4j 类型化解析、资源 Prompt 和成长宿主衔接。
 * 无 HTTP、凭证或数据库；样本只验证协议可执行，不证明供应商能力或修行资格判断合理。
 */
class CultivationGrowthReplayAiContractTest {
    private final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();

    @ParameterizedTest
    @EnumSource(Readiness.class)
    void typedDirectorAndWriterParseNestedGrowthAndCompleteIsolatedTurn(Readiness readiness) throws Exception {
        CultivationGrowthReplay host = new CultivationGrowthReplay(mapper);
        Checkpoint before = host.initial();
        String action = "原地尝试吐纳一盏茶，结束后停下，不尝试突破。";
        String directorPrompt = prompt("director");
        String writerPrompt = prompt("writer");
        FixedResponseModel directorModel = new FixedResponseModel(directorJson(readiness));
        FixedResponseModel writerModel = new FixedResponseModel("""
                {
                  "title": "一息之间",
                  "narrative": "你停在练功廊，慢慢找回方才那段舒适的呼吸。稍微加快脚步，气息便散了；你收住脚，没有尝试突破。",
                  "choices": [{"title": "再试缓步", "description": "试着放慢脚步，观察呼吸是否仍能接续。"}],
                  "summary": "原地尝试吐纳后停下，缓步仍会打乱气息，没有晋升。"
                }
                """);
        DirectorAi director = AiServices.builder(DirectorAi.class)
                .systemMessage(directorPrompt).chatModel(directorModel).build();
        NarrativeWriterAi writer = AiServices.builder(NarrativeWriterAi.class)
                .systemMessage(writerPrompt).chatModel(writerModel).build();

        // 响应字符串由 AiServices 自己解码为深层 record / enum，不经过测试中的 ObjectMapper 解码。
        var directed = director.direct(mapper.writeValueAsString(host.directorInput(before, action)));
        DirectorDraft draft = directed.content();
        assertThat(draft.resolution()).isEqualTo(Resolution.PARTIAL);
        assertThat(draft.growth().qualification().conclusion()).isEqualTo(readiness);
        assertThat(draft.growth().learning()).singleElement().satisfies(learning -> {
            assertThat(learning.abilityRef()).isEqualTo(ABILITY);
            assertThat(learning.evidenceRefs()).containsExactly("breath");
        });
        if (readiness == Readiness.READY) {
            assertThat(draft.growth().qualification().target()).isEqualTo(
                    new Position("realm:xuanxu:human:l1", "phase:xuanxu:middle"));
        } else {
            assertThat(draft.growth().qualification().target()).isNull();
        }

        Prepared prepared = host.prepare(before, action, draft);
        var rendered = writer.write(mapper.writeValueAsString(prepared.writerInput()));
        Checkpoint after = host.complete(prepared, rendered.content());

        assertThat(after.growth().revision()).isEqualTo(1);
        assertThat(after.growth().position()).isEqualTo(before.growth().position());
        assertThat(after.growth().learning().get(ABILITY).evidenceRefs())
                .containsExactly("experience:replay:1:1");
        assertThat(after.growth().qualification() != null).isEqualTo(readiness == Readiness.READY);
        assertThat(after.turns()).singleElement().satisfies(turn -> {
            assertThat(turn.prose().title()).isEqualTo("一息之间");
            assertThat(turn.prose().choices()).singleElement().satisfies(choice ->
                    assertThat(choice.title()).isEqualTo("再试缓步"));
        });
        assertThat(before.turns()).isEmpty();
        assertThat(before.experiences()).isEmpty();
        assertPromptReachedModel(directorModel, directorPrompt);
        assertPromptReachedModel(writerModel, writerPrompt);
    }

    private void assertPromptReachedModel(FixedResponseModel model, String expectedPrompt) {
        assertThat(model.requests).hasSize(1);
        assertThat(SystemMessage.findFirst(model.requests.getFirst().messages())).hasValueSatisfying(message ->
                assertThat(message.text()).contains(expectedPrompt));
    }

    private String prompt(String role) throws Exception {
        try (var stream = getClass().getClassLoader().getResourceAsStream("prompts/cultivation-replay-" + role + ".txt")) {
            assertThat(stream).as("实验 Prompt 资源存在").isNotNull();
            String text = new String(stream.readAllBytes(), StandardCharsets.UTF_8);
            assertThat(text).contains("prompt-version: cultivation-replay-" + role + "-v1");
            return text;
        }
    }

    private String directorJson(Readiness readiness) {
        String target = readiness == Readiness.READY
                ? "{\"stageRef\":\"realm:xuanxu:human:l1\",\"stepRef\":\"phase:xuanxu:middle\"}" : "null";
        String evidence = readiness == Readiness.READY ? "[\"breath\"]" : "[]";
        return """
                {
                  "resolution": "PARTIAL",
                  "outcomeSummary": "尝试中找回舒适呼吸，稍快便散乱。",
                  "elapsedTime": "一盏茶",
                  "experiences": [{"localRef":"breath","statement":"站定时找到呼吸节奏，快步时中断。","abilityRefs":["ability:replay:breathing"]}],
                  "growth": {
                    "learning": [{"abilityRef":"ability:replay:breathing","achieved":"站定可找回舒适呼吸","limitations":"快步仍会中断","evidenceRefs":["breath"]}],
                    "accumulation": {"achieved":"能够在站定时重复练习","limitations":"移动时尚不稳定","evidenceRefs":["breath"]},
                    "qualification": {"conclusion":"%s","target":%s,"reason":"固定协议样本，仅检验字段和证据通路。","evidenceRefs":%s}
                  },
                  "sceneBeats": ["原地尝试吐纳", "停下观察呼吸"],
                  "stoppingPoint": "仍在练功廊，未尝试突破。"
                }
                """.formatted(readiness.name(), target, evidence);
    }

    /** 只返回一次内存中的合成 JSON；任何隐式重试都会让测试失败，不含网络客户端。 */
    private static final class FixedResponseModel implements ChatModel {
        private final String response;
        private final List<ChatRequest> requests = new ArrayList<>();

        private FixedResponseModel(String response) {
            this.response = response;
        }

        @Override
        public ChatResponse doChat(ChatRequest request) {
            assertThat(requests).as("每阶段仅允许一次本地模型调用").isEmpty();
            requests.add(request);
            return ChatResponse.builder().aiMessage(AiMessage.from(response)).build();
        }
    }
}
