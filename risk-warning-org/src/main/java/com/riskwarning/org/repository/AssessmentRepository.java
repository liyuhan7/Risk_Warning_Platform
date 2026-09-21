package com.riskwarning.org.repository;

import com.riskwarning.common.po.report.Assessment;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface AssessmentRepository extends JpaRepository<Assessment, Integer> {

    /**
     * 取项目最近一次评估。同一项目每次上传确认都会新增一行评估记录，
     * 按项目号取单条结果会因多行而失败，必须显式取最新一行。
     */
    Optional<Assessment> findFirstByProjectIdOrderByIdDesc(long projectId);

    /** 上传确认幂等检查：稳定任务身份对应的评估是否已提交。 */
    Optional<Assessment> findBySourceTaskId(String sourceTaskId);

    Assessment findById(long l);
}
