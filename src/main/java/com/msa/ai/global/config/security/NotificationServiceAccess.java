package com.msa.ai.global.config.security;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Component;

/** Account 서비스 토큰의 scope와 지정 Notification 주체를 함께 검사하며 미설정이면 차단합니다. */
@Component("notificationServiceAccess")
public class NotificationServiceAccess {
    private final String subject;

    public NotificationServiceAccess(
            @Value("${ai.personalization.notification-subject:}") String subject) {
        this.subject = subject;
    }

    public boolean allowed(Jwt jwt) {
        if (subject == null
                || subject.isBlank()
                || !subject.equals(jwt.getSubject())
                || !"service".equals(jwt.getClaimAsString("principal_type"))
                || !"notification-service".equals(jwt.getClaimAsString("client_id"))) return false;
        Object scope = jwt.getClaims().get("scope");
        return scope instanceof String s
                && java.util.Arrays.asList(s.split(" ")).contains("ai.personalization.read");
    }
}
