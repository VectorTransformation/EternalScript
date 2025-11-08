package eternalScript;

import io.papermc.paper.plugin.loader.PluginClasspathBuilder;
import io.papermc.paper.plugin.loader.PluginLoader;
import io.papermc.paper.plugin.loader.library.impl.MavenLibraryResolver;
import org.bukkit.configuration.file.YamlConfiguration;
import org.eclipse.aether.artifact.DefaultArtifact;
import org.eclipse.aether.graph.Dependency;
import org.eclipse.aether.repository.RemoteRepository;

import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;

public class EternalScriptLoader implements PluginLoader {
    private final MavenLibraryResolver resolver = new MavenLibraryResolver();
    private final RemoteRepository repository = new RemoteRepository.Builder("central", "default", MavenLibraryResolver.MAVEN_CENTRAL_DEFAULT_MIRROR).build();

    @Override
    public void classloader(PluginClasspathBuilder builder) {
        try (
            var stream = getClass().getResourceAsStream("/paper-plugin.yml");
            var reader = stream == null ? null : new InputStreamReader(stream, StandardCharsets.UTF_8)
        ) {
            if (reader == null) return;
            var paperPluginYml = YamlConfiguration.loadConfiguration(reader);
            paperPluginYml.getStringList("libraries").forEach(this::addDependency);
            resolver.addRepository(repository);
            builder.addLibrary(resolver);
        } catch (Exception e) {

        }
    }

    private void addDependency(String artifact) {
        resolver.addDependency(new Dependency(new DefaultArtifact(artifact), null));
    }
}