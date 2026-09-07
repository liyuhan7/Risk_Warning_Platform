package com.riskwarning.processing.repository;

import com.riskwarning.common.po.file.ProjectFile;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface ProjectFileRepository extends JpaRepository<ProjectFile, Long> {

    Optional<ProjectFile> findByIdAndProjectIdAndAssessmentId(
            Long id, Long projectId, Long assessmentId);
}
