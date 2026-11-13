package io.github.illuseahashmap.serviceregistry.engine;

/** No deployed service definition matches the key, parent deployment, and tenant. */
public class ServiceDefinitionNotFoundException extends RuntimeException {

    public ServiceDefinitionNotFoundException(String message) {
        super(message);
    }
}
