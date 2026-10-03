package com.wenxian.game.story.agent;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.wenxian.game.model.application.ModelInvocationService;
import com.wenxian.game.model.domain.ModelRouteRequest;
import com.wenxian.game.model.domain.ModelTask;
import com.wenxian.game.story.agent.ai.StoryAiServiceRegistry;
import com.wenxian.game.story.domain.StoryCorrectionProposal;
import com.wenxian.game.story.domain.StoryCorrectionValidator;
import com.wenxian.game.story.domain.StoryPatch;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 通过统一模型路由和类型化 LangChain4j AI Service 生成命卷修正提案。
 *
 * <p>本类不接触数据库，也不应用模型输出。提案仍须经过领域校验、签名预览、玩家确认和 Reducer。</p>
 */
@Service
public class LangChain4jStoryCorrectionAgent implements StoryCorrectionAgent {
    private final ModelInvocationService invocations;
    private final StoryAiServiceRegistry services;
    private final ObjectMapper mapper;
    private final StoryCorrectionValidator validator;

    @Autowired
    public LangChain4jStoryCorrectionAgent(ModelInvocationService invocations,
                                           StoryAiServiceRegistry services,
                                           ObjectMapper mapper) {
        this(invocations, services, mapper, new StoryCorrectionValidator());
    }

    LangChain4jStoryCorrectionAgent(ModelInvocationService invocations,
                                    StoryAiServiceRegistry services,
                                    ObjectMapper mapper,
                                    StoryCorrectionValidator validator) {
        this.invocations = invocations;
        this.services = services;
        this.mapper = mapper;
        this.validator = validator;
    }

    @Override
    public Generated propose(String userId, String requestId, StoryPromptContext context, String instruction) {
        if (context == null) throw new IllegalArgumentException("修正预览缺少命卷上下文");
        if (instruction == null || instruction.isBlank()) throw new IllegalArgumentException("修正说明不能为空");
        try {
            String payload = userPrompt(context, instruction.strip());
            StoryCorrectionProposal proposal = invocations.invoke(
                    new ModelRouteRequest(ModelTask.STORY_CORRECTION, requestId, userId, false),
                    services::storyCorrection,
                    service -> service.propose(payload)).content();
            proposal = normalizeCultivation(context, proposal);
            if (!hasObjectiveChange(proposal)) {
                throw new StoryCorrectionNoChangeException(
                        "这段说明没有改变当前权威事实；未经玩家确认的叙事打算不会作为已执行行动继承。");
            }
            validator.validate(proposal);
            return new Generated(proposal);
        } catch (StoryCorrectionNoChangeException error) {
            throw error;
        } catch (JsonProcessingException error) {
            throw new IllegalArgumentException("修正预览上下文无法序列化", error);
        } catch (IllegalArgumentException error) {
            throw new IllegalArgumentException("修正说明未能形成可确认提案", error);
        }
    }

    private boolean hasObjectiveChange(StoryCorrectionProposal proposal) {
        if (proposal == null) return false;
        var core = proposal.coreState();
        boolean coreChanged = core != null && (present(core.location()) || present(core.condition())
                || present(core.realm()) || present(core.lifeGoal()));
        return coreChanged || proposal.cultivation() != null
                || !proposal.entities().isEmpty() || !proposal.worldFacts().isEmpty()
                || !proposal.knowledge().isEmpty();
    }

    /** 将模型对境界的自然语言理解收束到当前命卷已经确认的稳定阶段 ID。 */
    private StoryCorrectionProposal normalizeCultivation(StoryPromptContext context,
                                                           StoryCorrectionProposal proposal) {
        if (proposal == null) return null;
        StoryPromptContext.CultivationView current = context.objective().cultivation();
        StoryPatch.CultivationPatch requested = proposal.cultivation();
        StoryPatch.CoreStatePatch core = proposal.coreState();
        boolean freeRealm = core != null && present(core.realm());

        if (current == null) {
            throw new IllegalArgumentException("当前命卷缺少已确认主修体系，不能生成修正提案");
        }
        if (requested == null) {
            if (freeRealm) {
                throw new IllegalArgumentException("当前命卷的境界修正必须引用已确认体系中的稳定阶段ID");
            }
            return proposal;
        }
        boolean knownStage = current.stages().stream()
                .anyMatch(stage -> stage.stageId().equals(requested.targetStageId()));
        if (!knownStage) {
            throw new IllegalArgumentException("修正目标不属于当前命卷已确认的主修体系");
        }
        StoryPatch.CultivationPatch normalizedRequested = new StoryPatch.CultivationPatch(
                requested.targetStageId(), requested.progressNote(),
                StoryPatch.CultivationResolution.CORRECTED, requested.reason());
        if (freeRealm) {
            core = new StoryPatch.CoreStatePatch(core.location(), core.condition(), null, core.lifeGoal());
        }
        return new StoryCorrectionProposal(proposal.kind(), proposal.explanation(),
                proposal.confirmationPrompt(), core, normalizedRequested, proposal.entities(),
                proposal.worldFacts(), proposal.knowledge());
    }

    private boolean present(String value) {
        return value != null && !value.isBlank();
    }

    private String userPrompt(StoryPromptContext context, String instruction) throws JsonProcessingException {
        Map<String, Object> sections = new LinkedHashMap<>();
        sections.put("objective", context.objective());
        sections.put("cognition", context.cognition());
        sections.put("memory", context.memory());
        sections.put("narrative", context.narrative());
        sections.put("playerCorrectionInstruction", instruction);
        return mapper.writeValueAsString(sections);
    }
}
