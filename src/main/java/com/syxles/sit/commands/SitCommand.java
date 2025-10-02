package com.syxles.sit.commands;

import com.syxles.sit.SitPlugin;
import com.syxles.sit.core.CooldownManager;
import com.syxles.sit.core.SitManager;
import java.util.ArrayList;
import java.util.List;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;

/**
 * Handles the /sit command and its minimal subcommands.
 */
public final class SitCommand implements CommandExecutor, TabCompleter {

  /** Plugin instance. */
  private final SitPlugin plugin;
  /** Seat manager. */
  private final SitManager sitManager;
  /** Cooldown manager. */
  private final CooldownManager cooldownManager;

  /**
   * Creates a SitCommand executor.
   *
   * @param plugin Plugin instance.
   * @param sitManager Seat manager.
   * @param cooldownManager Cooldown manager.
   */
  public SitCommand(SitPlugin plugin, SitManager sitManager, CooldownManager cooldownManager) {
    this.plugin = plugin;
    this.sitManager = sitManager;
    this.cooldownManager = cooldownManager;
  }

  @Override
  public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
    // Only players can use /sit, since it acts only on self.
    if (!(sender instanceof Player)) {
      sender.sendMessage("Only players can use this command.");
      return true;
    }
    Player player = (Player) sender;

    // Handle '/sit reload' for admins as an argument.
    if (args.length == 1 && "reload".equalsIgnoreCase(args[0])) {
      if (!player.hasPermission("sit.reload")) {
        player.sendMessage("§cPermission refusée.");
        return true;
      }
      sitManager.reload();
      player.sendMessage("§aConfiguration rechargée.");
      return true;
    }

    // No args: toggle sit/unsit for self.
    if (!player.hasPermission("sit.use")) {
      player.sendMessage("§cPermission refusée.");
      return true;
    }

    sitManager.toggleSit(player);
    return true;
  }

  @Override
  public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
    List<String> out = new ArrayList<>();
    if (args.length == 1) {
      String p = args[0].toLowerCase();
      if ("reload".startsWith(p) && sender.hasPermission("sit.reload")) {
        out.add("reload");
      }
    }
    return out;
  }
}
