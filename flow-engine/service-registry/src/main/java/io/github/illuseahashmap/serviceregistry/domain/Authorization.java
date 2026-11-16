package io.github.illuseahashmap.serviceregistry.domain;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * How a service or an operation authenticates, as written in the model. It names its credentials (a secret, an
 * OAuth2 client, an expression) and never holds a secret value; the values come from this Flow Engine at call time.
 *
 * <ul>
 *   <li>BASIC: {@code username}, {@code value} = password</li>
 *   <li>BEARER: {@code value} = token</li>
 *   <li>API_KEY: {@code headerName} (header or query name), {@code apiKeyLocation}, {@code value} = key</li>
 *   <li>CUSTOM_HEADER: {@code headerName}, {@code value}</li>
 *   <li>MTLS: {@code certificatePem} (public, written in the model), {@code value} = private key</li>
 *   <li>HMAC: {@code headerName} = signature header, {@code value} = key</li>
 *   <li>AWS_SIG_V4: {@code accessKey}, {@code value} = secret key, {@code region}, {@code awsService}</li>
 * </ul>
 */
public record Authorization(
        AuthType type,
        String headerName,
        ApiKeyLocation apiKeyLocation,
        CredentialValue username,
        CredentialValue value,
        String certificatePem,
        CredentialValue accessKey,
        String region,
        String awsService) {

    public static final Authorization NONE =
            new Authorization(AuthType.NONE, null, null, null, null, null, null, null, null);

    /** {@code ${tenant.name}}: tenant variables an expression reads. */
    private static final Pattern TENANT_VARIABLE = Pattern.compile("\\$\\{\\s*tenant\\.([A-Za-z_][A-Za-z0-9_]*)");

    private static final Set<CredentialSource> SECRET_ONLY = EnumSet.of(CredentialSource.SECRET);
    private static final Set<CredentialSource> SECRET_VALUE =
            EnumSet.of(CredentialSource.SECRET, CredentialSource.EXPRESSION);
    private static final Set<CredentialSource> TOKEN =
            EnumSet.of(CredentialSource.SECRET, CredentialSource.EXPRESSION, CredentialSource.OAUTH2_CLIENT);
    private static final Set<CredentialSource> ANY = EnumSet.allOf(CredentialSource.class);
    private static final Set<CredentialSource> PLAIN =
            EnumSet.of(CredentialSource.VALUE, CredentialSource.EXPRESSION, CredentialSource.SECRET);
    private static final Set<CredentialSource> PLAIN_OR_SECRET = EnumSet.of(CredentialSource.VALUE, CredentialSource.SECRET);

    public Authorization {
        type = type == null ? AuthType.NONE : type;
        apiKeyLocation = apiKeyLocation == null ? ApiKeyLocation.HEADER : apiKeyLocation;
    }

    public static Authorization orNone(Authorization authorization) {
        return authorization == null ? NONE : authorization;
    }

    /**
     * What is wrong with this authorization, empty when it can be deployed. A secret (password, token, key,
     * private key) can never be written in the model: it is a secret, an expression or an OAuth2 client.
     */
    public List<String> problems() {
        List<String> problems = new ArrayList<>();
        switch (type) {
            case NONE -> {
                // Nothing to check.
            }
            case BASIC -> {
                check(problems, "username", username, PLAIN);
                check(problems, "password", value, SECRET_VALUE);
            }
            case BEARER -> check(problems, "token", value, TOKEN);
            case API_KEY -> {
                requireText(problems, "header", headerName);
                check(problems, "key", value, SECRET_VALUE);
            }
            case CUSTOM_HEADER -> {
                requireText(problems, "header", headerName);
                check(problems, "value", value, ANY);
            }
            case MTLS -> {
                requireText(problems, "certificatePem", certificatePem);
                check(problems, "privateKey", value, SECRET_ONLY);
            }
            case HMAC -> {
                requireText(problems, "header", headerName);
                check(problems, "key", value, SECRET_ONLY);
            }
            case AWS_SIG_V4 -> {
                check(problems, "accessKey", accessKey, PLAIN_OR_SECRET);
                check(problems, "secretKey", value, SECRET_ONLY);
                requireText(problems, "region", region);
                requireText(problems, "awsService", awsService);
            }
        }
        return problems;
    }

    /** Names of the secrets this authorization reads. */
    public Set<String> secrets() {
        return names(CredentialSource.SECRET);
    }

    /** Registration keys of the OAuth2 clients this authorization uses. */
    public Set<String> oauth2Clients() {
        return names(CredentialSource.OAUTH2_CLIENT);
    }

    /** Tenant variables read by the expressions of this authorization. */
    public Set<String> tenantVariables() {
        Set<String> names = new TreeSet<>();
        for (CredentialValue credential : credentials()) {
            if (credential.source() == CredentialSource.EXPRESSION) {
                names.addAll(tenantVariables(credential.value()));
            }
        }
        return names;
    }

    /** Tenant variables read by {@code ${tenant.<name>}} in a text, such as a base URL. */
    public static Set<String> tenantVariables(String text) {
        Set<String> names = new TreeSet<>();
        if (text != null) {
            Matcher matcher = TENANT_VARIABLE.matcher(text);
            while (matcher.find()) {
                names.add(matcher.group(1));
            }
        }
        return names;
    }

    private Set<String> names(CredentialSource source) {
        Set<String> names = new TreeSet<>();
        for (CredentialValue credential : credentials()) {
            if (credential.source() == source && !credential.missing()) {
                names.add(credential.value().trim());
            }
        }
        return names;
    }

    private List<CredentialValue> credentials() {
        if (type == AuthType.NONE) {
            return List.of();
        }
        List<CredentialValue> credentials = new ArrayList<>();
        for (CredentialValue credential : new CredentialValue[] {username, value, accessKey}) {
            if (credential != null) {
                credentials.add(credential);
            }
        }
        return credentials;
    }

    private static void check(List<String> problems, String field, CredentialValue credential,
                              Set<CredentialSource> allowed) {
        if (credential == null || credential.missing()) {
            problems.add(field + " là bắt buộc");
        } else if (!allowed.contains(credential.source())) {
            problems.add(field + " không được lấy từ " + credential.source());
        }
    }

    private static void requireText(List<String> problems, String field, String value) {
        if (value == null || value.isBlank()) {
            problems.add(field + " là bắt buộc");
        }
    }
}
