package io.github.illuseahashmap.workflow.process.application.port;

/**
 * Serialises deployments of the same publish id until the current transaction ends, so a request sent twice at
 * the same time still deploys once.
 */
@FunctionalInterface
public interface PublishLock {

    void lock(String tenantId, String publishId);
}
