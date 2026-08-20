package eternalscript.ide.protocol;

public final class IdeProtocol {
    public static final int VERSION = 3;
    public static final String DIRECTORY = ".eternalscript/ide";
    public static final String ENVIRONMENT_FILE = DIRECTORY + "/environment.properties";
    public static final int MAX_ENVIRONMENT_BYTES = 8 * 1024 * 1024;
    // Keep a strict defensive bound while leaving room for future protocol
    // extensions without coupling the format to the current fixed import set.
    public static final int MAX_COLLECTION_ENTRIES = 65_536;
    public static final int MAX_VALUE_BYTES = 64 * 1024;

    private IdeProtocol() {}
}
