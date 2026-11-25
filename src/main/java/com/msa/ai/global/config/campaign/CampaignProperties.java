package com.msa.ai.global.config.campaign;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.*;

/** 잘못된 시연 목록은 OFF로 내려 실사용자 대상 계산을 막습니다. */
@ConfigurationProperties("ai.campaign")
public record CampaignProperties(String mode, String demoAccountIds) {
    public CampaignProperties {
        mode = mode == null ? "OFF" : mode.toUpperCase(Locale.ROOT);
        if (!Set.of("OFF", "NORMAL", "DEMO").contains(mode)) mode = "OFF";
        if ("DEMO".equals(mode)) {
            try {
                var ids = parse(demoAccountIds);
                if (ids.isEmpty() || ids.size() > 10) {
                    org.slf4j.LoggerFactory.getLogger(CampaignProperties.class)
                            .error("Invalid demo allowlist; campaign mode OFF");
                    mode = "OFF";
                }
            } catch (RuntimeException ex) {
                org.slf4j.LoggerFactory.getLogger(CampaignProperties.class)
                        .error("Invalid demo allowlist; campaign mode OFF");
                mode = "OFF";
            }
        }
    }

    private static Set<UUID> parse(String s) {
        if (s == null || s.isBlank()) return Set.of();
        var result = new HashSet<UUID>();
        for (String id : s.split(",", -1)) result.add(UUID.fromString(id.trim()));
        return Set.copyOf(result);
    }

    public Set<UUID> accounts() {
        return "DEMO".equals(mode) ? parse(demoAccountIds) : Set.of();
    }

    public boolean demo() {
        return "DEMO".equals(mode);
    }

    public boolean enabled() {
        return !"OFF".equals(mode);
    }

    @Override
    public String toString() {
        return "CampaignProperties[REDACTED]";
    }
}
