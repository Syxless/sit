package com.syxles.sit;

import com.syxles.sit.core.SitManager;
import com.syxles.sit.core.CooldownManager;
import org.bukkit.Bukkit;
import org.bukkit.NamespacedKey;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.plugin.java.JavaPlugin;

/**
 * The main plugin entry for the Sit plugin.
 *
 * <p>This plugin adds a /sit command</p>
 *
 */
public final class SitPlugin extends JavaPlugin {

  /** Manager that handles seat creation and removal. */
  private SitManager sitManager;

  /** Manager that handles cooldowns for players. */
  private CooldownManager cooldownManager;

  /** Persistent data key used to mark seat entities as owned by this plugin. */
  private NamespacedKey seatKey;

  @Override
  public void onEnable() {
    // Load and save default configuration if not present.
    // This ensures that a fresh install has sane defaults.
    saveDefaultConfig();

    // Create the persistent key once (safe to reuse everywhere).
    seatKey = new NamespacedKey(this, "seat-owner");

    // Initialize managers after configuration is available.
    cooldownManager = new CooldownManager(this);
    sitManager = new SitManager(this, seatKey, cooldownManager);

    // Register command executor and tab completer for /sit.
    getCommand("sit").setExecutor(new com.syxles.sit.commands.SitCommand(this, sitManager, cooldownManager));
    getCommand("sit").setTabCompleter(new com.syxles.sit.commands.SitCommand(this, sitManager, cooldownManager));

    // Perform a quick cleanup scan to remove any orphan seat entities from prior runs.
    // This avoids leaks if the server crashed and left seats behind.
    Bukkit.getScheduler().runTaskLater(this, () -> sitManager.cleanupOrphanSeats(), 1L);
  }

  @Override
  public void onDisable() {
    // Ensure all seat entities are removed and players are safely unseated.
    // This prevents entity leaks and odd server states on stop or reload.
    if (sitManager != null) {
      sitManager.forceCleanupAll();
    }
  }

  /**
   * Returns the plugin configuration.
   *
   * @return The main configuration instance.
   */
  public FileConfiguration config() {
    return getConfig();
  }

  /**
   * Returns the seat persistent key.
   *
   * @return NamespacedKey used to mark seat entities.
   */
  public NamespacedKey seatKey() {
    return seatKey;
  }
}
