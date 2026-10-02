// java
package com.riskwarning.org.controller;

import com.riskwarning.common.annotation.AuthRequired;
import com.riskwarning.common.dto.UserResponse;
import com.riskwarning.common.enums.RegionEnum;
import com.riskwarning.common.enums.project.IndustryEnum;
import com.riskwarning.common.enums.project.ProjectOrientedUserEnum;
import com.riskwarning.common.enums.project.ProjectStatus;
import com.riskwarning.common.enums.project.ProjectType;
import com.riskwarning.common.result.Result;
import com.riskwarning.common.dto.project.ProjectCreateRequest;
import com.riskwarning.common.dto.project.ProjectMemberResponse;
import com.riskwarning.common.po.project.Project;
import com.riskwarning.common.po.report.Assessment;
import com.riskwarning.org.service.ProjectService;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import javax.validation.Valid;


/**
 * 项目控制器
 */
@RestController
@RequestMapping("/project")
public class ProjectController {

    private final ProjectService projectService;

    public ProjectController(ProjectService projectService) {
        this.projectService = projectService;
    }

    /**
     * 创建项目
     *
     * @param req 项目创建请求
     * @return 创建的项目
     */
    @PostMapping
    @AuthRequired
    public Result<Project> createProject(@Valid @RequestBody ProjectCreateRequest req) {
        Project p = new Project();
        p.setEnterpriseId(req.getEnterpriseId());
        p.setName(req.getName());
        p.setType(ProjectType.fromCode(req.getType()));
        p.setDescription(req.getDescription());
        p.setStartDate(req.getStartDate());
        p.setStatus(ProjectStatus.IN_PROGRESS);
        p.setPlannedCompletionDate(req.getPlannedCompletionDate());
        p.setIndustry(IndustryEnum.fromCode(req.getIndustry()));
        p.setRegion(RegionEnum.fromCode(req.getRegion()));
        p.setOrientedUser(ProjectOrientedUserEnum.fromCode(req.getOrientedUser()));

        Project saved = projectService.createProject(p);
        return Result.success(saved);
    }

    /**
     * 将成员添加到项目
     *
     * @param projectId 项目ID
     * @param req 添加成员请求
     * @return 操作结果
     */
    @PostMapping("/{projectId}/members")
    public Result<Void> addMember(@PathVariable Long projectId, @RequestBody com.riskwarning.common.dto.AddMemberRequest req) {
        projectService.addUserToProject(projectId, req.getUserId(), req.getRole());
        return Result.success();
    }

    /**
     * 获取所有项目
     *
     * @return 项目列表
     */
    @GetMapping
    public Result<List<Project>> getAllProjects() {
        return Result.success(projectService.getAllProjects());
    }

    /**
     * 根据项目ID获取项目成员列表
     *
     * @param projectId 项目ID
     * @return 项目成员列表
     */
    @GetMapping("/{projectId}/members")
    public Result<List<ProjectMemberResponse>> getProjectMembers(@PathVariable Long projectId) {
        return Result.success(projectService.getProjectMembers(projectId));
    }

    /**
     * 根据项目ID查询评估记录
     *
     * @param projectId 项目ID
     * @return 评估记录
     */
    @GetMapping("/{projectId}/assessment")
    public Result<Assessment> getAssessmentByProjectId(@PathVariable Long projectId) {
        return Result.success(projectService.getAssessmentByProjectId(projectId));
    }
}
