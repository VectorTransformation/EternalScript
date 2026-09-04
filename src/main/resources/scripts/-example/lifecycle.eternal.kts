/**
 * Lifecycle hooks run on activation and in reverse initialization order on unload.
 * They may run again when this script or one of its providers is replaced.
 */

onLoad {
    notify().success("Lifecycle example activated")
}

onUnload {
    notify().info("Lifecycle example deactivated")
}
