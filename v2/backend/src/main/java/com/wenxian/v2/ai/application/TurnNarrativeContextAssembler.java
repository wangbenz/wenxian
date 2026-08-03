package com.wenxian.v2.ai.application;

import com.wenxian.v2.ai.domain.TurnNarrativeContext;
import com.wenxian.v2.ai.persistence.NarrativeContextRepository;
import com.wenxian.v2.game.domain.GameRecords.SaveAggregate;
import org.springframework.stereotype.Service;

@Service
public class TurnNarrativeContextAssembler {
    private static final String WORLD_CANON = """
            玄墟界，绝天之变后三千年。最后一批飞升者在天门前失踪，此后无人能证明仙界存在。
            当前开放地域为东陆北境的青岐州。人、妖、灵三族拥有不同生命形态与境界称谓，
            修行需要资质、资源、传承、时间与代价。世界不会等待主角，既有事实不能被叙事静默改写。
            玄墟历固定每年十二个月、每月三十日、每日一千四百四十分钟；所有明确年月日必须按此换算。
            """;

    private final IntentContextAssembler authority;
    private final NarrativeContextRepository context;

    public TurnNarrativeContextAssembler(IntentContextAssembler authority,
                                         NarrativeContextRepository context) {
        this.authority = authority;
        this.context = context;
    }

    public TurnNarrativeContext assemble(String userId, String operationId, String actionText,
                                         SaveAggregate aggregate) {
        String saveId = aggregate.save().id();
        return new TurnNarrativeContext(WORLD_CANON, aggregate.save().difficulty().name(),
                authority.assemble(userId, operationId, actionText, aggregate),
                context.recentTurns(saveId, 3), context.confirmedFacts(saveId, 12),
                context.activeEvents(saveId));
    }
}
