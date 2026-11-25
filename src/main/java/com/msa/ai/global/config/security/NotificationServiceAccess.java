package com.msa.ai.global.config.security;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Component;

/** Account 서비스 토큰의 scope와 지정 Notification과 Payment 주체를 함께 검사하며 미설정이면 차단합니다. */
@Component("notificationServiceAccess")
public class NotificationServiceAccess {
    private final String subject;
    private final String paymentSubject;

    public NotificationServiceAccess(
            @Value("${ai.personalization.notification-subject:}") String subject,
            @Value("${ai.personalization.payment-subject:}") String paymentSubject) {
        this.subject = subject;
        this.paymentSubject = paymentSubject;
    }

    public boolean allowed(Jwt jwt) {
        String client = jwt.getClaimAsString("client_id");
        String expected =
                "notification-service".equals(client)
                        ? subject
                        : "payment-service".equals(client) ? paymentSubject : null;
        if (expected == null
                || expected.isBlank()
                || !expected.equals(jwt.getSubject())
                || !"service".equals(jwt.getClaimAsString("principal_type"))) return false;
        Object scope = jwt.getClaims().get("scope");
        return scope instanceof String s
                && java.util.Arrays.asList(s.split(" ")).contains("ai.personalization.read");
    }
}
