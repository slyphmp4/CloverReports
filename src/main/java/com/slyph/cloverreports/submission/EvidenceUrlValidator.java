package com.slyph.cloverreports.submission;

import com.slyph.cloverreports.CloverReports;

import java.net.IDN;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

public final class EvidenceUrlValidator {

    private static final Pattern DOMAIN = Pattern.compile("[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?(?:\\.[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?)+");
    private static final Pattern IPV4 = Pattern.compile("(?:\\d{1,3}\\.){3}\\d{1,3}");
    private final CloverReports plugin;

    public EvidenceUrlValidator(CloverReports plugin) {
        this.plugin = plugin;
    }

    public ValidationResult validate(String input) {
        if (input == null || input.isBlank()) {
            return ValidationResult.failure(Status.EMPTY);
        }

        String value = input.trim();
        int maximumLength = Math.max(64, plugin.getConfig().getInt("report.evidence.max-url-length", 2048));
        if (value.length() > maximumLength) {
            return ValidationResult.failure(Status.TOO_LONG);
        }
        for (int index = 0; index < value.length(); index++) {
            char character = value.charAt(index);
            if (Character.isISOControl(character) || Character.isWhitespace(character)) {
                return ValidationResult.failure(Status.INVALID_FORMAT);
            }
        }
        String lowerValue = value.toLowerCase(Locale.ROOT);
        if (lowerValue.contains("%00") || lowerValue.contains("%0a") || lowerValue.contains("%0d")) {
            return ValidationResult.failure(Status.INVALID_FORMAT);
        }

        URI uri;
        try {
            uri = new URI(value);
        } catch (URISyntaxException exception) {
            return ValidationResult.failure(Status.INVALID_FORMAT);
        }
        if (!uri.isAbsolute() || uri.isOpaque()) {
            return ValidationResult.failure(Status.INVALID_FORMAT);
        }

        String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
        if (!scheme.equals("http") && !scheme.equals("https")) {
            return ValidationResult.failure(Status.INVALID_SCHEME);
        }
        if (plugin.getConfig().getBoolean("report.evidence.require-https", true) && !scheme.equals("https")) {
            return ValidationResult.failure(Status.HTTPS_REQUIRED);
        }
        if (uri.getRawUserInfo() != null) {
            return ValidationResult.failure(Status.CREDENTIALS_NOT_ALLOWED);
        }

        String host = normalizeHost(uri.getHost());
        if (host == null || isPrivateHost(host)) {
            return ValidationResult.failure(Status.INVALID_HOST);
        }
        Set<String> allowedHosts = getAllowedHosts();
        boolean restrictHosts = !plugin.getConfig().getStringList("report.evidence.allowed-hosts").isEmpty();
        if (restrictHosts && allowedHosts.stream().noneMatch(allowed -> host.equals(allowed) || host.endsWith("." + allowed))) {
            return ValidationResult.failure(Status.HOST_NOT_ALLOWED);
        }

        int port = uri.getPort();
        if (port < -1 || port > 65_535) {
            return ValidationResult.failure(Status.PORT_NOT_ALLOWED);
        }
        if (port != -1 && !getAllowedPorts().contains(port)) {
            return ValidationResult.failure(Status.PORT_NOT_ALLOWED);
        }

        int normalizedPort = port;
        if ((scheme.equals("http") && port == 80) || (scheme.equals("https") && port == 443)) {
            normalizedPort = -1;
        }
        String normalized;
        try {
            normalized = buildNormalizedUrl(uri, scheme, host, normalizedPort);
        } catch (IllegalArgumentException exception) {
            return ValidationResult.failure(Status.INVALID_FORMAT);
        }
        if (normalized.length() > maximumLength) {
            return ValidationResult.failure(Status.TOO_LONG);
        }
        return ValidationResult.success(normalized);
    }

    private String normalizeHost(String rawHost) {
        if (rawHost == null || rawHost.isBlank()) {
            return null;
        }
        String host = rawHost.toLowerCase(Locale.ROOT);
        while (host.endsWith(".")) {
            host = host.substring(0, host.length() - 1);
        }
        try {
            host = IDN.toASCII(host, IDN.USE_STD3_ASCII_RULES).toLowerCase(Locale.ROOT);
        } catch (IllegalArgumentException exception) {
            return null;
        }
        return DOMAIN.matcher(host).matches() ? host : null;
    }

    private boolean isPrivateHost(String host) {
        return IPV4.matcher(host).matches()
                || host.indexOf(':') >= 0
                || host.equals("localhost")
                || host.endsWith(".localhost")
                || host.endsWith(".local")
                || host.endsWith(".internal")
                || host.endsWith(".lan")
                || host.endsWith(".home");
    }

    private Set<String> getAllowedHosts() {
        Set<String> result = new HashSet<>();
        for (String configured : plugin.getConfig().getStringList("report.evidence.allowed-hosts")) {
            String value = configured == null ? "" : configured.trim().toLowerCase(Locale.ROOT);
            if (value.startsWith("*.")) {
                value = value.substring(2);
            }
            while (value.startsWith(".")) {
                value = value.substring(1);
            }
            String host = normalizeHost(value);
            if (host != null) {
                result.add(host);
            }
        }
        return result;
    }

    private Set<Integer> getAllowedPorts() {
        List<Integer> configured = plugin.getConfig().getIntegerList("report.evidence.allowed-ports");
        if (configured.isEmpty()) {
            return Set.of(443);
        }
        Set<Integer> result = new HashSet<>();
        for (int port : configured) {
            if (port > 0 && port <= 65_535) {
                result.add(port);
            }
        }
        return result;
    }

    private String buildNormalizedUrl(URI source, String scheme, String host, int port) {
        StringBuilder result = new StringBuilder();
        result.append(scheme).append("://").append(host);
        if (port != -1) {
            result.append(':').append(port);
        }
        String path = source.getRawPath();
        result.append(path == null || path.isEmpty() ? "/" : path);
        if (source.getRawQuery() != null) {
            result.append('?').append(source.getRawQuery());
        }
        if (source.getRawFragment() != null) {
            result.append('#').append(source.getRawFragment());
        }
        return URI.create(result.toString()).normalize().toASCIIString();
    }

    public enum Status {
        VALID,
        EMPTY,
        TOO_LONG,
        INVALID_FORMAT,
        INVALID_SCHEME,
        HTTPS_REQUIRED,
        CREDENTIALS_NOT_ALLOWED,
        INVALID_HOST,
        HOST_NOT_ALLOWED,
        PORT_NOT_ALLOWED
    }

    public static final class ValidationResult {

        private final Status status;
        private final String normalizedUrl;

        private ValidationResult(Status status, String normalizedUrl) {
            this.status = status;
            this.normalizedUrl = normalizedUrl;
        }

        public static ValidationResult success(String normalizedUrl) {
            return new ValidationResult(Status.VALID, normalizedUrl);
        }

        public static ValidationResult failure(Status status) {
            return new ValidationResult(status, null);
        }

        public boolean isValid() {
            return status == Status.VALID;
        }

        public Status getStatus() {
            return status;
        }

        public String getNormalizedUrl() {
            return normalizedUrl;
        }
    }
}
