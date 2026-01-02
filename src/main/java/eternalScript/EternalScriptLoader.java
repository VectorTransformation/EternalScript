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
    @Override
    public void classloader(PluginClasspathBuilder builder) {
        try (
            var stream = getClass().getResourceAsStream("/paper-plugin.yml");
            var reader = stream != null ? new InputStreamReader(stream, StandardCharsets.UTF_8) : null
        ) {
            if (reader == null) return;
            var libraries = YamlConfiguration.loadConfiguration(reader).getStringList("libraries");
            if (libraries.isEmpty()) return;
            var resolver = new MavenLibraryResolver();
            resolver.addRepository(new RemoteRepository.Builder("central", "default", MavenLibraryResolver.MAVEN_CENTRAL_DEFAULT_MIRROR).build());
            libraries.forEach(artifact -> resolver.addDependency(new Dependency(new DefaultArtifact(artifact), null)));
            builder.addLibrary(resolver);
        } catch (Exception e) {

        }
    }
}