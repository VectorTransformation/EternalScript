package eternalscript;

import io.papermc.paper.plugin.loader.PluginClasspathBuilder;
import io.papermc.paper.plugin.loader.PluginLoader;
import io.papermc.paper.plugin.loader.library.impl.MavenLibraryResolver;
import org.eclipse.aether.artifact.DefaultArtifact;
import org.eclipse.aether.graph.Dependency;
import org.eclipse.aether.repository.RemoteRepository;

import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Properties;

public final class EternalScriptLoader implements PluginLoader {
    @Override
    public void classloader(PluginClasspathBuilder builder) {
        try (
            var stream = getClass().getResourceAsStream("/eternalscript-runtime-libraries.properties");
            var reader = stream != null ? new InputStreamReader(stream, StandardCharsets.UTF_8) : null
        ) {
            if (reader == null) {
                throw new IllegalStateException("Generated EternalScript runtime library manifest is missing");
            }
            var properties = new Properties();
            properties.load(reader);
            var libraries = properties.getProperty("libraries", "");
            if (libraries.isBlank()) return;
            var resolver = new MavenLibraryResolver();
            resolver.addRepository(new RemoteRepository.Builder("central", "default", MavenLibraryResolver.MAVEN_CENTRAL_DEFAULT_MIRROR).build());
            for (var artifact : libraries.split(",")) {
                var coordinate = artifact.trim();
                if (!coordinate.isEmpty()) {
                    resolver.addDependency(new Dependency(new DefaultArtifact(coordinate), null));
                }
            }
            builder.addLibrary(resolver);
        } catch (Exception error) {
            throw new IllegalStateException("Could not configure EternalScript runtime libraries", error);
        }
    }
}
