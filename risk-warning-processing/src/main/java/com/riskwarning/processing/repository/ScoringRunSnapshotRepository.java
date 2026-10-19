package com.riskwarning.processing.repository;

import com.riskwarning.common.po.analysis.ScoringRunSnapshot;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ScoringRunSnapshotRepository extends JpaRepository<ScoringRunSnapshot, String> { }
