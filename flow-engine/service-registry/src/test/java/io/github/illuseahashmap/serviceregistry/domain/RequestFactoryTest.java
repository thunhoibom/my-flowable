package io.github.illuseahashmap.serviceregistry.domain;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class RequestFactoryTest {

    @Test
    void placesResolvedValuesAndKeepsJsonTypes() {
        var operation = new PublishedOperation(
                "createLead", HttpMethod.POST, "https://crm.example/orders/${orderId}",
                AuthType.BEARER, null, "s3cret",
                "{\"name\":null,\"keep\":1}", 5000, List.of("409"), "HTTP_ERROR",
                List.of(
                        new InputParameter("orderId", ParameterType.STRING, true, ParameterLocation.PATH),
                        new InputParameter("q", ParameterType.STRING, false, ParameterLocation.QUERY),
                        new InputParameter("trace", ParameterType.STRING, false, ParameterLocation.HEADER),
                        new InputParameter("customerName", ParameterType.STRING, true, ParameterLocation.BODY),
                        new InputParameter("count", ParameterType.INTEGER, true, ParameterLocation.BODY),
                        new InputParameter("payload", ParameterType.JSON, true, ParameterLocation.BODY),
                        new InputParameter("name", ParameterType.STRING, false, ParameterLocation.BODY),
                        new InputParameter("meta", ParameterType.JSON, true, ParameterLocation.HEADER),
                        new InputParameter("unused", ParameterType.STRING, false, ParameterLocation.PATH)),
                List.of());
        var request = RequestFactory.create(operation, Map.of(
                "orderId", "9",
                "q", "open",
                "trace", "abc",
                "customerName", "Ann",
                "count", 2,
                "payload", Map.of("sku", "A"),
                "meta", Map.of("a", 1)));

        assertEquals("https://crm.example/orders/9?q=open", request.url());
        assertEquals("Bearer s3cret", request.headers().get("Authorization"));
        assertEquals("application/json", request.headers().get("Content-Type"));
        assertEquals("abc", request.headers().get("trace"));
        assertEquals("{\"a\":1}", request.headers().get("meta"));
        assertTrue(request.body().contains("\"customerName\":\"Ann\""));
        assertTrue(request.body().contains("\"count\":2"));
        assertTrue(request.body().contains("\"payload\":{\"sku\":\"A\"}"));
        assertTrue(request.body().contains("\"keep\":1"));
        assertFalse(request.body().contains("\"name\""));
    }

    @Test
    void omitsAbsentOptionalValues() {
        var operation = new PublishedOperation(
                "createLead", HttpMethod.GET, "https://crm.example/leads",
                AuthType.NONE, null, null, null, 1000, List.of(), "HTTP_ERROR",
                List.of(new InputParameter("q", ParameterType.STRING, false, ParameterLocation.QUERY)),
                List.of());
        var request = RequestFactory.create(operation, Map.of());
        assertEquals("https://crm.example/leads", request.url());
        assertFalse(request.headers().containsKey("q"));
    }

    @Test
    void rejectsLeftoverPlaceholderAndNonObjectBody() {
        var placeholder = new PublishedOperation(
                "createLead", HttpMethod.GET, "https://crm.example/${missing}",
                AuthType.NONE, null, null, null, 1000, List.of(), "HTTP_ERROR",
                List.of(), List.of());
        assertThrows(InvalidServiceRequestException.class, () -> RequestFactory.create(placeholder, Map.of()));

        var body = new PublishedOperation(
                "createLead", HttpMethod.POST, "https://crm.example/leads",
                AuthType.NONE, null, null, "[]", 1000, List.of(), "HTTP_ERROR",
                List.of(new InputParameter("name", ParameterType.STRING, true, ParameterLocation.BODY)),
                List.of());
        assertThrows(InvalidServiceRequestException.class, () -> RequestFactory.create(body, Map.of("name", "Ann")));
    }

    @Test
    void fillsTheObjectInsideAnArrayBody() {
        var operation = new PublishedOperation(
                "notifySign", HttpMethod.POST, "https://erp.example/api/contract/notify-sign",
                AuthType.NONE, null, null, "[{}]", 1000, List.of(), "HTTP_ERROR",
                List.of(
                        new InputParameter("econtract_app_id", ParameterType.STRING, true, ParameterLocation.BODY),
                        new InputParameter("emails", ParameterType.JSON, true, ParameterLocation.BODY),
                        new InputParameter("file_id", ParameterType.JSON, true, ParameterLocation.BODY)),
                List.of());
        var request = RequestFactory.create(operation, Map.of(
                "econtract_app_id", "141943",
                "emails", List.of("doitac@example.com"),
                "file_id", List.of("9001")));

        assertEquals(
                "[{\"econtract_app_id\":\"141943\",\"emails\":[\"doitac@example.com\"],\"file_id\":[\"9001\"]}]",
                request.body());
        assertEquals("application/json", request.headers().get("Content-Type"));
    }

    @Test
    void placesApiKeyInNamedHeader() {
        var operation = new PublishedOperation(
                "createLead", HttpMethod.GET, "https://crm.example/leads",
                AuthType.API_KEY, "X-Api-Key", "s3cret",
                null, 1000, List.of(), "HTTP_ERROR",
                List.of(new InputParameter("trace", ParameterType.STRING, false, ParameterLocation.HEADER)),
                List.of());
        var request = RequestFactory.create(operation, Map.of("trace", "1"));
        assertEquals("s3cret", request.headers().get("X-Api-Key"));
        assertEquals("1", request.headers().get("trace"));
        assertFalse(request.headers().containsKey("Authorization"));
    }

    @Test
    void joinsARelativePathAndKeepsAnAbsoluteUrl() {
        var relative = new PublishedOperation(
                "createLead", HttpMethod.GET, "/orders/${orderId}",
                AuthType.NONE, null, null, null, 1000, List.of(), "HTTP_ERROR",
                List.of(new InputParameter("orderId", ParameterType.STRING, true, ParameterLocation.PATH)),
                List.of());
        var request = RequestFactory.create(relative, Map.of("orderId", "15"), "https://crm.example/");
        assertEquals("https://crm.example/orders/15", request.url());

        var absolute = new PublishedOperation(
                "createLead", HttpMethod.GET, "https://other.example/orders",
                AuthType.NONE, null, null, null, 1000, List.of(), "HTTP_ERROR",
                List.of(), List.of());
        // An absolute template ignores the base, including a null base.
        assertEquals("https://other.example/orders", RequestFactory.create(absolute, Map.of(), null).url());
    }

    @Test
    void buildsFormRawAndMultipartBodies() {
        var form = contentOperation(ContentType.FORM, BodyPresence.CONTENT, null, List.of(
                new InputParameter("name", ParameterType.STRING, true, ParameterLocation.BODY)));
        var formRequest = RequestFactory.create(form, Map.of("name", "Ann"));
        assertEquals("name=Ann", formRequest.body());
        assertEquals("application/x-www-form-urlencoded", formRequest.headers().get("Content-Type"));

        var raw = contentOperation(ContentType.RAW, BodyPresence.CONTENT, "text/plain", List.of());
        var rawOperation = new PublishedOperation(
                raw.operationKey(), raw.method(), raw.urlTemplate(), raw.authMode(), raw.authType(),
                raw.apiKeyHeader(), raw.secret(), "hello", raw.connectTimeoutMillis(), raw.readTimeoutMillis(),
                raw.errorRules(), raw.successStatuses(), raw.inputs(), raw.outputs(),
                ContentType.RAW, "text/plain", List.of(), BodyPresence.CONTENT, ApiKeyLocation.HEADER);
        var rawRequest = RequestFactory.create(rawOperation, Map.of());
        assertEquals("hello", rawRequest.body());
        assertEquals("text/plain", rawRequest.headers().get("Content-Type"));

        var file = new InputParameter("doc", ParameterType.STRING, true, ParameterLocation.BODY, PartKind.FILE);
        var text = new InputParameter("title", ParameterType.STRING, true, ParameterLocation.BODY, PartKind.TEXT);
        var multipart = contentOperation(ContentType.MULTIPART, BodyPresence.CONTENT, null, List.of(text, file));
        var multipartRequest = RequestFactory.create(multipart, Map.of("title", "Hi", "doc", new byte[] {1, 2, 3}));
        assertTrue(multipartRequest.headers().get("Content-Type").startsWith("multipart/form-data; boundary="));
        String encoded = new String(multipartRequest.binaryBody(), java.nio.charset.StandardCharsets.ISO_8859_1);
        assertTrue(encoded.contains("name=\"title\""));
        assertTrue(encoded.contains("Hi"));
        assertTrue(encoded.contains("filename=\"doc\""));
        assertTrue(encoded.contains(new String(new byte[] {1, 2, 3}, java.nio.charset.StandardCharsets.ISO_8859_1)));
    }

    @Test
    void sendsAnEmptyBodyAndOmitsAMissingBody() {
        var empty = contentOperation(ContentType.JSON, BodyPresence.EMPTY, null, List.of());
        var emptyRequest = RequestFactory.create(empty, Map.of());
        assertEquals("", emptyRequest.body());
        assertEquals("application/json", emptyRequest.headers().get("Content-Type"));

        var omitted = contentOperation(ContentType.JSON, BodyPresence.NONE, null, List.of());
        var omittedRequest = RequestFactory.create(omitted, Map.of());
        assertEquals(null, omittedRequest.body());
        assertFalse(omittedRequest.headers().containsKey("Content-Type"));
    }

    @Test
    void letsAuthReplaceAStaticHeaderAndPutsAnApiKeyOnTheQuery() {
        var custom = new PublishedOperation(
                "createLead", HttpMethod.GET, "https://crm.example/leads",
                AuthMode.OVERRIDE, AuthType.CUSTOM_HEADER, "X-Api-Token", "tok", null,
                1000, 1000, List.of(), List.of(), List.of(), List.of(),
                ContentType.JSON, null, List.of(), BodyPresence.NONE, ApiKeyLocation.HEADER);
        assertEquals("tok", RequestFactory.create(custom, Map.of()).headers().get("X-Api-Token"));
        var unnamed = new PublishedOperation(
                "createLead", HttpMethod.GET, "https://crm.example/leads",
                AuthMode.OVERRIDE, AuthType.CUSTOM_HEADER, null, "tok", null,
                1000, 1000, List.of(), List.of(), List.of(), List.of(),
                ContentType.JSON, null, List.of(), BodyPresence.NONE, ApiKeyLocation.HEADER);
        assertThrows(InvalidServiceRequestException.class, () -> RequestFactory.create(unnamed, Map.of()));
    }

    private static PublishedOperation contentOperation(
            ContentType contentType, BodyPresence presence, String mediaType, List<InputParameter> inputs) {
        return new PublishedOperation(
                "createLead", HttpMethod.POST, "https://crm.example/leads",
                AuthMode.OVERRIDE, AuthType.NONE, null, null, null,
                1000, 1000, List.of(), List.of(), inputs, List.of(),
                contentType, mediaType, List.of(), presence, ApiKeyLocation.HEADER);
    }
}
