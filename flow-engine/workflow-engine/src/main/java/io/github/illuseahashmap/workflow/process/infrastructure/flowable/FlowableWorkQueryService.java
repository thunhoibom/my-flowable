package io.github.illuseahashmap.workflow.process.infrastructure.flowable;

import io.github.illuseahashmap.workflow.process.application.WorkQueryService;
import io.github.illuseahashmap.workflow.process.application.dto.InboxTaskView;
import io.github.illuseahashmap.workflow.process.application.dto.MyProcessView;
import io.github.illuseahashmap.workflow.process.application.dto.StartableProcessView;
import io.github.illuseahashmap.workflow.shared.context.CurrentPrincipalProvider;
import io.github.illuseahashmap.workflow.shared.context.TenantProvider;
import io.github.illuseahashmap.workflow.shared.exception.BusinessException;
import io.github.illuseahashmap.workflow.shared.exception.ErrorCode;
import io.github.illuseahashmap.workflow.shared.response.PageResult;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.Comparator;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.flowable.engine.HistoryService;
import org.flowable.engine.RepositoryService;
import org.flowable.engine.RuntimeService;
import org.flowable.engine.TaskService;
import org.flowable.engine.history.HistoricProcessInstance;
import org.flowable.engine.history.HistoricProcessInstanceQuery;
import org.flowable.engine.repository.ProcessDefinition;
import org.flowable.engine.runtime.ProcessInstance;
import org.flowable.task.api.Task;
import org.flowable.task.api.TaskQuery;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

@Service
public class FlowableWorkQueryService implements WorkQueryService {

    private static final int DEFAULT_PAGE_SIZE = 20;
    private static final int MAX_PAGE_SIZE = 100;

    private final TaskService taskService;
    private final RuntimeService runtimeService;
    private final HistoryService historyService;
    private final RepositoryService repositoryService;
    private final CurrentPrincipalProvider principals;
    private final TenantProvider tenants;

    public FlowableWorkQueryService(TaskService taskService, RuntimeService runtimeService,
                                    HistoryService historyService, RepositoryService repositoryService,
                                    CurrentPrincipalProvider principals, TenantProvider tenants) {
        this.taskService = taskService;
        this.runtimeService = runtimeService;
        this.historyService = historyService;
        this.repositoryService = repositoryService;
        this.principals = principals;
        this.tenants = tenants;
    }

    @Override
    public PageResult<InboxTaskView> inbox(Integer pageNum, Integer pageSize, String keyword) {
        int page = page(pageNum);
        int size = size(pageSize);
        // Same rule as TaskViewAssembler.canOperate: the assignee, or a candidate user of an unassigned task.
        TaskQuery query = taskService.createTaskQuery()
                .taskTenantId(tenants.current().tenantId())
                .taskCandidateOrAssigned(username())
                .active();
        if (StringUtils.hasText(keyword)) {
            String like = "%" + keyword.trim() + "%";
            query.or().taskNameLikeIgnoreCase(like).processInstanceBusinessKeyLikeIgnoreCase(like).endOr();
        }
        long total = query.count();
        List<Task> tasks = query.orderByTaskCreateTime().desc().listPage((page - 1) * size, size);
        Map<String, ProcessDefinition> definitions = definitions(
                tasks.stream().map(Task::getProcessDefinitionId).collect(Collectors.toSet()));
        Map<String, String> businessKeys = businessKeys(
                tasks.stream().map(Task::getProcessInstanceId).collect(Collectors.toSet()));
        List<InboxTaskView> records = tasks.stream().map(task -> {
            ProcessDefinition definition = definitions.get(task.getProcessDefinitionId());
            return new InboxTaskView(task.getId(), task.getName(), task.getTaskDefinitionKey(),
                    task.getProcessInstanceId(), task.getProcessDefinitionId(),
                    definition == null ? null : definition.getKey(),
                    definition == null ? null : definition.getName(),
                    businessKeys.get(task.getProcessInstanceId()), task.getAssignee(),
                    time(task.getCreateTime()), time(task.getDueDate()));
        }).toList();
        return new PageResult<>(total, page, size, records);
    }

    @Override
    public PageResult<MyProcessView> myProcesses(Integer pageNum, Integer pageSize, String status) {
        int page = page(pageNum);
        int size = size(pageSize);
        HistoricProcessInstanceQuery query = historyService.createHistoricProcessInstanceQuery()
                .processInstanceTenantId(tenants.current().tenantId())
                .startedBy(username())
                .excludeSubprocesses(true);
        switch (StringUtils.hasText(status) ? status.trim().toLowerCase(Locale.ROOT) : "all") {
            case "all" -> { }
            case "running" -> query.unfinished();
            case "finished" -> query.finished();
            default -> throw new BusinessException(ErrorCode.BAD_REQUEST, "Unsupported status: " + status);
        }
        long total = query.count();
        List<MyProcessView> records = query.orderByProcessInstanceStartTime().desc()
                .listPage((page - 1) * size, size).stream().map(FlowableWorkQueryService::toView).toList();
        return new PageResult<>(total, page, size, records);
    }

    @Override
    public List<StartableProcessView> startable() {
        return repositoryService.createProcessDefinitionQuery()
                .processDefinitionTenantId(tenants.current().tenantId())
                .latestVersion()
                .list().stream()
                .filter(definition -> !definition.isSuspended())
                .map(definition -> new StartableProcessView(definition.getId(), definition.getKey(),
                        definition.getName(), definition.getVersion()))
                .sorted(Comparator.comparing((StartableProcessView view) ->
                                Objects.requireNonNullElse(view.processDefinitionName(), view.processDefinitionKey()),
                        String.CASE_INSENSITIVE_ORDER))
                .toList();
    }

    private Map<String, ProcessDefinition> definitions(Set<String> ids) {
        if (ids.isEmpty()) {
            return Map.of();
        }
        return repositoryService.createProcessDefinitionQuery().processDefinitionIds(ids).list().stream()
                .collect(Collectors.toMap(ProcessDefinition::getId, Function.identity()));
    }

    private Map<String, String> businessKeys(Set<String> ids) {
        if (ids.isEmpty()) {
            return Map.of();
        }
        return runtimeService.createProcessInstanceQuery().processInstanceIds(ids).list().stream()
                .filter(instance -> instance.getBusinessKey() != null)
                .collect(Collectors.toMap(ProcessInstance::getId, ProcessInstance::getBusinessKey));
    }

    private static MyProcessView toView(HistoricProcessInstance instance) {
        String status = instance.getEndTime() == null ? "RUNNING"
                : StringUtils.hasText(instance.getDeleteReason()) ? "TERMINATED" : "FINISHED";
        return new MyProcessView(instance.getId(), instance.getProcessDefinitionId(),
                instance.getProcessDefinitionKey(), instance.getProcessDefinitionName(),
                instance.getProcessDefinitionVersion(), instance.getBusinessKey(), status,
                time(instance.getStartTime()), time(instance.getEndTime()));
    }

    private String username() {
        String username = principals.current().username();
        if (!StringUtils.hasText(username)) {
            throw new BusinessException(ErrorCode.UNAUTHORIZED, "No current user");
        }
        return username;
    }

    private static int page(Integer pageNum) {
        return pageNum == null || pageNum < 1 ? 1 : pageNum;
    }

    private static int size(Integer pageSize) {
        return pageSize == null || pageSize < 1 ? DEFAULT_PAGE_SIZE : Math.min(pageSize, MAX_PAGE_SIZE);
    }

    private static OffsetDateTime time(Date date) {
        return date == null ? null : date.toInstant().atZone(ZoneId.systemDefault()).toOffsetDateTime();
    }
}
