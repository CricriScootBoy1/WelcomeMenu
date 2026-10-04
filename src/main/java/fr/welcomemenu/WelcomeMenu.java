package fr.welcomemenu;

import net.kyori.adventure.key.Key;
import net.kyori.adventure.sound.Sound;
import net.kyori.adventure.sound.SoundStop;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Material;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.PluginCommand;
import org.bukkit.command.TabCompleter;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.InventoryView;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

public final class WelcomeMenu extends JavaPlugin implements Listener, CommandExecutor, TabCompleter {

    private static final MiniMessage MM = MiniMessage.miniMessage();
    private static final String DEFAULT_SOUND = "minecraft:music_disc.pigstep";

    /** Joueurs qui ont le menu ouvert. */
    private final Set<UUID> viewing = new HashSet<>();
    /** Joueurs qui ferment volontairement le menu (bouton / plugin). */
    private final Set<UUID> confirmed = new HashSet<>();

    /** Sert à reconnaître notre menu et mémorise l'action de chaque slot. */
    private static final class MenuHolder implements InventoryHolder {
        private Inventory inventory;
        private final Map<Integer, String> actions = new HashMap<>();

        @Override
        public Inventory getInventory() {
            return inventory;
        }
    }

    // ------------------------------------------------------------------
    // Cycle de vie
    // ------------------------------------------------------------------

    @Override
    public void onEnable() {
        saveDefaultConfig();
        getServer().getPluginManager().registerEvents(this, this);
        PluginCommand cmd = getCommand("bienvenue");
        if (cmd != null) {
            cmd.setExecutor(this);
            cmd.setTabCompleter(this);
        }
    }

    @Override
    public void onDisable() {
        for (UUID id : new ArrayList<>(viewing)) {
            Player p = Bukkit.getPlayer(id);
            confirmed.add(id);
            if (p != null) {
                p.closeInventory();
                stopAmbience(p);
            }
        }
        viewing.clear();
        confirmed.clear();
    }

    // ------------------------------------------------------------------
    // Événements
    // ------------------------------------------------------------------

    @EventHandler
    public void onJoin(PlayerJoinEvent e) {
        Player p = e.getPlayer();
        clearStaleState(p);

        FileConfiguration c = getConfig();
        if (c.getBoolean("show-on-first-join-only", false) && p.hasPlayedBefore()) return;
        if (p.hasPermission("welcomemenu.bypass")) return;

        long delay = Math.max(1L, c.getLong("open-delay-ticks", 20L));
        Bukkit.getScheduler().runTaskLater(this, () -> {
            if (p.isOnline()) showMenu(p);
        }, delay);
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent e) {
        Player p = e.getPlayer();
        viewing.remove(p.getUniqueId());
        confirmed.remove(p.getUniqueId());
        stopAmbience(p);
    }

    @EventHandler
    public void onClick(InventoryClickEvent e) {
        if (!(e.getInventory().getHolder() instanceof MenuHolder holder)) return;
        e.setCancelled(true); // rien ne peut être pris / déplacé

        if (!(e.getWhoClicked() instanceof Player p)) return;
        if (e.getClickedInventory() == null || e.getClickedInventory() != e.getInventory()) return;

        String action = holder.actions.get(e.getSlot());
        if (action != null) runAction(p, action);
    }

    @EventHandler
    public void onDrag(InventoryDragEvent e) {
        if (e.getInventory().getHolder() instanceof MenuHolder) {
            e.setCancelled(true);
        }
    }

    @EventHandler
    public void onClose(InventoryCloseEvent e) {
        if (!(e.getInventory().getHolder() instanceof MenuHolder)) return;
        if (!(e.getPlayer() instanceof Player p)) return;

        UUID id = p.getUniqueId();
        boolean forceButton = !getConfig().getBoolean("menu.close-with-escape", true);

        // Échap interdit : on rouvre le menu au tick suivant
        if (forceButton && !confirmed.contains(id) && isEnabled() && p.isOnline()) {
            Bukkit.getScheduler().runTask(this, () -> {
                if (p.isOnline() && viewing.contains(id)) {
                    p.openInventory(buildMenu());
                }
            });
            return;
        }

        confirmed.remove(id);
        viewing.remove(id);
        stopAmbience(p);
    }

    // ------------------------------------------------------------------
    // Commande /bienvenue
    // ------------------------------------------------------------------

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (args.length > 0 && args[0].equalsIgnoreCase("reload")) {
            if (!sender.hasPermission("welcomemenu.admin")) {
                sender.sendMessage(MM.deserialize("<red>Tu n'as pas la permission."));
                return true;
            }
            reloadConfig();
            sender.sendMessage(MM.deserialize("<green>Configuration rechargée."));
            return true;
        }

        if (sender instanceof Player p) {
            showMenu(p);
        } else {
            sender.sendMessage("Commande réservée aux joueurs.");
        }
        return true;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (args.length == 1 && sender.hasPermission("welcomemenu.admin")) {
            return List.of("reload");
        }
        return List.of();
    }

    // ------------------------------------------------------------------
    // Menu
    // ------------------------------------------------------------------

    private void showMenu(Player p) {
        UUID id = p.getUniqueId();
        if (viewing.contains(id)) return;

        viewing.add(id);
        InventoryView view = p.openInventory(buildMenu());
        if (view == null) {
            viewing.remove(id);
            return;
        }
        startAmbience(p);
    }

    private Inventory buildMenu() {
        FileConfiguration c = getConfig();
        MenuHolder holder = new MenuHolder();

        int rows = Math.max(1, Math.min(6, c.getInt("menu.rows", 5)));
        Component title = MM.deserialize(c.getString("menu.title", "<red>Bienvenue"));
        Inventory inv = Bukkit.createInventory(holder, rows * 9, title);
        holder.inventory = inv;

        // Décor : fond + bordure
        Material fillerMat = Material.matchMaterial(c.getString("menu.filler", "BLACK_STAINED_GLASS_PANE"));
        Material borderMat = Material.matchMaterial(c.getString("menu.border", ""));
        for (int slot = 0; slot < inv.getSize(); slot++) {
            int row = slot / 9;
            int col = slot % 9;
            boolean isBorder = row == 0 || row == rows - 1 || col == 0 || col == 8;
            if (isBorder && borderMat != null) {
                inv.setItem(slot, blank(borderMat));
            } else if (fillerMat != null) {
                inv.setItem(slot, blank(fillerMat));
            }
        }

        // Items définis dans config.yml
        ConfigurationSection items = c.getConfigurationSection("items");
        if (items != null) {
            for (String key : items.getKeys(false)) {
                ConfigurationSection s = items.getConfigurationSection(key);
                if (s == null) continue;

                int slot = s.getInt("slot", 0);
                if (slot < 0 || slot >= inv.getSize()) {
                    getLogger().warning("Item '" + key + "' : slot " + slot + " hors du menu.");
                    continue;
                }

                Material mat = Material.matchMaterial(s.getString("material", "PAPER"));
                if (mat == null) mat = Material.PAPER;

                ItemStack item = new ItemStack(mat);
                ItemMeta meta = item.getItemMeta();
                meta.displayName(noItalic(MM.deserialize(s.getString("name", " "))));

                List<Component> lore = new ArrayList<>();
                for (String line : s.getStringList("lore")) {
                    lore.add(noItalic(MM.deserialize(line)));
                }
                meta.lore(lore);

                if (s.getBoolean("glow", false)) {
                    meta.setEnchantmentGlintOverride(true);
                }
                item.setItemMeta(meta);

                inv.setItem(slot, item);
                holder.actions.put(slot, s.getString("action", "NONE"));
            }
        }
        return inv;
    }

    private ItemStack blank(Material material) {
        ItemStack item = new ItemStack(material);
        ItemMeta meta = item.getItemMeta();
        meta.displayName(Component.text(" "));
        meta.setHideTooltip(true);
        item.setItemMeta(meta);
        return item;
    }

    private Component noItalic(Component component) {
        return component.decorationIfAbsent(TextDecoration.ITALIC, TextDecoration.State.FALSE);
    }

    // ------------------------------------------------------------------
    // Actions des boutons
    // ------------------------------------------------------------------

    private void runAction(Player p, String action) {
        String upper = action.toUpperCase(Locale.ROOT);
        boolean close = upper.equals("CLOSE");
        boolean playerCmd = upper.startsWith("PLAYER:");
        boolean consoleCmd = upper.startsWith("CONSOLE:");
        if (!close && !playerCmd && !consoleCmd) return; // NONE ou inconnu

        UUID id = p.getUniqueId();
        // On exécute au tick suivant : fermer un inventaire pendant un clic est risqué
        Bukkit.getScheduler().runTask(this, () -> {
            confirmed.add(id);
            p.closeInventory();
            confirmed.remove(id);

            if (playerCmd) {
                String cmd = action.substring(7).trim().replace("%player%", p.getName());
                p.performCommand(cmd);
            } else if (consoleCmd) {
                String cmd = action.substring(8).trim().replace("%player%", p.getName());
                Bukkit.dispatchCommand(Bukkit.getConsoleSender(), cmd);
            }
        });
    }

    // ------------------------------------------------------------------
    // Ambiance : écran noir + musique
    // ------------------------------------------------------------------

    private void startAmbience(Player p) {
        FileConfiguration c = getConfig();

        p.setInvulnerable(true);

        if (c.getBoolean("blackscreen", true)) {
            p.addPotionEffect(new PotionEffect(
                    PotionEffectType.BLINDNESS, PotionEffect.INFINITE_DURATION, 0, false, false, false));
        }

        if (c.getBoolean("music.enabled", true)) {
            try {
                Sound music = Sound.sound(
                        Key.key(c.getString("music.sound", DEFAULT_SOUND)),
                        Sound.Source.RECORD,
                        (float) c.getDouble("music.volume", 1.0),
                        (float) c.getDouble("music.pitch", 1.0));
                p.playSound(music, Sound.Emitter.self()); // la musique suit le joueur
            } catch (Exception ex) {
                getLogger().warning("Son invalide dans config.yml : " + ex.getMessage());
            }
        }
    }

    private void stopAmbience(Player p) {
        try {
            p.stopSound(SoundStop.namedOnSource(
                    Key.key(getConfig().getString("music.sound", DEFAULT_SOUND)), Sound.Source.RECORD));
        } catch (Exception ignored) {
            // son invalide : rien à couper
        }
        removeInfiniteBlindness(p);
        p.setInvulnerable(false);
    }

    /** Nettoie un éventuel écran noir resté coincé (ex : crash serveur menu ouvert). */
    private void clearStaleState(Player p) {
        removeInfiniteBlindness(p);
        GameMode gm = p.getGameMode();
        if (gm == GameMode.SURVIVAL || gm == GameMode.ADVENTURE) {
            p.setInvulnerable(false);
        }
    }

    private void removeInfiniteBlindness(Player p) {
        PotionEffect fx = p.getPotionEffect(PotionEffectType.BLINDNESS);
        if (fx != null && fx.getDuration() == PotionEffect.INFINITE_DURATION) {
            p.removePotionEffect(PotionEffectType.BLINDNESS);
        }
    }
}
