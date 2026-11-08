package com.msa.ai.domain.personalization.repository;

import com.msa.ai.domain.personalization.entity.GaSyncState;

import jakarta.persistence.LockModeType;

import org.springframework.data.jpa.repository.*;

import java.time.Instant;
import java.util.Optional;

/** 초기 제어 행 생성과 임대 잠금 SQL을 서비스 밖에 모읍니다. */
public interface GaSyncStateRepository extends JpaRepository<GaSyncState, String> {
    @Modifying
    @Query(
            value =
                    "insert into ai.ga_sync_state(id,next_attempt_at) values ('ga4',:now) on"
                        + " conflict (id) do nothing",
            nativeQuery = true)
    int initialize(Instant now);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select s from GaSyncState s where s.id='ga4'")
    Optional<GaSyncState> lock();
}
