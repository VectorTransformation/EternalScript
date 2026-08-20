package eternalscript.ide.protocol;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.StringReader;
import java.net.URI;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

public final class IdeEnvironmentCodec {
    private static final Base64.Encoder ENCODER = Base64.getUrlEncoder().withoutPadding();
    private static final Base64.Decoder DECODER = Base64.getUrlDecoder();
    private static final String CONTENT_HASH = "contentHash";

    private IdeEnvironmentCodec() {}

    public static byte[] encode(IdeEnvironment environment) {
        return encode(environment, Map.of());
    }

    public static byte[] encode(IdeEnvironment environment, Map<String, String> optionalValues) {
        Map<String, String> values = encodedValues(environment);
        optionalValues.forEach((key, value) -> {
            if (!key.startsWith("x.") || !key.matches("[A-Za-z0-9_.-]+")) {
                throw new IllegalArgumentException("Optional IDE environment keys must start with x.: " + key);
            }
            if (values.containsKey(key)) throw new IllegalArgumentException("Duplicate IDE environment key: " + key);
            putValue(values, key, value);
        });
        String hash = contentHash(values);
        StringBuilder output = new StringBuilder(1024);
        new TreeMap<>(values).forEach((key, value) -> appendRaw(output, key, value));
        appendRaw(output, CONTENT_HASH, hash);
        byte[] result = output.toString().getBytes(StandardCharsets.UTF_8);
        if (result.length > IdeProtocol.MAX_ENVIRONMENT_BYTES) {
            throw new IllegalArgumentException("IDE environment exceeds " + IdeProtocol.MAX_ENVIRONMENT_BYTES + " bytes");
        }
        return result;
    }

    public static IdeEnvironment decode(byte[] content) {
        if (content.length > IdeProtocol.MAX_ENVIRONMENT_BYTES) {
            throw new IllegalArgumentException("IDE environment exceeds " + IdeProtocol.MAX_ENVIRONMENT_BYTES + " bytes");
        }
        try {
            String decoded = StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(content))
                .toString();
            return decode(decoded);
        } catch (CharacterCodingException error) {
            throw new IllegalArgumentException("IDE environment is not valid UTF-8", error);
        }
    }

    public static int peekProtocolVersion(byte[] content) {
        if (content.length > IdeProtocol.MAX_ENVIRONMENT_BYTES) {
            throw new IllegalArgumentException("IDE environment exceeds " + IdeProtocol.MAX_ENVIRONMENT_BYTES + " bytes");
        }
        try {
            String decoded = StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(content))
                .toString();
            Map<String, String> values = readValues(new StringReader(decoded));
            return number(values, "protocolVersion");
        } catch (CharacterCodingException error) {
            throw new IllegalArgumentException("IDE environment is not valid UTF-8", error);
        }
    }

    /** Returns the normalized, verified hash embedded in a valid v3 manifest. */
    public static String verifiedContentHash(byte[] content) {
        if (content.length > IdeProtocol.MAX_ENVIRONMENT_BYTES) {
            throw new IllegalArgumentException("IDE environment exceeds " + IdeProtocol.MAX_ENVIRONMENT_BYTES + " bytes");
        }
        try {
            String decoded = StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(content))
                .toString();
            Map<String, String> values = readValues(new StringReader(decoded));
            String expectedHash = raw(values, CONTENT_HASH);
            String actualHash = contentHash(values);
            if (!constantTimeEquals(expectedHash, actualHash)) {
                throw new IllegalArgumentException("IDE environment content hash does not match");
            }
            return actualHash;
        } catch (CharacterCodingException error) {
            throw new IllegalArgumentException("IDE environment is not valid UTF-8", error);
        }
    }

    public static IdeEnvironment decode(String content) {
        byte[] encoded = content.getBytes(StandardCharsets.UTF_8);
        if (encoded.length > IdeProtocol.MAX_ENVIRONMENT_BYTES) {
            throw new IllegalArgumentException("IDE environment exceeds " + IdeProtocol.MAX_ENVIRONMENT_BYTES + " bytes");
        }
        Map<String, String> values = readValues(new StringReader(content));
        String expectedHash = raw(values, CONTENT_HASH);
        String actualHash = contentHash(values);
        if (!constantTimeEquals(expectedHash, actualHash)) {
            throw new IllegalArgumentException("IDE environment content hash does not match");
        }

        int protocolVersion = number(values, "protocolVersion");
        String environmentId = value(values, "environmentId");
        String runtimePluginVersion = value(values, "runtimePluginVersion");
        String kotlinVersion = value(values, "kotlinVersion");
        String environmentFingerprint = value(values, "environmentFingerprint");
        String scriptRoot = value(values, "scriptRoot");
        List<URI> classpath = new ArrayList<>();
        int classpathCount = count(values, "classpath.count");
        for (int index = 0; index < classpathCount; index++) {
            classpath.add(URI.create(value(values, "classpath." + index)));
        }
        List<String> defaultImports = new ArrayList<>();
        int importCount = count(values, "defaultImports.count");
        for (int index = 0; index < importCount; index++) {
            defaultImports.add(value(values, "defaultImports." + index));
        }
        values.entrySet().removeIf(entry -> {
            if (!entry.getKey().startsWith("x.")) return false;
            decodeValue(entry.getValue(), entry.getKey());
            return true;
        });
        if (!values.isEmpty()) {
            throw new IllegalArgumentException("Unknown IDE environment keys: " + values.keySet());
        }
        return new IdeEnvironment(
            protocolVersion,
            environmentId,
            runtimePluginVersion,
            kotlinVersion,
            environmentFingerprint,
            scriptRoot,
            classpath,
            defaultImports
        );
    }

    private static Map<String, String> encodedValues(IdeEnvironment environment) {
        Map<String, String> values = new LinkedHashMap<>();
        putNumber(values, "protocolVersion", environment.protocolVersion());
        putValue(values, "environmentId", environment.environmentId());
        putValue(values, "runtimePluginVersion", environment.runtimePluginVersion());
        putValue(values, "kotlinVersion", environment.kotlinVersion());
        putValue(values, "environmentFingerprint", environment.environmentFingerprint());
        putValue(values, "scriptRoot", environment.scriptRoot());
        putNumber(values, "classpath.count", environment.classpath().size());
        for (int index = 0; index < environment.classpath().size(); index++) {
            putValue(values, "classpath." + index, environment.classpath().get(index).toASCIIString());
        }
        putNumber(values, "defaultImports.count", environment.defaultImports().size());
        for (int index = 0; index < environment.defaultImports().size(); index++) {
            putValue(values, "defaultImports." + index, environment.defaultImports().get(index));
        }
        return values;
    }

    private static Map<String, String> readValues(StringReader source) {
        Map<String, String> values = new LinkedHashMap<>();
        try (BufferedReader reader = new BufferedReader(source)) {
            String line;
            int lineNumber = 0;
            while ((line = reader.readLine()) != null) {
                lineNumber++;
                if (line.isBlank() || line.startsWith("#")) continue;
                if (line.getBytes(StandardCharsets.UTF_8).length > encodedValueLimit()) {
                    throw new IllegalArgumentException("IDE environment line " + lineNumber + " is too large");
                }
                int separator = line.indexOf('=');
                if (separator <= 0) {
                    throw new IllegalArgumentException("Invalid IDE environment line " + lineNumber);
                }
                String key = line.substring(0, separator);
                String encoded = line.substring(separator + 1);
                if (!key.matches("[A-Za-z0-9_.-]+")) {
                    throw new IllegalArgumentException("Invalid IDE environment key on line " + lineNumber);
                }
                String previous = values.put(key, encoded);
                if (previous != null) {
                    throw new IllegalArgumentException("Duplicate IDE environment key: " + key);
                }
            }
        } catch (IOException error) {
            throw new IllegalArgumentException("Could not read the IDE environment", error);
        }
        return values;
    }

    private static int number(Map<String, String> values, String key) {
        try {
            return Integer.parseInt(raw(values, key));
        } catch (NumberFormatException error) {
            throw new IllegalArgumentException("Invalid integer for IDE environment key " + key, error);
        }
    }

    private static int count(Map<String, String> values, String key) {
        int result = number(values, key);
        if (result < 0 || result > IdeProtocol.MAX_COLLECTION_ENTRIES) {
            throw new IllegalArgumentException("Invalid IDE environment count for " + key + ": " + result);
        }
        return result;
    }

    private static String value(Map<String, String> values, String key) {
        return decodeValue(raw(values, key), key);
    }

    private static String decodeValue(String encoded, String key) {
        try {
            byte[] bytes = DECODER.decode(encoded);
            if (bytes.length > IdeProtocol.MAX_VALUE_BYTES) {
                throw new IllegalArgumentException("IDE environment value is too large for key " + key);
            }
            return StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(bytes))
                .toString();
        } catch (CharacterCodingException | IllegalArgumentException error) {
            throw new IllegalArgumentException("Invalid base64 UTF-8 value for IDE environment key " + key, error);
        }
    }

    private static String raw(Map<String, String> values, String key) {
        String result = values.remove(key);
        if (result == null) throw new IllegalArgumentException("Missing IDE environment key: " + key);
        return result;
    }

    private static void putNumber(Map<String, String> values, String key, int value) {
        values.put(key, Integer.toString(value));
    }

    private static void putValue(Map<String, String> values, String key, String value) {
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        if (bytes.length > IdeProtocol.MAX_VALUE_BYTES) {
            throw new IllegalArgumentException("IDE environment value is too large for key " + key);
        }
        values.put(key, ENCODER.encodeToString(bytes));
    }

    private static void appendRaw(StringBuilder output, String key, String value) {
        output.append(key).append('=').append(value).append('\n');
    }

    private static String contentHash(Map<String, String> values) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            new TreeMap<>(values).forEach((key, value) -> {
                digest.update(key.getBytes(StandardCharsets.UTF_8));
                digest.update((byte) '=');
                digest.update(value.getBytes(StandardCharsets.US_ASCII));
                digest.update((byte) '\n');
            });
            return hex(digest.digest());
        } catch (NoSuchAlgorithmException error) {
            throw new IllegalStateException("SHA-256 is unavailable", error);
        }
    }

    private static boolean constantTimeEquals(String first, String second) {
        return MessageDigest.isEqual(
            first.getBytes(StandardCharsets.US_ASCII),
            second.getBytes(StandardCharsets.US_ASCII)
        );
    }

    private static int encodedValueLimit() {
        return ((IdeProtocol.MAX_VALUE_BYTES + 2) / 3) * 4 + 256;
    }

    private static String hex(byte[] bytes) {
        StringBuilder result = new StringBuilder(bytes.length * 2);
        for (byte value : bytes) result.append(String.format("%02x", value));
        return result.toString();
    }
}
