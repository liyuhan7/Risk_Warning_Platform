package com.riskwarning.org.service.impl;

import com.riskwarning.common.context.UserContext;
import com.riskwarning.common.exception.BusinessException;
import com.riskwarning.common.dto.UserResponse;
import com.riskwarning.common.dto.project.ProjectMemberResponse;
import com.riskwarning.common.po.project.Project;
import com.riskwarning.common.po.project.ProjectMember;
import com.riskwarning.common.po.project.ProjectMemberId;
import com.riskwarning.common.po.report.Assessment;
import com.riskwarning.org.repository.AssessmentRepository;
import com.riskwarning.org.repository.ProjectMemberRepository;
import com.riskwarning.org.repository.ProjectRepository;
import com.riskwarning.org.service.ProjectService;
import com.riskwarning.common.po.user.User;
import com.riskwarning.common.enums.project.ProjectRole;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.persistence.EntityManager;
import java.time.LocalDateTime;
import java.util.List;
import java.util.stream.Collectors;

@Service
public class ProjectServiceImpl implements ProjectService {

    private final ProjectRepository projectRepository;
    private final ProjectMemberRepository projectMemberRepository;
    private final AssessmentRepository assessmentRepository;
    private final EntityManager em;

    public ProjectServiceImpl(ProjectRepository projectRepository,
                              ProjectMemberRepository projectMemberRepository,
                              AssessmentRepository assessmentRepository,
                              EntityManager em) {
        this.projectRepository = projectRepository;
        this.projectMemberRepository = projectMemberRepository;
        this.assessmentRepository = assessmentRepository;
        this.em = em;
    }

    @Override
    @Transactional
    public Project createProject(Project project) {
        LocalDateTime now = LocalDateTime.now();
        project.setCreatedAt(now);
        project.setUpdatedAt(now);
        Project saved = projectRepository.save(project);

        User currentUser = UserContext.getUser();
        Long ownerUserId = currentUser == null ? null : currentUser.getId();
        if (ownerUserId != null) {
            User owner = em.find(User.class, ownerUserId);
            if (owner == null) {
                throw new BusinessException(404, "项目创建用户不存在");
            }

            ProjectMemberId id = new ProjectMemberId();
            id.setProjectId(saved.getId());
            id.setUserId(ownerUserId);


            ProjectMember pm = new ProjectMember();
            pm.setId(id);
            pm.setProject(saved);
            pm.setUser(owner);
            pm.setRole(ProjectRole.PROJECT_ADMIN);
            projectMemberRepository.save(pm);
        }

        return saved;
    }

    @Override
    @Transactional
    public void addUserToProject(Long projectId, Long userId, String role) {
        requirePositiveId(projectId, "项目 ID");
        requirePositiveId(userId, "用户 ID");
        Project project = projectRepository.findById(projectId)
                .orElseThrow(() -> new BusinessException(404, "项目不存在"));

        User user = em.find(User.class, userId);
        if (user == null) {
            throw new BusinessException(404, "用户不存在");
        }

        ProjectRole roleEnum = ProjectRole.from(role);

        ProjectMemberId id = new ProjectMemberId();
        id.setProjectId(projectId);
        id.setUserId(userId);

        ProjectMember pm = new ProjectMember();
        pm.setProject(project);
        pm.setUser(user);
        pm.setId(id);
        pm.setRole(roleEnum);

        projectMemberRepository.save(pm);
    }

    @Override
    @Transactional
    public List<Project> getAllProjects() {
        return projectRepository.findAll();
    }

    @Override
    @Transactional
    public List<ProjectMemberResponse> getProjectMembers(Long projectId) {
        requirePositiveId(projectId, "项目 ID");
        projectRepository.findById(projectId)
                .orElseThrow(() -> new BusinessException(404, "项目不存在"));

        List<ProjectMember> all = projectMemberRepository.findAll();

        return all.stream()
                .filter(pm -> {
                    if (pm == null) return false;
                    ProjectMemberId id = pm.getId();
                    return id != null && projectId.equals(id.getProjectId());
                })
                .map(pm -> {
                    ProjectMemberId id = pm.getId();
                    User u = em.find(User.class, id.getUserId());
                    UserResponse ur = new UserResponse(
                            u.getId(),
                            u.getEmail(),
                            u.getFullName(),
                            u.getAvatarUrl(),
                            u.getCreatedAt(),
                            u.getUpdatedAt()
                    );
                    String roleCode = pm.getRole() == null ? null : pm.getRole().toString();
                    return new ProjectMemberResponse(ur, roleCode);
                })
                .collect(Collectors.toList());
    }

    @Override
    @Transactional(readOnly = true)
    public Assessment getAssessmentByProjectId(Long projectId) {
        requirePositiveId(projectId, "项目 ID");
        projectRepository.findById(projectId)
                .orElseThrow(() -> new BusinessException(404, "项目不存在"));

        return assessmentRepository.findFirstByProjectIdOrderByIdDesc(projectId)
                .orElseThrow(() -> new BusinessException(404, "项目评估不存在"));
    }

    private void requirePositiveId(Long id, String name) {
        if (id == null || id <= 0) {
            throw new BusinessException(400, name + " 必须为正数");
        }
    }
}
