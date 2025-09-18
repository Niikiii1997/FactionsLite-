
package com.example.factionslite;

import org.bukkit.*;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.block.Block;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.EntityExplodeEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemFlag;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.util.Vector;
import org.bukkit.ChatColor;

import java.io.File;
import java.io.IOException;
import java.util.*;
import java.util.stream.Collectors;

public class FactionsLite extends JavaPlugin implements Listener {

    private final Map<String, Faction> factionsByName = new HashMap<>();
    private final Map<UUID, String> playerFaction = new HashMap<>();
    private final Map<String, Long> activeRaids = new HashMap<>(); // targetName -> endTime (ms)

    @Override
    public void onEnable() {
        saveDefaultConfig();
        getConfig().addDefault("faction.base.size", 96);
        getConfig().addDefault("faction.base.buffer", 16);
        getConfig().addDefault("raid.duration.seconds", 900);
        // Newbie shield + defend
        getConfig().addDefault("shield.newbie.hours", 24); // 24h protection for new factions
        getConfig().addDefault("shield.defend.duration.seconds", 180); // 3 min defend
        getConfig().addDefault("shield.defend.cooldown.seconds", 900); // 15 min cooldown
        getConfig().options().copyDefaults(true);
        saveConfig();

        loadData();
        getServer().getPluginManager().registerEvents(this, this);

        // periodic buffs + defend effects tick
        new BukkitRunnable() {
            @Override public void run() {
                for (Player p : Bukkit.getOnlinePlayers()) {
                    applyBuffsIfInFactionWorld(p);
                    applyDefendEffectsTick(p);
                }
            }
        }.runTaskTimer(this, 40L, 100L);

        getLogger().info("FactionsLite v1.2.0 enabled. Factions: " + factionsByName.size());
    }

    @Override
    public void onDisable() { saveData(); }

    // ===== Data persistence =====
    private File dataFile() { return new File(getDataFolder(), "data.yml"); }

    private void loadData() {
        getDataFolder().mkdirs();
        if (!dataFile().exists()) return;
        var yml = org.bukkit.configuration.file.YamlConfiguration.loadConfiguration(dataFile());
        var sec = yml.getConfigurationSection("factions");
        if (sec != null) {
            for (String name : sec.getKeys(false)) {
                String tag = sec.getString(name + ".tag", name);
                String ownerStr = sec.getString(name + ".owner");
                UUID owner = ownerStr == null ? null : UUID.fromString(ownerStr);
                Faction f = new Faction(name, tag, owner);
                for (String s : sec.getStringList(name + ".members")) f.members.add(UUID.fromString(s));
                if (sec.isConfigurationSection(name + ".home")) {
                    var hs = sec.getConfigurationSection(name + ".home");
                    String world = hs.getString("world");
                    double x = hs.getDouble("x"), y = hs.getDouble("y"), z = hs.getDouble("z");
                    float yaw = (float) hs.getDouble("yaw"), pitch = (float) hs.getDouble("pitch");
                    if (Bukkit.getWorld(world) != null) f.home = new Location(Bukkit.getWorld(world), x, y, z, yaw, pitch);
                }
                for (String c : sec.getStringList(name + ".claims")) {
                    String[] p = c.split(";"); f.claims.add(new ChunkPos(p[0], Integer.parseInt(p[1]), Integer.parseInt(p[2])));
                }
                f.speedLevel = sec.getInt(name + ".upgrades.speed", 0);
                f.damageLevel = sec.getInt(name + ".upgrades.damage", 0);
                f.heartsLevel = sec.getInt(name + ".upgrades.hearts", 0);
                f.createdAt = sec.getLong(name + ".meta.createdAt", System.currentTimeMillis());
                f.defendUntil = sec.getLong(name + ".meta.defendUntil", 0L);
                f.defendCooldownUntil = sec.getLong(name + ".meta.defendCooldownUntil", 0L);
                factionsByName.put(name.toLowerCase(), f);
            }
        }
        var psec = yml.getConfigurationSection("players");
        if (psec != null) for (String uuid : psec.getKeys(false)) playerFaction.put(UUID.fromString(uuid), psec.getString(uuid));
    }

    private void saveData() {
        var yml = new org.bukkit.configuration.file.YamlConfiguration();
        var sec = yml.createSection("factions");
        for (Faction f : factionsByName.values()) {
            String base = f.name;
            sec.set(base + ".tag", f.tag);
            sec.set(base + ".owner", f.owner == null ? null : f.owner.toString());
            sec.set(base + ".members", f.members.stream().map(UUID::toString).collect(Collectors.toList()));
            if (f.home != null) {
                var hs = sec.createSection(base + ".home");
                hs.set("world", f.home.getWorld().getName());
                hs.set("x", f.home.getX()); hs.set("y", f.home.getY()); hs.set("z", f.home.getZ());
                hs.set("yaw", f.home.getYaw()); hs.set("pitch", f.home.getPitch());
            }
            List<String> claimStr = new ArrayList<>(); for (ChunkPos cp : f.claims) claimStr.add(cp.world + ";" + cp.x + ";" + cp.z);
            sec.set(base + ".claims", claimStr);
            sec.set(base + ".upgrades.speed", f.speedLevel);
            sec.set(base + ".upgrades.damage", f.damageLevel);
            sec.set(base + ".upgrades.hearts", f.heartsLevel);
            sec.set(base + ".meta.createdAt", f.createdAt);
            sec.set(base + ".meta.defendUntil", f.defendUntil);
            sec.set(base + ".meta.defendCooldownUntil", f.defendCooldownUntil);
        }
        var psec = yml.createSection("players");
        for (var e : playerFaction.entrySet()) psec.set(e.getKey().toString(), e.getValue());
        try { yml.save(dataFile()); } catch (IOException e) { e.printStackTrace(); }
    }

    // ===== Utility =====
    private String color(String s) { return ChatColor.translateAlternateColorCodes('&', s); }
    private Faction getPlayerFaction(Player p) { String fn = playerFaction.get(p.getUniqueId()); return fn == null ? null : factionsByName.get(fn.toLowerCase()); }
    private String worldNameFor(String factionName) { return "f_" + factionName.toLowerCase(); }

    private boolean insideBaseSquare(Faction f, Location loc) {
        int size = getConfig().getInt("faction.base.size"); int half = size/2;
        Location c = f.baseCenter(); if (c == null || !Objects.equals(loc.getWorld(), c.getWorld())) return false;
        return Math.abs(loc.getBlockX() - c.getBlockX()) <= half && Math.abs(loc.getBlockZ() - c.getBlockZ()) <= half;
    }
    private boolean insideSiegeRing(Faction f, Location loc) {
        int size = getConfig().getInt("faction.base.size"); int half = size/2; int buffer = getConfig().getInt("faction.base.buffer");
        Location c = f.baseCenter(); if (c == null || !Objects.equals(loc.getWorld(), c.getWorld())) return false;
        int dx = Math.abs(loc.getBlockX() - c.getBlockX()), dz = Math.abs(loc.getBlockZ() - c.getBlockZ());
        boolean outsideBase = dx > half || dz > half; boolean insideOuter = dx <= (half + buffer) && dz <= (half + buffer);
        return outsideBase && insideOuter;
    }
    private Faction getFactionByWorld(World w) {
        if (w == null) return null; String n = w.getName();
        for (Faction f : factionsByName.values()) if (n.equalsIgnoreCase(worldNameFor(f.name))) return f;
        return null;
    }
    private String getFactionAt(org.bukkit.Chunk chunk) {
        String world = chunk.getWorld().getName(); int x = chunk.getX(), z = chunk.getZ();
        for (Faction f : factionsByName.values()) for (ChunkPos cp : f.claims) if (cp.world.equals(world) && cp.x == x && cp.z == z) return f.name;
        return null;
    }

    // ===== Shields & Defend =====
    private boolean hasNewbieShield(Faction f) {
        long hrs = getConfig().getInt("shield.newbie.hours");
        long until = f.createdAt + hrs*3600_000L;
        return System.currentTimeMillis() < until;
    }
    private boolean isDefending(Faction f) { return System.currentTimeMillis() < f.defendUntil; }
    private boolean isShielded(Faction f) { return hasNewbieShield(f) || isDefending(f); }

    private void applyDefendEffectsTick(Player p) {
        Faction f = getPlayerFaction(p);
        if (f == null) return;
        if (getFactionByWorld(p.getWorld()) != f) return;
        if (!isDefending(f)) return;
        // defenders get Resistance + Regen while in base
        if (insideBaseSquare(f, p.getLocation())) {
            p.addPotionEffect(new org.bukkit.potion.PotionEffect(org.bukkit.potion.PotionEffectType.DAMAGE_RESISTANCE, 140, 0, true, false, false));
            p.addPotionEffect(new org.bukkit.potion.PotionEffect(org.bukkit.potion.PotionEffectType.REGENERATION, 140, 0, true, false, false));
        }
        // enemies inside base get Slowness
        for (Player other : p.getWorld().getPlayers()) {
            if (other == p) continue;
            Faction of = getPlayerFaction(other);
            if (of == f) continue;
            if (insideBaseSquare(f, other.getLocation())) {
                other.addPotionEffect(new org.bukkit.potion.PotionEffect(org.bukkit.potion.PotionEffectType.SLOW, 100, 0, true, false, false));
            }
        }
    }

    // ===== Events =====
    @EventHandler
    public void onBlockBreak(BlockBreakEvent e) {
        Player pl = e.getPlayer();
        Faction pf = getPlayerFaction(pl);
        Location loc = e.getBlock().getLocation();
        Faction at = getFactionByWorld(loc.getWorld());
        if (at == null) {
            String owner = getFactionAt(e.getBlock().getChunk());
            if (owner == null) return;
            if (pf == null || !owner.equalsIgnoreCase(pf.name)) { e.setCancelled(true); pl.sendMessage(ChatColor.RED + "This land is claimed by " + owner + "."); }
            return;
        }
        if (!at.equals(pf)) { e.setCancelled(true); return; }
        if (!insideBaseSquare(at, loc)) { e.setCancelled(true); }
    }

    @EventHandler
    public void onBlockPlace(BlockPlaceEvent e) {
        Player pl = e.getPlayer();
        Faction pf = getPlayerFaction(pl);
        Location loc = e.getBlock().getLocation();
        Faction at = getFactionByWorld(loc.getWorld());
        if (at == null) {
            String owner = getFactionAt(e.getBlock().getChunk());
            if (owner == null) return;
            if (pf == null || !owner.equalsIgnoreCase(pf.name)) { e.setCancelled(true); pl.sendMessage(ChatColor.RED + "This land is claimed by " + owner + "."); }
            return;
        }
        // In faction world
        if (!at.equals(pf)) {
            // Shield prohibits enemy placements entirely
            if (isShielded(at)) { e.setCancelled(true); return; }
            // Enemies can place TNT in siege ring during active raid
            if (e.getBlockPlaced().getType() == Material.TNT && isRaidActive(at.name) && insideSiegeRing(at, loc)) return;
            e.setCancelled(true); return;
        }
        // members placing inside base only
        if (!insideBaseSquare(at, loc)) e.setCancelled(true);
    }

    @EventHandler
    public void onPlayerMove(PlayerMoveEvent e) {
        Player p = e.getPlayer();
        World w = p.getWorld();
        Faction at = getFactionByWorld(w);
        if (at == null) return;
        if (!insideBaseSquare(at, e.getTo())) {
            p.teleport(e.getFrom());
            p.sendMessage(ChatColor.GRAY + "You cannot leave your faction base.");
        }
    }

    @EventHandler
    public void onExplode(EntityExplodeEvent e) {
        World w = e.getLocation().getWorld();
        Faction at = getFactionByWorld(w);
        if (at == null) return;
        // If shield is up, cancel explosions in base + ring
        if (isShielded(at)) {
            e.blockList().clear();
            e.setCancelled(true);
            return;
        }
        // Allow TNT to affect obsidian in faction worlds
        if (e.getEntityType() == EntityType.PRIMED_TNT || e.getEntityType() == EntityType.MINECART_TNT) {
            e.setYield(0.2f);
            // nothing to remove: let obsidian be breakable
        }
    }

    @EventHandler
    public void onInventoryClick(InventoryClickEvent e) {
        if (!(e.getWhoClicked() instanceof Player p)) return;
        if (!e.getView().getTitle().equals("Faction Upgrades")) return;
        e.setCancelled(true);
        ItemStack clicked = e.getCurrentItem(); if (clicked == null || clicked.getType() == Material.AIR) return;
        String name = ChatColor.stripColor(clicked.getItemMeta().getDisplayName());
        Faction f = getPlayerFaction(p); if (f == null) { p.closeInventory(); return; }
        if (name.startsWith("Speed")) { if (f.speedLevel < 3) f.speedLevel++; p.sendMessage(ChatColor.GREEN + "Speed upgraded to " + f.speedLevel); }
        else if (name.startsWith("Damage")) { if (f.damageLevel < 3) f.damageLevel++; p.sendMessage(ChatColor.GREEN + "Damage upgraded to " + f.damageLevel); }
        else if (name.startsWith("Hearts")) { if (f.heartsLevel < 3) f.heartsLevel++; p.sendMessage(ChatColor.GREEN + "Hearts upgraded to " + f.heartsLevel); }
        saveData();
        openUpgradeGui(p, f);
        applyBuffsIfInFactionWorld(p);
    }

    private void openUpgradeGui(Player p, Faction f) {
        Inventory inv = Bukkit.createInventory(null, 9, "Faction Upgrades");
        inv.setItem(2, upgradeItem(Material.SUGAR, ChatColor.AQUA + "Speed (" + f.speedLevel + "/3)", List.of("Move faster in your base")));
        inv.setItem(4, upgradeItem(Material.IRON_SWORD, ChatColor.RED + "Damage (" + f.damageLevel + "/3)", List.of("Deal more damage in your base")));
        inv.setItem(6, upgradeItem(Material.REDSTONE, ChatColor.GOLD + "Hearts (" + f.heartsLevel + "/3)", List.of("Extra max health in your base")));
        p.openInventory(inv);
    }
    private ItemStack upgradeItem(Material mat, String name, List<String> lore) {
        ItemStack is = new ItemStack(mat);
        ItemMeta im = is.getItemMeta();
        im.setDisplayName(name);
        im.setLore(lore.stream().map(s -> ChatColor.GRAY + s).collect(Collectors.toList()));
        im.addItemFlags(ItemFlag.HIDE_ATTRIBUTES);
        is.setItemMeta(im);
        return is;
    }

    private void applyBuffsIfInFactionWorld(Player p) {
        Faction f = getPlayerFaction(p); if (f == null) return;
        if (getFactionByWorld(p.getWorld()) != f) return;
        if (f.speedLevel > 0) p.addPotionEffect(new org.bukkit.potion.PotionEffect(org.bukkit.potion.PotionEffectType.SPEED, 220, f.speedLevel-1, true, false, false));
        if (f.damageLevel > 0) p.addPotionEffect(new org.bukkit.potion.PotionEffect(org.bukkit.potion.PotionEffectType.INCREASE_DAMAGE, 220, f.damageLevel-1, true, false, false));
        var attr = p.getAttribute(Attribute.GENERIC_MAX_HEALTH);
        if (attr != null) {
            double base = 20.0, extra = f.heartsLevel * 2.0;
            if (attr.getBaseValue() != base + extra) {
                attr.setBaseValue(base + extra);
                if (p.getHealth() > attr.getBaseValue()) p.setHealth(attr.getBaseValue());
            }
        }
    }

    // ===== Commands =====
    @Override
    public boolean onCommand(CommandSender sender, Command cmd, String label, String[] args) {
        if (!cmd.getName().equalsIgnoreCase("f")) return false;
        if (!(sender instanceof Player)) { sender.sendMessage("Players only."); return true; }
        Player p = (Player) sender;
        if (args.length == 0) { help(p); return true; }
        String sub = args[0].toLowerCase();
        switch (sub) {
            case "help" -> help(p);
            case "create" -> {
                if (args.length < 2) { p.sendMessage(color("&eUsage: /f create <name>")); break; }
                String name = args[1];
                if (playerFaction.containsKey(p.getUniqueId())) { p.sendMessage(color("&cYou're already in a faction.")); break; }
                if (factionsByName.containsKey(name.toLowerCase())) { p.sendMessage(color("&cThat name is taken.")); break; }
                Faction f = new Faction(name, name, p.getUniqueId());
                f.members.add(p.getUniqueId());
                f.createdAt = System.currentTimeMillis();
                factionsByName.put(name.toLowerCase(), f);
                playerFaction.put(p.getUniqueId(), name);
                // create faction world
                WorldCreator wc = new WorldCreator(worldNameFor(name));
                wc.environment(World.Environment.NORMAL);
                wc.type(WorldType.NORMAL);
                World w = Bukkit.createWorld(wc);
                w.setGameRule(GameRule.KEEP_INVENTORY, true);
                f.home = w.getSpawnLocation();
                p.sendMessage(color("&aCreated faction &e" + name + "&a and its world &e" + w.getName()));
                long newbieHrs = getConfig().getInt("shield.newbie.hours");
                p.sendMessage(color("&7Newbie Shield active for &e" + newbieHrs + "h&7. Raids/TNT blocked in your world."));
            }
            case "base" -> {
                Faction f = getPlayerFaction(p);
                if (f == null) { p.sendMessage(color("&cJoin a faction first.")); break; }
                if (f.home == null) f.home = f.baseCenter();
                p.teleport(f.home);
                p.sendMessage(color("&aTeleported to your faction base."));
                applyBuffsIfInFactionWorld(p);
            }
            case "raid" -> {
                if (args.length < 2) { p.sendMessage(color("&eUsage: /f raid <faction>")); break; }
                String target = args[1].toLowerCase();
                Faction tf = factionsByName.get(target);
                Faction pf = getPlayerFaction(p);
                if (tf == null) { p.sendMessage(color("&cNo such faction.")); break; }
                if (pf != null && pf.name.equalsIgnoreCase(tf.name)) { p.sendMessage(color("&cYou cannot raid your own faction.")); break; }
                if (hasNewbieShield(tf)) { p.sendMessage(color("&cThat faction is under Newbie Shield. Try later.")); break; }
                long dur = getConfig().getInt("raid.duration.seconds") * 1000L;
                activeRaids.put(tf.name.toLowerCase(), System.currentTimeMillis() + dur);
                Bukkit.broadcastMessage(color("&c&lRAID &7> &e" + p.getName() + " &7started a raid on &c" + tf.name + "&7! (&e" + (dur/1000) + "s&7)"));
            }
            case "defend" -> {
                Faction f = getPlayerFaction(p);
                if (f == null) { p.sendMessage(color("&cJoin a faction first.")); break; }
                if (!isRaidActive(f.name)) { p.sendMessage(color("&eYou can only use /f defend while being raided.")); break; }
                long now = System.currentTimeMillis();
                if (now < f.defendCooldownUntil) {
                    long sec = (f.defendCooldownUntil - now + 999)/1000;
                    p.sendMessage(color("&cDefend is on cooldown for &e" + sec + "s"));
                    break;
                }
                long dur = getConfig().getInt("shield.defend.duration.seconds") * 1000L;
                long cd  = getConfig().getInt("shield.defend.cooldown.seconds") * 1000L;
                f.defendUntil = now + dur;
                f.defendCooldownUntil = now + cd;
                saveData();
                Bukkit.broadcastMessage(color("&a&lDEFEND &7> Faction &a" + f.name + " &7activated Defend for &e" + (dur/1000) + "s&7!"));
            }
            case "upgrades" -> {
                Faction f = getPlayerFaction(p);
                if (f == null) { p.sendMessage(color("&cJoin a faction first.")); break; }
                openUpgradeGui(p, f);
            }
            default -> p.sendMessage(color("&eUnknown subcommand. Use /f help"));
        }
        return true;
    }

    private boolean isRaidActive(String targetFaction) {
        Long end = activeRaids.get(targetFaction.toLowerCase());
        return end != null && System.currentTimeMillis() < end;
    }

    private void help(Player p) {
        p.sendMessage(color("&6&lFactionsLite &7- Commands"));
        p.sendMessage(color("&e/f create <name>&7 - Create faction + world (newbie shield active)"));
        p.sendMessage(color("&e/f base&7 - Teleport to your faction base"));
        p.sendMessage(color("&e/f raid <faction>&7 - Start a raid (enables TNT ring)"));
        p.sendMessage(color("&e/f defend&7 - Activate temporary defend shield (during raid)"));
        p.sendMessage(color("&e/f upgrades&7 - Open faction upgrades GUI"));
        p.sendMessage(color("&7Config: shield.newbie.hours, shield.defend.duration.seconds, shield.defend.cooldown.seconds"));
    }

    // ===== data classes =====
    static class Faction {
        final String name;
        String tag;
        UUID owner;
        Set<UUID> members = new HashSet<>();
        Set<UUID> invites = new HashSet<>();
        Set<ChunkPos> claims = new HashSet<>();
        Location home;
        int speedLevel = 0, damageLevel = 0, heartsLevel = 0;
        long createdAt = System.currentTimeMillis();
        long defendUntil = 0L;
        long defendCooldownUntil = 0L;

        Faction(String name, String tag, UUID owner) { this.name = name; this.tag = tag; this.owner = owner; }
        Location baseCenter() {
            if (home != null) return home;
            World w = Bukkit.getWorld("f_" + name.toLowerCase());
            return w == null ? null : w.getSpawnLocation();
        }
    }
    static class ChunkPos {
        final String world; final int x; final int z;
        ChunkPos(String w, int x, int z) { world = w; this.x = x; this.z = z; }
        @Override public boolean equals(Object o){ if (this==o) return true; if (!(o instanceof ChunkPos that)) return false; return x==that.x && z==that.z && Objects.equals(world, that.world); }
        @Override public int hashCode(){ return Objects.hash(world,x,z); }
    }
}
