package com.wenxian.game.v014.service;

import com.wenxian.game.auth.AuthService.UserIdentity;
import com.wenxian.game.v014.ai.V014CharacterRouteAgent;
import com.wenxian.game.v014.model.V014ApiModels.CharacterRouteRequest;
import com.wenxian.game.v014.model.V014ApiModels.CharacterRouteResponse;
import com.wenxian.game.v014.model.V014State.WorldProfile;
import com.wenxian.game.v014.service.V014ModelPreferenceService.ResolvedRoute;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

@Service
public class V014CharacterRouteService {
    private final V014WorldCatalog worlds;
    private final V014ModelPreferenceService models;
    private final V014CharacterRouteAgent agent;
    private final String baseUrl;

    public V014CharacterRouteService(V014WorldCatalog worlds, V014ModelPreferenceService models,
                                     V014CharacterRouteAgent agent,
                                     @Value("${deepseek.base-url:https://api.deepseek.com}") String baseUrl) {
        this.worlds = worlds;
        this.models = models;
        this.agent = agent;
        this.baseUrl = baseUrl;
    }

    public CharacterRouteResponse generate(UserIdentity user, CharacterRouteRequest request) {
        WorldProfile world = worlds.resolve(request.worldCode(), request.customWorld());
        return new CharacterRouteResponse(agent.generate(user.userId(), world, request,
                lightweight(models.resolve(user.userId(), baseUrl))));
    }

    static ResolvedRoute lightweight(ResolvedRoute selected) {
        String model = "DEEPSEEK".equals(selected.provider()) && "deepseek-v4-pro".equals(selected.model())
                ? "deepseek-v4-flash" : selected.model();
        return new ResolvedRoute(selected.baseUrl(), selected.apiKey(), selected.provider(), selected.adapterType(),
                model, selected.credentialSource(), selected.hasCustomCredential());
    }
}
