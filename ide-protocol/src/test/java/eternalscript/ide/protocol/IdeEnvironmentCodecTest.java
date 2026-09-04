package eternalscript.ide.protocol;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;

class IdeEnvironmentCodecTest {
    @Test
    void roundTripsDeterministically() {
        IdeEnvironment environment = environment(
            List.of(URI.create("file:///server/plugins/EternalScript.jar"))
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
    void rejectsUnknownAndDuplicateKeys() {
        String encoded = new String(IdeEnvironmentCodec.encode(environment(List.of())), StandardCharsets.UTF_8);

        assertThrows(IllegalArgumentException.class, () -> IdeEnvironmentCodec.decode(encoded + "extra=eA\n"));
        assertThrows(
            IllegalArgumentException.class,
            () -> IdeEnvironmentCodec.decode(encoded + "environmentId=eA\n")
        );
    }

    @Test
    void rejectsTamperingMalformedUtf8AndUnsafeRoots() {
        IdeEnvironment environment = environment(List.of());
        byte[] encoded = IdeEnvironmentCodec.encode(environment);
        byte[] tampered = encoded.clone();
        tampered[0] = tampered[0] == 'a' ? (byte) 'b' : (byte) 'a';

        assertThrows(IllegalArgumentException.class, () -> IdeEnvironmentCodec.decode(tampered));
        assertThrows(IllegalArgumentException.class, () -> IdeEnvironmentCodec.decode(new byte[] {(byte) 0xc3, (byte) 0x28}));
        assertThrows(
            IllegalArgumentException.class,
            () -> new IdeEnvironment(
                UUID.randomUUID().toString(),
                "abc123",
                "../scripts",
                List.of()
            )
        );
        assertNotEquals(0, encoded.length);
    }

    @Test
    void rejectsNonFileUrisOversizedInputValuesAndCollections() {
        assertThrows(
            IllegalArgumentException.class,
            () -> environment(List.of(URI.create("https://example.invalid/plugin.jar")))
        );
        assertThrows(
            IllegalArgumentException.class,
            () -> new IdeEnvironment(
                UUID.randomUUID().toString(),
                "x".repeat(IdeProtocol.MAX_VALUE_BYTES + 1),
                "scripts",
                List.of()
            )
        );
        assertThrows(
            IllegalArgumentException.class,
            () -> environment(Collections.nCopies(
                IdeProtocol.MAX_COLLECTION_ENTRIES + 1,
                URI.create("file:///same.jar")
            ))
        );
        assertThrows(
            IllegalArgumentException.class,
            () -> IdeEnvironmentCodec.decode(new byte[IdeProtocol.MAX_ENVIRONMENT_BYTES + 1])
        );
    }

    private static IdeEnvironment environment(List<URI> classpath) {
        return new IdeEnvironment(
            UUID.randomUUID().toString(),
            "limits",
            "scripts",
            classpath
        );
    }
}
