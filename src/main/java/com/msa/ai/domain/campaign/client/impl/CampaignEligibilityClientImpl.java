package com.msa.ai.domain.campaign.client.impl;

import com.msa.ai.domain.campaign.client.*;
import com.msa.ai.global.config.client.ClientProperties;

import org.springframework.stereotype.Component;

import tools.jackson.databind.ObjectMapper;

import java.util.*;

/** 외부 동의 조회 실패는 거절로 확정하지 않고 다음 회차에서 재평가합니다. */
@Component
public class CampaignEligibilityClientImpl implements CampaignEligibilityClient {
    private final InternalHttp http;
    private final ServiceTokenProvider tokens;
    private final ClientProperties config;
    private final ObjectMapper json;

    public CampaignEligibilityClientImpl(
            InternalHttp http,
            ServiceTokenProvider tokens,
            ClientProperties config,
            ObjectMapper json) {
        this.http = http;
        this.tokens = tokens;
        this.config = config;
        this.json = json;
    }

    public boolean allowed(UUID account) {
        var response =
                http.send(
                        config.account(),
                        "GET",
                        "/internal/v1/notification-eligibility?accountId="
                                + account
                                + "&purpose=PERSONALIZED",
                        Map.of("Authorization", "Bearer " + tokens.token()),
                        null);
        if (response.status() == 401) tokens.invalidate();
        if (response.status() != 200) throw new RemoteUnavailable();
        var body = json.readTree(response.body());
        if (!body.path("success").asBoolean() || !body.path("data").path("eligible").isBoolean())
            throw new RemoteUnavailable();
        return body.path("data").path("eligible").asBoolean();
    }
}
