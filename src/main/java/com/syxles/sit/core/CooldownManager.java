package com.syxles.sit.core;

import com.syxles.sit.SitPlugin;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.bukkit.entity.Player;

/**
 * Tracks per-player cooldowns for the /sit command.
 */
public final class CooldownManager {

  /** Reference to the plugin for config access. */
  private final SitPlugin plugin;

  /** Stores the last-use epoch milliseconds per player. */
  private final Map<UUID, Long> lastUse = new ConcurrentHashMap<>();

  /**
   * Creates a cooldown manager.
   *
   * @param plugin Plugin instance for config access.
   */
  public CooldownManager(SitPlugin plugin) {
    this.plugin = plugin;
  }

  /**
   * Returns true if the player is currently under cooldown and does not bypass it.
   *
   * @param player The player to check.
   * @return Whether the player is on cooldown.
   */
  public boolean isOnCooldown(Player player) {
    if (player.hasPermission("sit.bypasscooldown")) {
      // Players with bypass permission never face cooldown.
      return false;
    }
    long cooldownSeconds = Math.max(0L, plugin.config().getLong("cooldown-seconds", 3L));
    if (cooldownSeconds <= 0L) {
      return false;
    }
    Long last = lastUse.get(player.getUniqueId());
    if (last == null) {
      return false;
    }
    long now = System.currentTimeMillis();
    return (now - last) < (cooldownSeconds * 1000L);
  }

  /**
   * Marks the player as having used the command now.
   *
   * @param player The player.
   */
  public void markUsed(Player player) {
    lastUse.put(player.getUniqueId(), System.currentTimeMillis());
  }
}
