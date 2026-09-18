package com.riskwarning.processing.repository;

import com.riskwarning.common.po.project.Project;
import org.springframework.data.repository.Repository;
import java.util.Optional;

/** 项目信息只读入口，不暴露写操作。 */
public interface ProjectRepository extends Repository<Project, Long> {
    Optional<Project> findById(Long id);
}
