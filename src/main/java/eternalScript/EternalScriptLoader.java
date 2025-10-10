package eternalScript;

import io.papermc.paper.plugin.loader.PluginClasspathBuilder;
import io.papermc.paper.plugin.loader.PluginLoader;
import io.papermc.paper.plugin.loader.library.impl.MavenLibraryResolver;
import org.eclipse.aether.artifact.DefaultArtifact;
import org.eclipse.aether.graph.Dependency;
import org.eclipse.aether.repository.RemoteRepository;

public class EternalScriptLoader implements PluginLoader {
    private final String kotlinVersion = "2.2.20";

    @Override
    public void classloader(PluginClasspathBuilder classpathBuilder) {
        implementation(classpathBuilder, kotlin("stdlib-jdk8"));
        implementation(classpathBuilder, kotlin("reflect"));
        implementation(classpathBuilder, "org.jetbrains.kotlinx:kotlinx-serialization-json:1.9.0");
        implementation(classpathBuilder, kotlin("scripting-jvm"));
        implementation(classpathBuilder, kotlin("scripting-jvm-host"));
        implementation(classpathBuilder, "org.jetbrains.kotlinx:kotlinx-coroutines-core:1.10.2");
    }

    private void implementation(PluginClasspathBuilder classpathBuilder, String artifact) {
        var resolver = new MavenLibraryResolver();
        resolver.addDependency(new Dependency(new DefaultArtifact(artifact), null));
        var repository = new RemoteRepository.Builder("central", "default", MavenLibraryResolver.MAVEN_CENTRAL_DEFAULT_MIRROR).build();
        resolver.addRepository(repository);
        classpathBuilder.addLibrary(resolver);
    }

    private String kotlin(String artifact) {
        return "org.jetbrains.kotlin:kotlin-" + artifact + ":" + kotlinVersion;
    }
}