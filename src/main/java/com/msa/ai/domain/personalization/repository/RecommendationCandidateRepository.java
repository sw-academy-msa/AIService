package com.msa.ai.domain.personalization.repository;

import com.msa.ai.domain.personalization.entity.RecommendationCandidate;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

/** 후보 유일성과 발행 이력은 AI DB에서만 관리합니다. */
public interface RecommendationCandidateRepository
        extends JpaRepository<RecommendationCandidate, UUID> {}
