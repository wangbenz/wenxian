package com.wenxian.v2.ai.application;

import com.wenxian.v2.ai.domain.TurnNarrativeContext;
import com.wenxian.v2.ai.domain.TurnProposal.Generated;

public interface TurnProposalGenerator {
    Generated generate(TurnNarrativeContext context);
}
