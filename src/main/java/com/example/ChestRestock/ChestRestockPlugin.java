package com.example.chestrestock;

import io.papermc.paper.command.brigadier.BasicCommand;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import org.bukkit.*;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.block.Block;
import org.bukkit.block.Chest;
import org.bukkit.block.data.BlockData;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.*;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.util.Transformation;
import org.joml.AxisAngle4f;
import org.joml.Vector3f;

import java.util.*;
import java.util.concurrent.ThreadLocalRandom;

public class ChestRestockPlugin extends JavaPlugin implements BasicCommand, Listener {

    private enum ChestTier {
        NORMAL, RARE
    }

    private static class RegisteredChest {
        final Location location;
        final ChestTier tier;
        boolean armedMimic;

        RegisteredChest(Location location, ChestTier tier) {
            this.location = location;
            this.tier = tier;
            this.armedMimic = false;
        }
    }

    private final Map<String, RegisteredChest> registeredChests = new HashMap<>();
    // Maps Rabbit UUID -> BlockDisplay UUID
    private final Map<UUID, UUID> activeMimics = new HashMap<>();

    private NamespacedKey keyMimic;

    @Override
    public void onEnable() {
        keyMimic = new NamespacedKey(this, "chest_mimic");
        saveDefaultConfig();
        loadChestsFromConfig();

        registerCommand("chest", "Register and restock WaveDungeon chests", this);
        getServer().getPluginManager().registerEvents(this, this);
        startMimicSyncTask();

        getLogger().info("ChestRestock enabled with " + registeredChests.size() + " registered chests!");
    }

    @Override
    public void onDisable() {
        saveChestsToConfig();
        // Clean up active mimics
        for (Map.Entry<UUID, UUID> entry : activeMimics.entrySet()) {
            Entity brain = Bukkit.getEntity(entry.getKey());
            Entity display = Bukkit.getEntity(entry.getValue());
            if (brain != null) brain.remove();
            if (display != null) display.remove();
        }
        activeMimics.clear();
    }

    @Override
    public void execute(CommandSourceStack stack, String[] args) {
        CommandSender sender = stack.getSender();

        if (args.length == 0) {
            sender.sendMessage(ChatColor.GOLD + "ChestRestock Commands:");
            sender.sendMessage(ChatColor.YELLOW + "/chest register <normal|rare>" + ChatColor.GRAY + " - Register the chest you are looking at");
            sender.sendMessage(ChatColor.YELLOW + "/chest restock" + ChatColor.GRAY + " - Restock all registered chests (10% Mimic chance)");
            return;
        }

        String sub = args[0].toLowerCase(Locale.ROOT);

        switch (sub) {
            case "register" -> {
                if (!(sender instanceof Player player)) {
                    sender.sendMessage(ChatColor.RED + "Only players can register chests.");
                    return;
                }
                if (args.length < 2) {
                    player.sendMessage(ChatColor.RED + "Usage: /chest register <normal|rare>");
                    return;
                }
                ChestTier tier;
                try {
                    tier = ChestTier.valueOf(args[1].toUpperCase(Locale.ROOT));
                } catch (IllegalArgumentException e) {
                    player.sendMessage(ChatColor.RED + "Tier must be 'normal' or 'rare'.");
                    return;
                }

                Block target = player.getTargetBlockExact(10);
                if (target == null || (target.getType() != Material.CHEST && target.getType() != Material.TRAPPED_CHEST)) {
                    player.sendMessage(ChatColor.RED + "Look directly at a Chest within 10 blocks.");
                    return;
                }

                Location loc = target.getLocation();
                String key = locToKey(loc);
                registeredChests.put(key, new RegisteredChest(loc, tier));
                saveChestsToConfig();

                ChatColor color = (tier == ChestTier.RARE) ? ChatColor.LIGHT_PURPLE : ChatColor.GREEN;
                player.sendMessage(color + "Registered " + tier.name() + " chest! (Total registered: " + registeredChests.size() + ")");
            }

            case "restock" -> {
                if (registeredChests.isEmpty()) {
                    sender.sendMessage(ChatColor.RED + "No chests registered! Look at a chest and run /chest register <normal|rare>.");
                    return;
                }
                int restockedCount = 0;
                int mimicCount = 0;

                for (RegisteredChest rc : registeredChests.values()) {
                    Block b = rc.location.getBlock();
                    if (b.getType() != Material.CHEST && b.getType() != Material.TRAPPED_CHEST) {
                        b.setType(Material.CHEST);
                    }

                    // 10% chance to arm as a Mimic
                    if (ThreadLocalRandom.current().nextDouble() < 0.10) {
                        rc.armedMimic = true;
                        mimicCount++;
                        if (b.getState() instanceof Chest chestState) {
                            chestState.getBlockInventory().clear();
                        }
                    } else {
                        rc.armedMimic = false;
                        fillChestWithLoot(b, rc.tier);
                    }
                    restockedCount++;
                }

                Bukkit.broadcastMessage(ChatColor.GOLD + "[ChestRestock] Restocked " + restockedCount + " dungeon chests!");
                if (mimicCount > 0) {
                    sender.sendMessage(ChatColor.DARK_RED + "(Psst: " + mimicCount + " chest(s) turned into Mimics!)");
                }
            }

            default -> sender.sendMessage(ChatColor.RED + "Unknown subcommand. Use /chest register <normal|rare> or /chest restock.");
        }
    }

    @Override
    public Collection<String> suggest(CommandSourceStack stack, String[] args) {
        if (args.length <= 1) {
            return Arrays.asList("register", "restock");
        }
        if (args.length == 2 && args[0].equalsIgnoreCase("register")) {
            return Arrays.asList("normal", "rare");
        }
        return Collections.emptyList();
    }

    private void fillChestWithLoot(Block block, ChestTier tier) {
        if (!(block.getState() instanceof Chest chest)) return;
        Inventory inv = chest.getBlockInventory();
        inv.clear();

        List<ItemStack> pool = (tier == ChestTier.RARE) ? getBossLootPool() : getTraderTradeLootPool();

        // Pick 2 random items and place them in random slots
        for (int i = 0; i < 2; i++) {
            ItemStack item = pool.get(ThreadLocalRandom.current().nextInt(pool.size())).clone();
            int slot = ThreadLocalRandom.current().nextInt(inv.getSize());
            inv.setItem(slot, item);
        }
    }

    // Items needed to trade with the WaveDungeon Quartermaster trader
    private List<ItemStack> getTraderTradeLootPool() {
        return Arrays.asList(
                new ItemStack(Material.EMERALD, ThreadLocalRandom.current().nextInt(2, 5)),
                new ItemStack(Material.COBBLESTONE, ThreadLocalRandom.current().nextInt(12, 24)),
                new ItemStack(Material.WHEAT, ThreadLocalRandom.current().nextInt(6, 12)),
                new ItemStack(Material.OAK_PLANKS, ThreadLocalRandom.current().nextInt(12, 20)),
                new ItemStack(Material.IRON_NUGGET, ThreadLocalRandom.current().nextInt(4, 9))
        );
    }

    // WaveDungeon Boss loot pool for Rare chests
    private List<ItemStack> getBossLootPool() {
        return Arrays.asList(
                new ItemStack(Material.IRON_INGOT, ThreadLocalRandom.current().nextInt(1, 4)),
                new ItemStack(Material.DIAMOND, 1),
                new ItemStack(Material.GOLDEN_APPLE, 1),
                new ItemStack(Material.COOKED_BEEF, ThreadLocalRandom.current().nextInt(4, 9)),
                new ItemStack(Material.EXPERIENCE_BOTTLE, ThreadLocalRandom.current().nextInt(4, 10))
        );
    }

    @EventHandler
    public void onChestInteract(PlayerInteractEvent event) {
        if (event.getAction() != Action.RIGHT_CLICK_BLOCK && event.getAction() != Action.LEFT_CLICK_BLOCK) return;
        Block clicked = event.getClickedBlock();
        if (clicked == null) return;

        String key = locToKey(clicked.getLocation());
        RegisteredChest rc = registeredChests.get(key);
        if (rc == null || !rc.armedMimic) return;

        // Trigger the Mimic ambush!
        event.setCancelled(true);
        rc.armedMimic = false;

        BlockData chestData = clicked.getBlockData().clone();
        clicked.setType(Material.AIR);

        spawnMimic(clicked.getLocation().add(0.5, 0.0, 0.5), chestData, event.getPlayer());
    }

    private void spawnMimic(Location loc, BlockData chestData, Player target) {
        World w = loc.getWorld();
        if (w == null) return;

        // Killer Rabbit AI brain (chases players, hops aggressively, never goes through walls)
        Rabbit brain = (Rabbit) w.spawnEntity(loc, EntityType.RABBIT);
        brain.setRabbitType(Rabbit.Type.THE_KILLER_BUNNY);
        brain.setInvisible(true);
        brain.setSilent(true);
        brain.setCustomName(ChatColor.DARK_RED + "" + ChatColor.BOLD + "Chest Mimic");
        brain.setCustomNameVisible(true);
        brain.getPersistentDataContainer().set(keyMimic, PersistentDataType.BYTE, (byte) 1);

        // 20 hearts = 40.0 HP, 5.0 damage per attack
        AttributeInstance maxHp = brain.getAttribute(Attribute.MAX_HEALTH);
        if (maxHp != null) maxHp.setBaseValue(40.0);
        brain.setHealth(40.0);

        AttributeInstance atk = brain.getAttribute(Attribute.ATTACK_DAMAGE);
        if (atk != null) atk.setBaseValue(5.0);

        if (target != null && target.getGameMode() == GameMode.SURVIVAL) {
            brain.setTarget(target);
        }

        // Visual Chest BlockDisplay centered on the rabbit's hitbox
        BlockDisplay display = (BlockDisplay) w.spawnEntity(loc, EntityType.BLOCK_DISPLAY);
        display.setBlock(chestData);
        display.setTransformation(new Transformation(
                new Vector3f(-0.5f, 0.0f, -0.5f),
                new AxisAngle4f(0, 0, 0, 1),
                new Vector3f(1.0f, 1.0f, 1.0f),
                new AxisAngle4f(0, 0, 0, 1)
        ));
        display.setTeleportDuration(1);

        activeMimics.put(brain.getUniqueId(), display.getUniqueId());

        w.playSound(loc, Sound.ENTITY_ZOMBIE_BREAK_WOODEN_DOOR, 1.2f, 0.6f);
        w.spawnParticle(Particle.CRIT, loc.clone().add(0, 0.5, 0), 25, 0.4, 0.4, 0.4, 0.2);
        if (target != null) {
            target.sendMessage(ChatColor.DARK_RED + "" + ChatColor.BOLD + "IT'S A MIMIC!");
        }
    }

    // Keep the moving chest BlockDisplay locked onto the Killer Rabbit brain every tick
    private void startMimicSyncTask() {
        new BukkitRunnable() {
            @Override
            public void run() {
                if (activeMimics.isEmpty()) return;

                Iterator> it = activeMimics.entrySet().iterator();
                while (it.hasNext()) {
                    Map.Entry<UUID, UUID> entry = it.next();
                    Entity brain = Bukkit.getEntity(entry.getKey());
                    Entity display = Bukkit.getEntity(entry.getValue());

                    if (brain == null || !brain.isValid() || brain.isDead()) {
                        if (display != null && display.isValid()) display.remove();
                        it.remove();
                        continue;
                    }

                    if (display != null && display.isValid()) {
                        Location syncLoc = brain.getLocation().clone();
                        syncLoc.setPitch(0f);
                        display.teleport(syncLoc);
                    }
                }
            }
        }.runTaskTimer(this, 1L, 1L);
    }

    @EventHandler
    public void onMimicDamage(EntityDamageByEntityEvent event) {
        // Ensure exact 5.0 damage per Mimic attack regardless of difficulty scaling
        if (event.getDamager() instanceof Rabbit rabbit
                && rabbit.getPersistentDataContainer().has(keyMimic, PersistentDataType.BYTE)) {
            event.setDamage(5.0);
            rabbit.getWorld().playSound(rabbit.getLocation(), Sound.BLOCK_CHEST_CLOSE, 1.2f, 0.5f);
        }
    }

    @EventHandler
    public void onMimicDeath(EntityDeathEvent event) {
        if (!(event.getEntity() instanceof Rabbit rabbit)) return;
        if (!rabbit.getPersistentDataContainer().has(keyMimic, PersistentDataType.BYTE)) return;

        event.getDrops().clear();
        // Drop rare boss loot when a Mimic is defeated!
        List<ItemStack> bossPool = getBossLootPool();
        event.getDrops().add(bossPool.get(ThreadLocalRandom.current().nextInt(bossPool.size())).clone());
        event.getDrops().add(new ItemStack(Material.EMERALD, 3));

        UUID displayId = activeMimics.remove(rabbit.getUniqueId());
        if (displayId != null) {
            Entity display = Bukkit.getEntity(displayId);
            if (display != null && display.isValid()) display.remove();
        }
        rabbit.getWorld().playSound(rabbit.getLocation(), Sound.ENTITY_ITEM_BREAK, 1.2f, 0.7f);
    }

    // --- Persistence Helpers ---

    private void saveChestsToConfig() {
        getConfig().set("chests", null);
        for (Map.Entry<String, RegisteredChest> entry : registeredChests.entrySet()) {
            String path = "chests." + entry.getKey();
            RegisteredChest rc = entry.getValue();
            getConfig().set(path + ".world", rc.location.getWorld().getName());
            getConfig().set(path + ".x", rc.location.getBlockX());
            getConfig().set(path + ".y", rc.location.getBlockY());
            getConfig().set(path + ".z", rc.location.getBlockZ());
            getConfig().set(path + ".tier", rc.tier.name());
        }
        saveConfig();
    }

    private void loadChestsFromConfig() {
        registeredChests.clear();
        ConfigurationSection sec = getConfig().getConfigurationSection("chests");
        if (sec == null) return;

        for (String key : sec.getKeys(false)) {
            String worldName = sec.getString(key + ".world");
            World w = (worldName != null) ? Bukkit.getWorld(worldName) : null;
            if (w == null) continue;

            int x = sec.getInt(key + ".x");
            int y = sec.getInt(key + ".y");
            int z = sec.getInt(key + ".z");
            ChestTier tier = ChestTier.valueOf(sec.getString(key + ".tier", "NORMAL"));

            Location loc = new Location(w, x, y, z);
            registeredChests.put(locToKey(loc), new RegisteredChest(loc, tier));
        }
    }

    private String locToKey(Location loc) {
        return loc.getWorld().getName() + "_" + loc.getBlockX() + "_" + loc.getBlockY() + "_" + loc.getBlockZ();
    }
}
