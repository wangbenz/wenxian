package com.wenxian.game.story.api;

import org.junit.jupiter.api.Test;
import org.springframework.web.bind.annotation.PostMapping;

import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.Set;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/** 保护玩家修正必须经过“预览后确认”的稳定 HTTP 契约。 */
class StoryCorrectionApiContractTest {

    @Test
    void exposesOnlyTwoPhaseCorrectionOnStableStoryApi() {
        Set<String> postPaths = Arrays.stream(StoryController.class.getDeclaredMethods())
                .map(Method::getDeclaredAnnotations)
                .flatMap(Arrays::stream)
                .filter(PostMapping.class::isInstance)
                .map(PostMapping.class::cast)
                .flatMap(mapping -> Arrays.stream(mapping.value()))
                .collect(Collectors.toSet());

        assertThat(postPaths)
                .contains("/saves/{saveId}/corrections/preview", "/saves/{saveId}/corrections/confirm")
                .doesNotContain("/saves/{saveId}/corrections");
    }
}
