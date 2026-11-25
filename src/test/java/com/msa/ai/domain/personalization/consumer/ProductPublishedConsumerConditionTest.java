package com.msa.ai.domain.personalization.consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import com.msa.ai.domain.personalization.service.RecommendationService;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import tools.jackson.databind.json.JsonMapper;

/** 맞춤 쿠폰 캠페인만 켠 배포에서도 공개 상품 수신 consumer가 등록되는지 확인합니다. */
class ProductPublishedConsumerConditionTest {
    private final ApplicationContextRunner runner =
            new ApplicationContextRunner()
                    .withBean(RecommendationService.class, () -> mock(RecommendationService.class))
                    .withBean(JsonMapper.class, JsonMapper::new)
                    .withUserConfiguration(ProductPublishedConsumer.class);

    @Test
    void registersWhenOnlyCampaignModeIsOn() {
        for (String mode : new String[] {"NORMAL", "DEMO", "demo"}) {
            runner.withPropertyValues(
                            "ai.personalization.enabled=false", "ai.campaign.mode=" + mode)
                    .run(c -> assertThat(c).hasSingleBean(ProductPublishedConsumer.class));
        }
    }

    @Test
    void registersWhenPersonalizationIsOn() {
        runner.withPropertyValues("ai.personalization.enabled=true", "ai.campaign.mode=OFF")
                .run(c -> assertThat(c).hasSingleBean(ProductPublishedConsumer.class));
    }

    @Test
    void staysOffWhenBothAreOff() {
        runner.withPropertyValues("ai.personalization.enabled=false", "ai.campaign.mode=OFF")
                .run(c -> assertThat(c).doesNotHaveBean(ProductPublishedConsumer.class));
        runner.run(c -> assertThat(c).doesNotHaveBean(ProductPublishedConsumer.class));
    }
}
