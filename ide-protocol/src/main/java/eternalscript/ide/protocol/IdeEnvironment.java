package eternalscript.ide.protocol;

import java.net.URI;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

public record IdeEnvironment(
    String environmentId,
    String environmentFingerprint,
    String scriptRoot,
    List<URI> classpath
) {
    public IdeEnvironment {
        environmentId = requireUuid(environmentId, "environmentId");
        environmentFingerprint = requireText(environmentFingerprint, "environmentFingerprint");
        scriptRoot = requireRelativePath(scriptRoot, "scriptRoot");
        classpath = List.copyOf(Objects.requireNonNull(classpath, "classpath"));
        if (classpath.size() > IdeProtocol.MAX_COLLECTION_ENTRIES) {
            throw new IllegalArgumentException("classpath has too many entries: " + classpath.size());
        }
        classpath.forEach(uri -> requireFileUri(uri, "classpath entry"));
    }

    private static String requireUuid(String value, String name) {
        String text = requireText(value, name);
        try {
            return UUID.fromString(text).toString();
        } catch (IllegalArgumentException error) {
            throw new IllegalArgumentException(name + " must be a UUID", error);
        }
    }

    private static String requireText(String value, String name) {
        Objects.requireNonNull(value, name);
        requireLine(value, name);
        if (value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return value;
    }

    private static URI requireFileUri(URI value, String name) {
        Objects.requireNonNull(value, name);
        if (!"file".equalsIgnoreCase(value.getScheme()) || value.getFragment() != null || value.getQuery() != null) {
            throw new IllegalArgumentException(name + " must be a file URI without a query or fragment: " + value);
        }
        requireLine(value.toASCIIString(), name);
        return value;
    }

    private static String requireRelativePath(String value, String name) {
        String path = requireText(value, name);
        if (path.startsWith("/") || path.endsWith("/") || path.indexOf('\\') >= 0 || path.indexOf(':') >= 0) {
            throw new IllegalArgumentException(name + " must be a normalized relative path: " + path);
        }
        String[] segments = path.split("/", -1);
        for (String segment : segments) {
            if (segment.isEmpty() || ".".equals(segment) || "..".equals(segment)) {
                throw new IllegalArgumentException(name + " must be a normalized relative path: " + path);
            }
        }
        return path;
    }

    private static void requireLine(String value, String name) {
        Objects.requireNonNull(value, name);
        if (value.indexOf('\n') >= 0 || value.indexOf('\r') >= 0 || value.indexOf('\0') >= 0) {
            throw new IllegalArgumentException(name + " must fit on one line");
        }
        if (value.getBytes(java.nio.charset.StandardCharsets.UTF_8).length > IdeProtocol.MAX_VALUE_BYTES) {
            throw new IllegalArgumentException(name + " is too large");
        }
    }
}
