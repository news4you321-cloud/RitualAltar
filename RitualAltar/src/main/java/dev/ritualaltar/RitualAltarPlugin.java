package dev.ritualaltar;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Bukkit;
import org.bukkit.Color;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.PluginCommand;
import org.bukkit.command.TabExecutor;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Display;
import org.bukkit.entity.ItemDisplay;
import org.bukkit.entity.Player;
import org.bukkit.entity.TextDisplay;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockExplodeEvent;
import org.bukkit.event.entity.EntityExplodeEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.util.Transformation;
import org.joml.AxisAngle4f;
import org.joml.Vector3f;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * RitualAltar - a deepslate altar with four glowing pillars, a floating rotating item,
 * a hologram ("ALTAR / <item name> / RITUAL mm:ss") and a countdown. When the countdown
 * reaches zero a player can right-click the centre block to claim the item.
 */
public final class RitualAltarPlugin extends JavaPlugin implements Listener, TabExecutor {

    private enum State { RUNNING, READY, CLAIMED }

    private static final TextColor TAN = TextColor.color(0xD9CBA3);
    private static final TextColor GOLD = TextColor.color(0xFFAA00);

    private final Map<String, Altar> altars = new LinkedHashMap<>();
    private File dataFile;
    private int saveCounter = 0;

    // ------------------------------------------------------------------ lifecycle

    @Override
    public void onEnable() {
        saveDefaultConfig();
        dataFile = new File(getDataFolder(), "altars.yml");

        getServer().getPluginManager().registerEvents(this, this);
        PluginCommand cmd = getCommand("altar");
        if (cmd != null) {
            cmd.setExecutor(this);
            cmd.setTabCompleter(this);
        }

        // Spawn displays one tick later so every world is surely loaded.
        Bukkit.getScheduler().runTask(this, this::loadAltars);
        Bukkit.getScheduler().runTaskTimer(this, this::secondTick, 20L, 20L);
        Bukkit.getScheduler().runTaskTimer(this, this::animTick, 2L, 2L);
    }

    @Override
    public void onDisable() {
        saveAltars();
        for (Altar a : altars.values()) {
            a.removeDisplays();
        }
        altars.clear();
    }

    // ------------------------------------------------------------------ persistence

    private void loadAltars() {
        altars.clear();
        if (!dataFile.exists()) return;
        YamlConfiguration yml = YamlConfiguration.loadConfiguration(dataFile);
        ConfigurationSection sec = yml.getConfigurationSection("altars");
        if (sec == null) return;
        for (String id : sec.getKeys(false)) {
            ConfigurationSection s = sec.getConfigurationSection(id);
            if (s == null) continue;
            World w = Bukkit.getWorld(s.getString("world", ""));
            ItemStack item = s.getItemStack("item");
            if (w == null || item == null) {
                getLogger().warning("Skipping altar '" + id + "' (missing world or item).");
                continue;
            }
            Location center = new Location(w, s.getInt("x"), s.getInt("y"), s.getInt("z"));
            int duration = s.getInt("duration", 1460);
            Altar a = new Altar(id, center, item, duration);
            a.remaining = s.getInt("remaining", duration);
            try {
                a.state = State.valueOf(s.getString("state", "RUNNING"));
            } catch (IllegalArgumentException ex) {
                a.state = State.RUNNING;
            }
            altars.put(id, a);
            a.spawnDisplays();
        }
        getLogger().info("Loaded " + altars.size() + " altar(s).");
    }

    private void saveAltars() {
        YamlConfiguration yml = new YamlConfiguration();
        for (Altar a : altars.values()) {
            String p = "altars." + a.id;
            yml.set(p + ".world", a.center.getWorld().getName());
            yml.set(p + ".x", a.center.getBlockX());
            yml.set(p + ".y", a.center.getBlockY());
            yml.set(p + ".z", a.center.getBlockZ());
            yml.set(p + ".item", a.item);
            yml.set(p + ".duration", a.duration);
            yml.set(p + ".remaining", a.remaining);
            yml.set(p + ".state", a.state.name());
        }
        try {
            getDataFolder().mkdirs();
            yml.save(dataFile);
        } catch (IOException e) {
            getLogger().severe("Could not save altars.yml: " + e.getMessage());
        }
    }

    // ------------------------------------------------------------------ ticking

    /** Once per second: countdown, hologram refresh, ambient particles. */
    private void secondTick() {
        for (Altar a : altars.values()) {
            if (!a.isLoaded()) continue;
            if (a.state == State.RUNNING) {
                a.remaining--;
                if (a.remaining <= 0) {
                    a.remaining = 0;
                    a.state = State.READY;
                    a.onReady();
                }
            }
            a.refreshText();
            a.ambient();
        }
        if (++saveCounter >= 30) {
            saveCounter = 0;
            saveAltars();
        }
    }

    /** Every 2 ticks: spin and bob the floating item. */
    private void animTick() {
        for (Altar a : altars.values()) {
            if (!a.isLoaded()) continue;
            if (a.state != State.CLAIMED && (a.itemDisplay == null || !a.itemDisplay.isValid())
                    || a.textDisplay == null || !a.textDisplay.isValid()) {
                a.spawnDisplays();
                continue;
            }
            if (a.itemDisplay == null || !a.itemDisplay.isValid()) continue;
            a.angle += 0.10f;
            float bob = (float) Math.sin(a.angle * 0.5f) * 0.08f;
            a.itemDisplay.setInterpolationDelay(0);
            a.itemDisplay.setInterpolationDuration(2);
            a.itemDisplay.setTransformation(new Transformation(
                    new Vector3f(0f, bob, 0f),
                    new AxisAngle4f(a.angle, 0f, 1f, 0f),
                    new Vector3f(1f, 1f, 1f),
                    new AxisAngle4f()));
        }
    }

    // ------------------------------------------------------------------ events

    @EventHandler(priority = EventPriority.HIGH)
    public void onInteract(PlayerInteractEvent e) {
        if (e.getAction() != Action.RIGHT_CLICK_BLOCK || e.getHand() != EquipmentSlot.HAND) return;
        Block clicked = e.getClickedBlock();
        if (clicked == null) return;
        Altar a = altarAtCenterBlock(clicked);
        if (a == null) return;

        e.setCancelled(true);
        Player p = e.getPlayer();

        switch (a.state) {
            case RUNNING -> p.sendActionBar(Component.text("The ritual is still in progress: ", NamedTextColor.GRAY)
                    .append(Component.text(fmt(a.remaining), GOLD)));
            case CLAIMED -> p.sendActionBar(Component.text("This altar is spent.", NamedTextColor.GRAY));
            case READY -> {
                if (!p.hasPermission("ritualaltar.claim")) {
                    p.sendActionBar(Component.text("You may not claim this.", NamedTextColor.RED));
                    return;
                }
                a.claim(p);
            }
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onBreak(BlockBreakEvent e) {
        if (!getConfig().getBoolean("protect-blocks", true)) return;
        if (!isProtected(e.getBlock())) return;
        if (e.getPlayer().hasPermission("ritualaltar.admin")) return;
        e.setCancelled(true);
    }

    @EventHandler(ignoreCancelled = true)
    public void onEntityExplode(EntityExplodeEvent e) {
        if (getConfig().getBoolean("protect-blocks", true)) {
            e.blockList().removeIf(this::isProtected);
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onBlockExplode(BlockExplodeEvent e) {
        if (getConfig().getBoolean("protect-blocks", true)) {
            e.blockList().removeIf(this::isProtected);
        }
    }

    private Altar altarAtCenterBlock(Block b) {
        for (Altar a : altars.values()) {
            if (a.center.getWorld().equals(b.getWorld())
                    && a.center.getBlockX() == b.getX()
                    && a.center.getBlockY() + 1 == b.getY()
                    && a.center.getBlockZ() == b.getZ()) {
                return a;
            }
        }
        return null;
    }

    private boolean isProtected(Block b) {
        for (Altar a : altars.values()) {
            if (!a.center.getWorld().equals(b.getWorld())) continue;
            int dx = Math.abs(b.getX() - a.center.getBlockX());
            int dz = Math.abs(b.getZ() - a.center.getBlockZ());
            int dy = b.getY() - a.center.getBlockY();
            if (dx <= 3 && dz <= 3 && dy >= 0 && dy <= 3) return true;
        }
        return false;
    }

    // ------------------------------------------------------------------ commands

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!sender.hasPermission("ritualaltar.admin")) {
            msg(sender, "You don't have permission.", NamedTextColor.RED);
            return true;
        }
        if (args.length == 0) {
            help(sender);
            return true;
        }

        switch (args[0].toLowerCase(Locale.ROOT)) {
            case "create" -> {
                if (!(sender instanceof Player p)) {
                    msg(sender, "Only players can create altars.", NamedTextColor.RED);
                    return true;
                }
                if (args.length < 2) {
                    msg(sender, "Usage: /altar create <id> [mm:ss]  (hold the item in your main hand)", NamedTextColor.RED);
                    return true;
                }
                String id = args[1].toLowerCase(Locale.ROOT);
                if (!id.matches("[a-z0-9_\\-]{1,32}")) {
                    msg(sender, "The id may only contain a-z, 0-9, _ and -.", NamedTextColor.RED);
                    return true;
                }
                if (altars.containsKey(id)) {
                    msg(sender, "An altar with that id already exists.", NamedTextColor.RED);
                    return true;
                }
                ItemStack hand = p.getInventory().getItemInMainHand();
                if (hand.getType().isAir()) {
                    msg(sender, "Hold the item the altar should display.", NamedTextColor.RED);
                    return true;
                }
                int duration = parseTime(args.length >= 3 ? args[2] : getConfig().getString("default-duration", "24:20"));
                if (duration <= 0) {
                    msg(sender, "Invalid time. Use mm:ss (e.g. 24:20) or a number of seconds.", NamedTextColor.RED);
                    return true;
                }
                Location floor = p.getLocation().getBlock().getLocation().subtract(0, 1, 0);
                ItemStack shown = hand.clone();
                shown.setAmount(1);
                Altar a = new Altar(id, floor, shown, duration);
                altars.put(id, a);
                a.build();
                a.spawnDisplays();
                saveAltars();
                msg(sender, "Altar '" + id + "' created. Ritual length " + fmt(duration) + ".", NamedTextColor.GREEN);
            }
            case "remove" -> {
                Altar a = needAltar(sender, args);
                if (a == null) return true;
                a.removeDisplays();
                altars.remove(a.id);
                saveAltars();
                msg(sender, "Altar '" + a.id + "' removed (blocks stay in the world).", NamedTextColor.GREEN);
            }
            case "list" -> {
                if (altars.isEmpty()) {
                    msg(sender, "No altars yet.", NamedTextColor.GRAY);
                    return true;
                }
                for (Altar a : altars.values()) {
                    msg(sender, a.id + " - " + a.state + " " + fmt(a.remaining)
                            + " @ " + a.center.getWorld().getName() + " "
                            + a.center.getBlockX() + " " + a.center.getBlockY() + " " + a.center.getBlockZ(),
                            NamedTextColor.YELLOW);
                }
            }
            case "start" -> {
                Altar a = needAltar(sender, args);
                if (a == null) return true;
                a.remaining = a.duration;
                a.state = State.RUNNING;
                a.spawnDisplays();
                saveAltars();
                msg(sender, "Ritual restarted (" + fmt(a.duration) + ").", NamedTextColor.GREEN);
            }
            case "time" -> {
                if (args.length < 3) {
                    msg(sender, "Usage: /altar time <id> <mm:ss>", NamedTextColor.RED);
                    return true;
                }
                Altar a = needAltar(sender, args);
                if (a == null) return true;
                int t = parseTime(args[2]);
                if (t <= 0) {
                    msg(sender, "Invalid time.", NamedTextColor.RED);
                    return true;
                }
                a.remaining = t;
                if (a.state != State.RUNNING) {
                    a.state = State.RUNNING;
                    a.spawnDisplays();
                }
                a.refreshText();
                saveAltars();
                msg(sender, "Remaining time set to " + fmt(t) + ".", NamedTextColor.GREEN);
            }
            case "item" -> {
                if (!(sender instanceof Player p)) {
                    msg(sender, "Only players can use this.", NamedTextColor.RED);
                    return true;
                }
                Altar a = needAltar(sender, args);
                if (a == null) return true;
                ItemStack hand = p.getInventory().getItemInMainHand();
                if (hand.getType().isAir()) {
                    msg(sender, "Hold the new item first.", NamedTextColor.RED);
                    return true;
                }
                a.item = hand.clone();
                a.item.setAmount(1);
                a.spawnDisplays();
                saveAltars();
                msg(sender, "Altar item changed.", NamedTextColor.GREEN);
            }
            case "reload" -> {
                reloadConfig();
                msg(sender, "Config reloaded.", NamedTextColor.GREEN);
            }
            default -> help(sender);
        }
        return true;
    }

    private Altar needAltar(CommandSender sender, String[] args) {
        if (args.length < 2) {
            msg(sender, "Usage: /altar " + args[0] + " <id>", NamedTextColor.RED);
            return null;
        }
        Altar a = altars.get(args[1].toLowerCase(Locale.ROOT));
        if (a == null) msg(sender, "No altar with that id.", NamedTextColor.RED);
        return a;
    }

    private void help(CommandSender s) {
        msg(s, "/altar create <id> [mm:ss]  - build an altar showing the item in your hand", NamedTextColor.YELLOW);
        msg(s, "/altar remove <id>          - remove the altar entry and hologram", NamedTextColor.YELLOW);
        msg(s, "/altar list                 - list altars", NamedTextColor.YELLOW);
        msg(s, "/altar start <id>           - restart the ritual", NamedTextColor.YELLOW);
        msg(s, "/altar time <id> <mm:ss>    - set the remaining time", NamedTextColor.YELLOW);
        msg(s, "/altar item <id>            - change the displayed/reward item", NamedTextColor.YELLOW);
        msg(s, "/altar reload               - reload config.yml", NamedTextColor.YELLOW);
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        List<String> out = new ArrayList<>();
        if (!sender.hasPermission("ritualaltar.admin")) return out;
        if (args.length == 1) {
            for (String s : List.of("create", "remove", "list", "start", "time", "item", "reload")) {
                if (s.startsWith(args[0].toLowerCase(Locale.ROOT))) out.add(s);
            }
        } else if (args.length == 2 && !args[0].equalsIgnoreCase("create") && !args[0].equalsIgnoreCase("list")
                && !args[0].equalsIgnoreCase("reload")) {
            for (String id : altars.keySet()) {
                if (id.startsWith(args[1].toLowerCase(Locale.ROOT))) out.add(id);
            }
        }
        return out;
    }

    // ------------------------------------------------------------------ helpers

    private static void msg(CommandSender s, String text, NamedTextColor color) {
        s.sendMessage(Component.text(text, color));
    }

    static String fmt(int seconds) {
        return String.format("%02d:%02d", seconds / 60, seconds % 60);
    }

    /** Accepts "mm:ss" or a plain number of seconds. Returns -1 when invalid. */
    static int parseTime(String s) {
        try {
            if (s.contains(":")) {
                String[] parts = s.split(":");
                if (parts.length != 2) return -1;
                int m = Integer.parseInt(parts[0].trim());
                int sec = Integer.parseInt(parts[1].trim());
                if (m < 0 || sec < 0 || sec > 59) return -1;
                return m * 60 + sec;
            }
            return Integer.parseInt(s.trim());
        } catch (NumberFormatException ex) {
            return -1;
        }
    }

    private Material mat(String path, Material fallback) {
        Material m = Material.matchMaterial(getConfig().getString(path, fallback.name()));
        return m == null ? fallback : m;
    }

    // ------------------------------------------------------------------ altar

    private final class Altar {
        final String id;
        final Location center;   // floor centre block
        ItemStack item;
        final int duration;
        int remaining;
        State state = State.RUNNING;
        float angle = 0f;
        ItemDisplay itemDisplay;
        TextDisplay textDisplay;

        Altar(String id, Location center, ItemStack item, int duration) {
            this.id = id;
            this.center = center;
            this.item = item;
            this.duration = duration;
            this.remaining = duration;
        }

        boolean isLoaded() {
            World w = center.getWorld();
            return w != null && w.isChunkLoaded(center.getBlockX() >> 4, center.getBlockZ() >> 4);
        }

        /** Builds the 7x7 floor, centre block and four pillars. */
        void build() {
            World w = center.getWorld();
            int cx = center.getBlockX(), cy = center.getBlockY(), cz = center.getBlockZ();
            Material floor = mat("blocks.floor", Material.DEEPSLATE_TILES);
            Material pillar = mat("blocks.pillar", Material.CHISELED_DEEPSLATE);
            Material top = mat("blocks.pillar-top", Material.SOUL_LANTERN);
            Material mid = mat("blocks.center", Material.BLUE_ICE);
            boolean clear = getConfig().getBoolean("clear-above", true);

            for (int dx = -3; dx <= 3; dx++) {
                for (int dz = -3; dz <= 3; dz++) {
                    w.getBlockAt(cx + dx, cy, cz + dz).setType(floor, false);
                    if (clear) {
                        for (int dy = 1; dy <= 4; dy++) {
                            w.getBlockAt(cx + dx, cy + dy, cz + dz).setType(Material.AIR, false);
                        }
                    }
                }
            }
            w.getBlockAt(cx, cy + 1, cz).setType(mid, false);
            for (int sx : new int[]{-3, 3}) {
                for (int sz : new int[]{-3, 3}) {
                    w.getBlockAt(cx + sx, cy + 1, cz + sz).setType(pillar, false);
                    w.getBlockAt(cx + sx, cy + 2, cz + sz).setType(pillar, false);
                    w.getBlockAt(cx + sx, cy + 3, cz + sz).setType(top, false);
                }
            }
        }

        Location itemLoc() {
            return center.clone().add(0.5, 2.6, 0.5);
        }

        Location textLoc() {
            return center.clone().add(0.5, 3.7, 0.5);
        }

        void spawnDisplays() {
            removeDisplays();
            World w = center.getWorld();
            if (w == null) return;

            if (state != State.CLAIMED) {
                itemDisplay = w.spawn(itemLoc(), ItemDisplay.class, d -> {
                    d.setItemStack(item.clone());
                    d.setItemDisplayTransform(ItemDisplay.ItemDisplayTransform.FIXED);
                    d.setBillboard(Display.Billboard.FIXED);
                    d.setPersistent(false);
                    d.setViewRange(1.0f);
                });
            }
            textDisplay = w.spawn(textLoc(), TextDisplay.class, d -> {
                d.setBillboard(Display.Billboard.CENTER);
                d.setAlignment(TextDisplay.TextAlignment.CENTER);
                d.setDefaultBackground(false);
                d.setBackgroundColor(Color.fromARGB(110, 0, 0, 0));
                d.setSeeThrough(false);
                d.setPersistent(false);
                d.setViewRange(0.6f);
                d.text(buildText());
            });
        }

        void removeDisplays() {
            if (itemDisplay != null) {
                itemDisplay.remove();
                itemDisplay = null;
            }
            if (textDisplay != null) {
                textDisplay.remove();
                textDisplay = null;
            }
        }

        Component buildText() {
            Component line3 = switch (state) {
                case RUNNING -> Component.text("RITUAL ", NamedTextColor.GRAY)
                        .append(Component.text(fmt(remaining), GOLD));
                case READY -> Component.text("READY", NamedTextColor.GREEN, TextDecoration.BOLD)
                        .append(Component.text("  right-click to claim", NamedTextColor.GRAY)
                                .decoration(TextDecoration.BOLD, false));
                case CLAIMED -> Component.text("RITUAL COMPLETE", NamedTextColor.DARK_GRAY);
            };
            return Component.text("ALTAR", TAN)
                    .appendNewline()
                    .append(item.effectiveName().colorIfAbsent(NamedTextColor.WHITE).decorate(TextDecoration.BOLD))
                    .appendNewline()
                    .append(Component.text("              ", GOLD, TextDecoration.STRIKETHROUGH))
                    .appendNewline()
                    .append(line3);
        }

        void refreshText() {
            if (textDisplay != null && textDisplay.isValid()) {
                textDisplay.text(buildText());
            }
        }

        void ambient() {
            World w = center.getWorld();
            if (w == null || state == State.CLAIMED) return;
            int cx = center.getBlockX(), cy = center.getBlockY(), cz = center.getBlockZ();
            for (int sx : new int[]{-3, 3}) {
                for (int sz : new int[]{-3, 3}) {
                    w.spawnParticle(Particle.SOUL_FIRE_FLAME, cx + sx + 0.5, cy + 3.9, cz + sz + 0.5,
                            1, 0.1, 0.1, 0.1, 0.005);
                }
            }
            w.spawnParticle(Particle.ENCHANT, itemLoc(), state == State.READY ? 12 : 4, 0.6, 0.6, 0.6, 0.4);
        }

        void onReady() {
            World w = center.getWorld();
            if (w == null) return;
            Location loc = itemLoc();
            if (getConfig().getBoolean("lightning-effect", true)) {
                w.strikeLightningEffect(center.clone().add(0.5, 1, 0.5));
            }
            w.playSound(loc, Sound.BLOCK_BEACON_ACTIVATE, 2f, 1f);
            w.spawnParticle(Particle.TOTEM_OF_UNDYING, loc, 60, 0.5, 0.5, 0.5, 0.4);

            double r = getConfig().getDouble("announce-radius", 64);
            if (r > 0) {
                Component c = Component.text("The ritual at altar ", NamedTextColor.GRAY)
                        .append(Component.text(id, GOLD))
                        .append(Component.text(" is complete!", NamedTextColor.GRAY));
                for (Player p : w.getPlayers()) {
                    if (p.getLocation().distanceSquared(loc) <= r * r) p.sendMessage(c);
                }
            }
        }

        void claim(Player p) {
            Map<Integer, ItemStack> left = p.getInventory().addItem(item.clone());
            for (ItemStack rest : left.values()) {
                p.getWorld().dropItemNaturally(p.getLocation(), rest);
            }
            World w = center.getWorld();
            Location loc = itemLoc();
            w.playSound(loc, Sound.ENTITY_PLAYER_LEVELUP, 1.5f, 1f);
            w.spawnParticle(Particle.END_ROD, loc, 40, 0.4, 0.4, 0.4, 0.1);
            p.sendMessage(Component.text("You claimed ", NamedTextColor.GRAY)
                    .append(item.effectiveName().colorIfAbsent(NamedTextColor.WHITE))
                    .append(Component.text("!", NamedTextColor.GRAY)));

            if (getConfig().getBoolean("repeat", true)) {
                remaining = duration;
                state = State.RUNNING;
            } else {
                state = State.CLAIMED;
            }
            spawnDisplays();
            saveAltars();
        }
    }
}
