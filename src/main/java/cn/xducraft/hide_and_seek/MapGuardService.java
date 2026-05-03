package cn.xducraft.hide_and_seek;

final class MapGuardService {
    private static final String ENABLED_PATH = "mapGuard.enabled";

    private final Hide_and_seek plugin;
    private boolean enabled;

    MapGuardService(Hide_and_seek plugin) {
        this.plugin = plugin;
        reloadFromConfig();
    }

    void reloadFromConfig() {
        enabled = plugin.getConfig().getBoolean(ENABLED_PATH, true);
    }

    boolean isEnabled() {
        return enabled;
    }

    void setEnabled(boolean enabled) {
        this.enabled = enabled;
        plugin.getConfig().set(ENABLED_PATH, enabled);
        plugin.saveConfig();
    }
}
