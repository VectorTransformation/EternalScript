package eternalscript.ide.protocol;

public final class IdeProtocol {
    public static final String SCRIPT_JVM_TARGET = "25";
    public static final String DIRECTORY = ".eternalscript/ide";
    public static final String ENVIRONMENT_FILE = DIRECTORY + "/environment.properties";
    public static final int MAX_ENVIRONMENT_BYTES = 8 * 1024 * 1024;
    public static final int MAX_COLLECTION_ENTRIES = 65_536;
    public static final int MAX_VALUE_BYTES = 64 * 1024;

    private IdeProtocol() {}
}
