package eternalscript.ide.protocol;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.List;
import java.util.UUID;
import java.util.stream.IntStream;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;

class IdeEnvironmentCodecTest {
    @Test
    void roundTripsDeterministically() {
        assertEquals(3, IdeProtocol.VERSION);
        IdeEnvironment environment = new IdeEnvironment(
            IdeProtocol.VERSION,
            UUID.randomUUID().toString(),
            "2.1.0",
            "2.4.10",
            "abc123",
            "scripts",
            List.of(URI.create("file:///server/plugins/EternalScript.jar")),
            List.of("org.bukkit.Bukkit", "example.Type as Alias")
        );

        byte[] encoded = IdeEnvironmentCodec.encode(environment);
        assertEquals(environment, IdeEnvironmentCodec.decode(encoded));
        assertArrayEquals(encoded, IdeEnvironmentCodec.encode(environment));
        String reordered = new String(encoded, StandardCharsets.UTF_8).lines()
            .sorted(java.util.Comparator.reverseOrder())
            .collect(Collectors.joining("\n", "", "\n"));
        assertEquals(
            IdeEnvironmentCodec.verifiedContentHash(encoded),
            IdeEnvironmentCodec.verifiedContentHash(reordered.getBytes(StandardCharsets.UTF_8))
        );
    }

    @Test
    void acceptsHashedOptionalKeysAndRejectsUnknownAndDuplicateKeys() {
        IdeEnvironment environment = new IdeEnvironment(
            IdeProtocol.VERSION,
            UUID.randomUUID().toString(),
            "2.1.0",
            "2.4.10",
            "abc123",
            "scripts",
            List.of(),
            List.of()
        );
        String encoded = new String(IdeEnvironmentCodec.encode(environment), StandardCharsets.UTF_8);
        byte[] extended = IdeEnvironmentCodec.encode(environment, Map.of("x.future", "supported"));

        assertEquals(environment, IdeEnvironmentCodec.decode(extended));
        assertThrows(IllegalArgumentException.class, () -> IdeEnvironmentCodec.decode(encoded + "extra=eA\n"));
        assertThrows(
            IllegalArgumentException.class,
            () -> IdeEnvironmentCodec.decode(encoded + "protocolVersion=1\n")
        );
    }

    @Test
    void rejectsTamperingMalformedUtf8AndUnsafeRoots() {
        IdeEnvironment environment = new IdeEnvironment(
            IdeProtocol.VERSION,
            UUID.randomUUID().toString(),
            "2.1.0",
            "2.4.10",
            "abc123",
            "scripts",
            List.of(),
            List.of()
        );
        byte[] encoded = IdeEnvironmentCodec.encode(environment);
        byte[] tampered = encoded.clone();
        tampered[0] = tampered[0] == 'a' ? (byte) 'b' : (byte) 'a';

        assertThrows(IllegalArgumentException.class, () -> IdeEnvironmentCodec.decode(tampered));
        assertThrows(IllegalArgumentException.class, () -> IdeEnvironmentCodec.decode(new byte[] {(byte) 0xc3, (byte) 0x28}));
        assertThrows(
            IllegalArgumentException.class,
            () -> new IdeEnvironment(
                IdeProtocol.VERSION,
                UUID.randomUUID().toString(),
                "2.1.0",
                "2.4.10",
                "abc123",
                "../scripts",
                List.of(),
                List.of()
            )
        );
        assertNotEquals(0, encoded.length);
    }

    @Test
    void rejectsNonFileUrisOversizedInputValuesAndCollections() {
        assertThrows(
            IllegalArgumentException.class,
            () -> environment(List.of(URI.create("https://example.invalid/plugin.jar")), List.of())
        );
        assertThrows(
            IllegalArgumentException.class,
            () -> environment(List.of(), List.of("x".repeat(IdeProtocol.MAX_VALUE_BYTES + 1)))
        );
        assertThrows(
            IllegalArgumentException.class,
            () -> environment(
                List.of(),
                java.util.Collections.nCopies(IdeProtocol.MAX_COLLECTION_ENTRIES + 1, "example.Type")
            )
        );
        assertThrows(
            IllegalArgumentException.class,
            () -> IdeEnvironmentCodec.decode(new byte[IdeProtocol.MAX_ENVIRONMENT_BYTES + 1])
        );
    }

    @Test
    void acceptsLargeDefaultImportSetsWithinTheBoundedManifest() {
        List<String> imports = IntStream.range(0, 20_000)
            .mapToObj(index -> "example.generated.Type" + index)
            .toList();
        IdeEnvironment environment = environment(List.of(), imports);

        byte[] encoded = IdeEnvironmentCodec.encode(environment);

        assertEquals(imports, IdeEnvironmentCodec.decode(encoded).defaultImports());
        assertTrue(encoded.length < IdeProtocol.MAX_ENVIRONMENT_BYTES);
    }

    private static IdeEnvironment environment(List<URI> classpath, List<String> defaultImports) {
        return new IdeEnvironment(
            IdeProtocol.VERSION,
            UUID.randomUUID().toString(),
            "2.1.0",
            "2.4.10",
            "limits",
            "scripts",
            classpath,
            defaultImports
        );
    }
}
