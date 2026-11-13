package io.github.illuseahashmap.workflow.process.application;

import io.github.illuseahashmap.workflow.process.application.dto.ProcessDefinitionSummaryView;
import io.github.illuseahashmap.workflow.process.application.dto.ProcessReleaseView;
import io.github.illuseahashmap.workflow.process.application.dto.PublishProcessRequest;
import io.github.illuseahashmap.workflow.process.application.dto.PublishProcessResult;
import io.github.illuseahashmap.workflow.process.application.dto.RollbackProcessRequest;
import io.github.illuseahashmap.workflow.shared.response.PageResult;
import java.util.List;

/**
 * Publishing from Design: every change of what runs goes to Flow Engine through its Deployment API. The publish
 * history stays here ({@code workflow_process_release}).
 */
public interface ProcessPublishService {

    PublishProcessResult publish(String processDefinitionKey, PublishProcessRequest request);

    /** CI/SERVICE path: overwrite the draft with this XML, then publish it. Unchanged XML is a no-op. */
    PublishProcessResult publishXml(String processDefinitionKey, String name, String bpmnXml);

    PublishProcessResult rollback(String processDefinitionKey, RollbackProcessRequest request);

    ProcessReleaseView suspend(String processDefinitionKey);

    ProcessReleaseView resume(String processDefinitionKey);

    List<ProcessReleaseView> releases(String processDefinitionKey);

    /** Drafts and deployed processes together, as the process list of Design shows them. */
    PageResult<ProcessDefinitionSummaryView> page(Integer pageNum, Integer pageSize, String key, String name,
                                                  String publishStatus);

    /** Deletes the process in Flow Engine (if deployed), then its draft and publish history. */
    void delete(String processDefinitionKey);
}
