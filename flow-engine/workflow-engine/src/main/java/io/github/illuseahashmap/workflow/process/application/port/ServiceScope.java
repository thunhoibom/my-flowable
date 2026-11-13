package io.github.illuseahashmap.workflow.process.application.port;

/**
 * Where a service task looks up its service definition. {@code parentDeploymentId} is the deployment of the
 * running process definition, like Flowable's ProcessDefinitionUtil.getDefinitionDeploymentId. Null when the task
 * asks for the latest version ({@code sameDeployment="false"}).
 */
public record ServiceScope(String tenantId, String parentDeploymentId) {

    public static ServiceScope latest(String tenantId) {
        return new ServiceScope(tenantId, null);
    }
}
