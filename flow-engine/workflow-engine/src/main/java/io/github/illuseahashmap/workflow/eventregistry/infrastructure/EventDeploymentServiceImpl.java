package io.github.illuseahashmap.workflow.eventregistry.infrastructure;

import io.github.illuseahashmap.workflow.config.EventRegistryProperties;
import io.github.illuseahashmap.workflow.eventregistry.application.EventDeploymentService;
import io.github.illuseahashmap.workflow.eventregistry.application.dto.DeployedModelView;
import io.github.illuseahashmap.workflow.eventregistry.application.dto.EventDeploymentRequest;
import io.github.illuseahashmap.workflow.eventregistry.application.dto.EventDeploymentResult;
import io.github.illuseahashmap.workflow.eventregistry.application.port.EventReferenceRepository;
import io.github.illuseahashmap.workflow.eventregistry.application.port.EventRegistryGateway;
import io.github.illuseahashmap.workflow.eventregistry.domain.EventModelDefinition;
import io.github.illuseahashmap.workflow.eventregistry.domain.EventModelRules;
import io.github.illuseahashmap.workflow.eventregistry.domain.KeyDetectionMode;
import io.github.illuseahashmap.workflow.eventregistry.domain.RegistryModelType;
import io.github.illuseahashmap.workflow.eventregistry.domain.RestChannelDefinition;
import io.github.illuseahashmap.workflow.eventregistry.domain.RestChannelRules;
import io.github.illuseahashmap.workflow.process.application.WorkflowOperationAuditService;
import io.github.illuseahashmap.workflow.shared.context.TenantContext;
import io.github.illuseahashmap.workflow.shared.context.TenantProvider;
import io.github.illuseahashmap.workflow.shared.exception.BusinessException;
import io.github.illuseahashmap.workflow.shared.exception.ErrorCode;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

@Service
public class EventDeploymentServiceImpl implements EventDeploymentService {

    private final EventRegistryGateway gateway;
    private final EventReferenceRepository references;
    private final WorkflowOperationAuditService auditService;
    private final TenantProvider tenants;
    private final EventRegistryProperties properties;

    public EventDeploymentServiceImpl(EventRegistryGateway gateway, EventReferenceRepository references,
                                      WorkflowOperationAuditService auditService,
                                      TenantProvider tenants, EventRegistryProperties properties) {
        this.gateway = gateway;
        this.references = references;
        this.auditService = auditService;
        this.tenants = tenants;
        this.properties = properties;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public EventDeploymentResult deploy(EventDeploymentRequest request) {
        RegistrySupport.requireEnabled(properties);
        TenantContext.TenantInfo tenant = tenants.current();
        if (StringUtils.hasText(request.tenantId()) && !request.tenantId().equals(tenant.tenantId())
                && !request.tenantId().equals(tenant.tenantCode())) {
            throw new BusinessException(ErrorCode.FORBIDDEN, "tenantId does not match the tenant of the caller");
        }
        if (!StringUtils.hasText(request.publishId())) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "publishId is required");
        }
        List<EventModelDefinition> events = events(request);
        List<RestChannelDefinition> channels = channels(request);
        String tenantId = tenant.tenantId();
        List<EventDeploymentResult.Item> eventItems = new ArrayList<>();
        for (EventModelDefinition event : events) {
            eventItems.add(deploy(tenantId, RegistryModelType.EVENT, event.key(),
                    FlowableEventRegistryJson.event(event), request.publishId()));
        }
        Set<String> missing = new TreeSet<>();
        for (RestChannelDefinition channel : channels) {
            channel.allowedEventKeys().stream()
                    .filter(eventKey -> gateway.publishedEventFields(tenantId, eventKey).isEmpty())
                    .forEach(missing::add);
        }
        if (!missing.isEmpty()) {
            throw new BusinessException(ErrorCode.BAD_REQUEST,
                    "Các sự kiện chưa được phát hành: " + String.join(", ", missing));
        }
        List<EventDeploymentResult.Item> channelItems = new ArrayList<>();
        for (RestChannelDefinition channel : channels) {
            channelItems.add(deploy(tenantId, RegistryModelType.CHANNEL, channel.key(),
                    FlowableEventRegistryJson.channel(channel, tenantId), request.publishId()));
        }
        return new EventDeploymentResult(List.copyOf(eventItems), List.copyOf(channelItems));
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<DeployedModelView> event(String key) {
        String tenantId = tenants.current().tenantId();
        return gateway.latest(tenantId, RegistryModelType.EVENT, key).map(latest -> new DeployedModelView(key,
                latest.version(), gateway.publishedEventFields(tenantId, key).orElse(List.of()).stream()
                        .map(RegistryDraftJson::view).toList()));
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<DeployedModelView> channel(String key) {
        return gateway.latest(tenants.current().tenantId(), RegistryModelType.CHANNEL, key)
                .map(latest -> new DeployedModelView(key, latest.version(), null));
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void deleteEvent(String key) {
        RegistrySupport.requireEnabled(properties);
        String tenantId = tenants.current().tenantId();
        long waiting = gateway.waitingSubscriptions(tenantId, key);
        if (waiting > 0) {
            throw new BusinessException(ErrorCode.CONFLICT, "Còn " + waiting + " công việc đang chờ sự kiện này");
        }
        if (references.latestProcessDefinitionsUsing(tenantId, key) > 0) {
            throw new BusinessException(ErrorCode.CONFLICT, "Quy trình đang dùng sự kiện này");
        }
        gateway.deleteDeployments(tenantId, RegistryModelType.EVENT, key);
        auditService.record("EVENT_MODEL_UNDEPLOYED", null, null, null, key, "DEPLOYED", "DELETED", null);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void deleteChannel(String key) {
        RegistrySupport.requireEnabled(properties);
        gateway.deleteDeployments(tenants.current().tenantId(), RegistryModelType.CHANNEL, key);
        auditService.record("EVENT_CHANNEL_UNDEPLOYED", null, null, null, key, "DEPLOYED", "DELETED", null);
    }

    private EventDeploymentResult.Item deploy(String tenantId, RegistryModelType type, String key, String json,
                                              String publishId) {
        Optional<EventRegistryGateway.Latest> latest = gateway.latest(tenantId, type, key);
        String sha = FlowableEventRegistryJson.sha256(json);
        if (latest.isPresent() && sha.equals(FlowableEventRegistryJson.sha256(latest.get().json()))) {
            return new EventDeploymentResult.Item(key, latest.get().version(), false);
        }
        EventRegistryGateway.Deployed deployed = type == RegistryModelType.EVENT
                ? gateway.deployEvent(tenantId, key, json)
                : gateway.deployChannel(tenantId, key, json);
        auditService.record(type == RegistryModelType.EVENT ? "EVENT_MODEL_DEPLOYED" : "EVENT_CHANNEL_DEPLOYED",
                null, null, null, key, null, "DEPLOYED",
                "version=" + deployed.version() + ", publishId=" + publishId);
        return new EventDeploymentResult.Item(key, deployed.version(), true);
    }

    private static List<EventModelDefinition> events(EventDeploymentRequest request) {
        List<EventModelDefinition> events = new ArrayList<>();
        Set<String> keys = new HashSet<>();
        for (EventDeploymentRequest.Event event : nullToEmpty(request.events())) {
            EventModelDefinition definition = new EventModelDefinition(event.key(), event.name().trim(),
                    RegistrySupport.trimToNull(event.description()),
                    event.fields().stream().map(RegistryDraftJson::field).toList());
            requireUnique(keys, definition.key(), "Sự kiện");
            requireValid("Sự kiện " + definition.key(), EventModelRules.validate(definition));
            events.add(definition);
        }
        return events;
    }

    private static List<RestChannelDefinition> channels(EventDeploymentRequest request) {
        List<RestChannelDefinition> channels = new ArrayList<>();
        Set<String> keys = new HashSet<>();
        for (EventDeploymentRequest.Channel channel : nullToEmpty(request.channels())) {
            RestChannelDefinition definition = new RestChannelDefinition(channel.key(), channel.name().trim(),
                    RegistrySupport.trimToNull(channel.description()), detection(channel.detection()),
                    channel.detectionValue().trim(), trimmed(channel.allowedEventKeys()), trimmed(channel.userIds()),
                    trimmed(channel.groupIds()));
            requireUnique(keys, definition.key(), "Kênh");
            requireValid("Kênh " + definition.key(), RestChannelRules.validate(definition));
            channels.add(definition);
        }
        return channels;
    }

    private static void requireUnique(Set<String> keys, String key, String label) {
        if (!keys.add(key)) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, label + " " + key + " bị gửi hai lần");
        }
    }

    private static void requireValid(String subject, List<String> problems) {
        if (!problems.isEmpty()) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, subject + ": " + String.join("; ", problems));
        }
    }

    /** Null for an unknown mode; the rules then report it. */
    private static KeyDetectionMode detection(String value) {
        String wanted = value.trim().toUpperCase(Locale.ROOT);
        return Arrays.stream(KeyDetectionMode.values()).filter(mode -> mode.name().equals(wanted))
                .findFirst().orElse(null);
    }

    private static List<String> trimmed(List<String> values) {
        return values == null ? List.of() : values.stream().filter(Objects::nonNull).map(String::trim).toList();
    }

    private static <T> List<T> nullToEmpty(List<T> values) {
        return values == null ? List.of() : values;
    }
}
