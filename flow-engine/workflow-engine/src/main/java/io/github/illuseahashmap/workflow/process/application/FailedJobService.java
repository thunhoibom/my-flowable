package io.github.illuseahashmap.workflow.process.application;

import io.github.illuseahashmap.workflow.process.application.dto.FailedJobView;
import io.github.illuseahashmap.workflow.shared.response.PageResult;

/** Jobs of the current tenant that failed all their retries (Flowable dead-letter jobs). */
public interface FailedJobService {

    PageResult<FailedJobView> page(Integer pageNum, Integer pageSize, String processInstanceId);

    String stacktrace(String jobId);

    /** Moves the job back to the executor with {@code retries} new attempts. */
    void retry(String jobId, int retries);

    void delete(String jobId);
}
