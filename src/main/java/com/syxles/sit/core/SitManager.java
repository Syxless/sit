package com.syxles.sit.core;

import com.syxles.sit.SitPlugin;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.entity.ArmorStand;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerChangedWorldEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.bukkit.event.player.PlayerToggleSneakEvent;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitRunnable;

/**
 * Manages seat entities (invisible ArmorStands) and player sit/unsit lifecycle.
 */
public final class SitManager implements Listener {

  /** Plugin reference for utilities and configuration. */
  private final SitPlugin plugin;

  /** Persistent key to mark owned seat entities. */
  private final NamespacedKey seatKey;

  /** Cooldown manager for /sit usage. */
  private final CooldownManager cooldownManager;

  /** Tracks player UUID to their seat ArmorStand entity UUID. */
  private final Map<UUID, UUID> seated = new ConcurrentHashMap<>();

  /** Cached blacklist of forbidden blocks to sit on. */
  private final Set<Material> blacklist = new HashSet<>();

  /**
   * Constructs a new SitManager.
   *
   * @param plugin Plugin instance.
   * @param seatKey Persistent key for seat ownership.
   * @param cooldownManager Cooldown manager.
   */
  public SitManager(SitPlugin plugin, NamespacedKey seatKey, CooldownManager cooldownManager) {
    this.plugin = plugin;
    this.seatKey = seatKey;
    this.cooldownManager = cooldownManager;
    // Register listener hooks.
    plugin.getServer().getPluginManager().registerEvents(this, plugin);
    // Preload blacklist from config.
    loadBlacklist();
  }

  /** Reloads dynamic configuration pieces. */
  public void reload() {
    plugin.reloadConfig();
    loadBlacklist();
  }

  /** Loads the blacklist from config into a fast set. */
  private void loadBlacklist() {
    blacklist.clear();
    for (String key : plugin.config().getStringList("blacklist-blocks")) {
      try {
        Material m = Material.valueOf(key);
        blacklist.add(m);
      } catch (IllegalArgumentException ignored) {
        // Silently ignore bad material names in config to be resilient.
      }
    }
  }

  /**
   * Returns whether the player is currently seated by this plugin.
   *
   * @param player The player to check.
   * @return True if seated.
   */
  public boolean isSeated(Player player) {
    UUID seatId = seated.get(player.getUniqueId());
    if (seatId == null) {
      return false;
    }
    Entity e = findEntityByUUID(player.getWorld(), seatId);
    return e instanceof ArmorStand && e.isValid();
  }

  /**
   * Attempts to toggle the player's seated state: if seated, stand up; otherwise sit down.
   *
   * @param player The player invoking the command.
   */
  public void toggleSit(Player player) {
    if (isSeated(player)) {
      // Already seated: stand up.
      unsit(player, true);
      return;
    }

    // Enforce cooldown if applicable.
    if (cooldownManager.isOnCooldown(player)) {
      player.sendMessage("§cPlease wait before using /sit again.");
      return;
    }

    // Check if we can safely sit.
    String denyReason = canSitReason(player);
    if (denyReason != null) {
      player.sendMessage("§cCannot sit: " + denyReason);
      return;
    }

    // Proceed to create a seat entity and mount the player.
    if (createSeatAndMount(player)) {
      cooldownManager.markUsed(player);
      player.sendMessage("§7You sit down.");
    } else {
      player.sendMessage("§cAn error occurred while creating the seat.");
    }
  }

  /**
   * Returns a human-readable reason why the player cannot sit, or null if sitting is allowed.
   *
   * @param player The player.
   * @return Null if allowed; otherwise a reason message.
   */
  public String canSitReason(Player player) {
    // Disallow if already in a vehicle or has a passenger context.
    if (player.getVehicle() != null) {
      return "you are in a vehicle.";
    }
    if (player.isSleeping()) {
      return "you are sleeping.";
    }
    if (player.isGliding() && plugin.config().getBoolean("prevent-while-gliding", true)) {
      return "you are gliding (elytra).";
    }
    if (player.isSwimming() && plugin.config().getBoolean("prevent-while-swimming", true)) {
      return "you are swimming.";
    }
    if (player.getFallDistance() > 0.0f && plugin.config().getBoolean("prevent-while-falling", true)) {
      return "you are falling.";
    }

    // Prevent sitting in liquids if configured.
    if (plugin.config().getBoolean("prevent-in-liquid", true)) {
      Material feet = player.getLocation().getBlock().getType();
      if (feet == Material.WATER || feet == Material.LAVA) {
        return "you are in a liquid.";
      }
    }

    // Check the block below for safety.
    Block below = player.getLocation().getBlock().getRelative(BlockFace.DOWN);
    if (blacklist.contains(below.getType())) {
      return "the block under your feet is dangerous (" + below.getType() + ").";
    }
    if (!below.getType().isSolid()) {
      return "no solid block under your feet.";
    }

    // Ensure there is headroom to place a seat (space at feet).
    Block at = player.getLocation().getBlock();
    if (at.getType().isSolid()) {
      return "not enough space at your current location.";
    }

    return null;
  }

  /**
   * Creates an invisible ArmorStand seat and mounts the player on it.
   *
   * @param player The player to seat.
   * @return True on success, false otherwise.
   */
  private boolean createSeatAndMount(Player player) {
    Location loc = player.getLocation().clone();

    // Fine-tune Y so the player visually sits on the block below.
    loc.setYaw(player.getLocation().getYaw());
    loc.setPitch(0);

    try {
      ArmorStand stand = player.getWorld().spawn(loc, ArmorStand.class, as -> {
        // Configure the armor stand as an invisible, non-gravity marker seat.
        as.setGravity(false);                 // Seat should not fall.
        as.setVisible(false);                 // Do not render stand.
        as.setMarker(true);                   // Reduce hitbox drastically.
        as.setSmall(true);                    // Smaller pivot improves look.
        as.setInvulnerable(true);             // Avoid damage/removal by gameplay.
        as.setCustomNameVisible(false);       // No name tag.
        as.setSilent(true);                   // No sounds.
        as.setCollidable(false);              // Avoid entity collisions.
        // Mark with persistent data so we can find and clean seats reliably.
        PersistentDataContainer pdc = as.getPersistentDataContainer();
        pdc.set(seatKey, PersistentDataType.STRING, player.getUniqueId().toString());
      });

      if (stand == null || !stand.isValid()) {
        return false;
      }

      // Mount the player onto the seat. Use a scheduled task to ensure mounting after spawn.
      new BukkitRunnable() {
        @Override public void run() {
          if (!stand.isValid() || !player.isOnline()) {
            if (stand.isValid()) {
              stand.remove();
            }
            return;
          }
          // Clear any vehicle first, then add as passenger.
          if (player.getVehicle() != null) {
            player.getVehicle().removePassenger(player);
          }
          boolean ok = stand.addPassenger(player);
          if (!ok) {
            // If mounting failed, remove the stand to prevent leaks.
            stand.remove();
            player.sendMessage("§cUnable to mount (entity conflict).");
            return;
          }
          // Record mapping for later cleanup.
          seated.put(player.getUniqueId(), stand.getUniqueId());
        }
      }.runTask(plugin);

      return true;
    } catch (Throwable t) {
      // Be defensive: on any exception, do not leave entities around.
      Bukkit.getLogger().warning("[Sit] Exception while creating seat: " + t.getMessage());
      return false;
    }
  }

  /**
   * Unseats the player if currently seated by this plugin.
   *
   * @param player The player to unseat.
   * @param notify Whether to send a chat feedback message.
   */
  public void unsit(Player player, boolean notify) {
    UUID seatId = seated.remove(player.getUniqueId());
    if (seatId == null) {
      return;
    }
    Entity e = findEntityByUUID(player.getWorld(), seatId);
    if (e instanceof ArmorStand) {
      ArmorStand as = (ArmorStand) e;
      // Eject player first to avoid weird mounting chains.
      if (as.getPassengers().contains(player)) {
        as.removePassenger(player);
      }
      // Teleport the player slightly up to avoid clipping into the block.
      Location safe = as.getLocation().clone().add(0, 0.35, 0);
      player.teleport(safe);
      // Finally, remove the seat entity.
      as.remove();
    }
    if (notify) {
      player.sendMessage("§7You stand up.");
    }
  }

  /**
   * Finds an entity by UUID in the given world.
   *
   * @param world World to search.
   * @param uuid Entity UUID to locate.
   * @return The entity or null.
   */
  private Entity findEntityByUUID(World world, UUID uuid) {
    for (Entity e : world.getEntities()) {
      if (e.getUniqueId().equals(uuid)) {
        return e;
      }
    }
    return null;
  }

  /** Scans all worlds for orphan seats (with our key but no passenger) and removes them. */
  public void cleanupOrphanSeats() {
    for (World w : Bukkit.getWorlds()) {
      for (Entity e : w.getEntitiesByClass(ArmorStand.class)) {
        ArmorStand as = (ArmorStand) e;
        String owner = as.getPersistentDataContainer().get(seatKey, PersistentDataType.STRING);
        if (owner != null) {
          if (as.getPassengers().isEmpty()) {
            as.remove();
          }
        }
      }
    }
  }

  /** Forces cleanup of all current seats and unseats any players. */
  public void forceCleanupAll() {
    // Unseat all tracked players safely.
    for (UUID pid : new HashSet<>(seated.keySet())) {
      Player p = Bukkit.getPlayer(pid);
      if (p != null && p.isOnline()) {
        unsit(p, false);
      }
    }
    // Remove any orphan entities in the worlds.
    cleanupOrphanSeats();
  }

  // ===================== Listener hooks =====================

  @EventHandler
  public void onQuit(PlayerQuitEvent event) {
    // If configured, unseat on quit to avoid dangling seats.
    if (plugin.config().getBoolean("auto-unsit.on-quit", true)) {
      unsit(event.getPlayer(), false);
    }
  }

  @EventHandler
  public void onWorldChange(PlayerChangedWorldEvent event) {
    if (plugin.config().getBoolean("auto-unsit.on-world-change", true)) {
      unsit(event.getPlayer(), false);
    }
  }

  @EventHandler
  public void onTeleport(PlayerTeleportEvent event) {
    if (plugin.config().getBoolean("auto-unsit.on-teleport", true)) {
      if (isSeated(event.getPlayer())) {
        unsit(event.getPlayer(), false);
      }
    }
  }

  @EventHandler
  public void onSneak(PlayerToggleSneakEvent event) {
    if (!event.isSneaking()) {
      return;
    }
    if (plugin.config().getBoolean("auto-unsit.on-sneak", true)) {
      if (isSeated(event.getPlayer())) {
        unsit(event.getPlayer(), true);
      }
    }
  }
}
