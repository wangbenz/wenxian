package com.wenxian.v2.ai.application;

import com.wenxian.v2.ai.domain.ModelContracts.ModelRequest;
import com.wenxian.v2.ai.domain.ModelContracts.ModelResult;

public interface ModelGateway {
    ModelResult completeJson(ModelRequest request);
}
