package io.github.illuseahashmap.workflow.process.application;

import io.github.illuseahashmap.workflow.process.application.dto.InboxTaskView;
import io.github.illuseahashmap.workflow.process.application.dto.MyProcessView;
import io.github.illuseahashmap.workflow.process.application.dto.StartableProcessView;
import io.github.illuseahashmap.workflow.shared.response.PageResult;
import java.util.List;

/** Runtime API reads for the Work area, always for the current user in the current tenant. */
public interface WorkQueryService {

    /** Running tasks the user can act on, newest first. {@code keyword} matches task name or business key. */
    PageResult<InboxTaskView> inbox(Integer pageNum, Integer pageSize, String keyword);

    /** Instances the user started, newest first. {@code status}: all, running or finished. */
    PageResult<MyProcessView> myProcesses(Integer pageNum, Integer pageSize, String status);

    /** Processes the user can start now, by name. */
    List<StartableProcessView> startable();
}
