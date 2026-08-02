package com.wenxian.v2.ai.application;

import com.wenxian.v2.ai.domain.ActionIntent;
import com.wenxian.v2.ai.domain.IntentContext;

public interface IntentInterpreter {
    ActionIntent interpret(IntentContext context);
}
