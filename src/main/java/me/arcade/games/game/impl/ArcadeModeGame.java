package me.arcade.games.game.impl;

import me.arcade.games.ArcadePlugin;
import me.arcade.games.arena.Arena;
import me.arcade.games.game.Game;
import me.arcade.games.util.Msg;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.block.data.BlockData;
import org.bukkit.entity.Player;
import org.bukkit.entity.Item;
import org.bukkit.entity.ItemDisplay;
import org.bukkit.util.Transformation;
import org.joml.Vector3f;
import org.joml.AxisAngle4f;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.event.entity.EntityPickupItemEvent;
import org.bukkit.entity.Projectile;
import org.bukkit.entity.Snowball;
import org.bukkit.entity.Egg;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.util.Vector;
import java.util.*;

/** Shared implementation for the additional arcade modes. */
public final class ArcadeModeGame extends Game {
    private final Map<Block, BlockData> changed = new LinkedHashMap<>();
    private final Set<UUID> tagged = new HashSet<>();
    private final Map<UUID, Long> tagTimes = new HashMap<>();
    private final Map<UUID, ItemStack> oldHelmets = new HashMap<>();
    private final Random random = new Random();
    private final Map<UUID, Location> checkpoints = new HashMap<>();
    private final Map<UUID, Integer> coins = new HashMap<>();
    private int secondsLeft = 180;
    private final List<UUID> templeFinishOrder = new ArrayList<>();
    private final Set<UUID> completedRunners = new HashSet<>();

    public ArcadeModeGame(ArcadePlugin plugin, Arena arena) { super(plugin, arena); }

    @Override protected void onStart() {
        int i = 0; secondsLeft = Math.max(30, cfg().getInt("round-seconds", 180));
        for (Player p : allPlayers()) {
            p.teleport(spawnFor(i++)); p.setGameMode(GameMode.SURVIVAL);
            switch (arena.type) {
                case TNT_TAG -> { p.getInventory().clear(); p.getInventory().setHelmet(null); }
                case SPLEGG -> { p.getInventory().addItem(new ItemStack(Material.IRON_SHOVEL)); p.getInventory().addItem(new ItemStack(Material.EGG, 64)); }
                case BOMBLOBBERS -> p.getInventory().addItem(new ItemStack(Material.SNOWBALL, 32));
                case QUAKE -> { p.getInventory().addItem(new ItemStack(Material.BOW)); p.getInventory().addItem(new ItemStack(Material.ARROW, 64)); }
                case TEMPLE_RUN -> { p.addPotionEffect(new PotionEffect(PotionEffectType.SPEED, 20 * secondsLeft, 0, false, false)); checkpoints.put(p.getUniqueId(), p.getLocation().clone()); coins.put(p.getUniqueId(), 0); }
                default -> {}
            }
        }
        if (arena.type == me.arcade.games.arena.GameType.TNT_TAG && !alive.isEmpty()) {
            UUID first = new ArrayList<>(alive).get(random.nextInt(alive.size())); tagged.add(first); tagTimes.put(first, ticks + 20L * cfg().getInt("tag-fuse-seconds", 20)); setTaggedHelmet(first, true);
            Player p = org.bukkit.Bukkit.getPlayer(first); if (p != null) Msg.send(p, "&cYou are IT! Tag another player!");
        }
        if (arena.type == me.arcade.games.arena.GameType.TEMPLE_RUN && cfg().getBoolean("course.coins-enabled", true) && arena.world() != null) {
            for (Location loc : arena.coins.values()) {
                if (loc == null || loc.getWorld() == null) continue;
                Material coinMaterial = Material.matchMaterial(cfg().getString("course.coin-item", "GOLD_INGOT"));
                if (coinMaterial == null || coinMaterial.isAir()) coinMaterial = Material.GOLD_INGOT;
                double height = cfg().getDouble("course.coin-height", 0.35);
                Location displayLoc = loc.clone().add(0, height, 0);
                Material itemMaterial = coinMaterial;
                displayLoc.getWorld().spawn(displayLoc, Item.class, item -> {
                    item.setItemStack(new ItemStack(itemMaterial));
                    item.setPickupDelay(Integer.MAX_VALUE);
                    item.setGravity(false);
                    item.setInvulnerable(true);
                    item.setSilent(true);
                    item.addScoreboardTag("ag_temple_coin_display");
                });
            }
        }
        broadcast("&a" + arena.type.display + " started! Survive and be the last player standing.");
    }

    @Override protected void tick() {
        if (ticks % 20 == 0) {
            secondsLeft--;
            if (secondsLeft <= 0) { finishByScore(); return; }
            if (plugin.getConfig().getBoolean("timer.enabled", true)) for (Player p : alivePlayers()) {
                p.setLevel(0); p.setExp(0f);
                String format = plugin.getConfig().getString("timer.format", "&e{game} &8| &f{seconds}s");
                Msg.bar(p, format.replace("{game}", arena.type.display).replace("{arena}", arena.name).replace("{seconds}", String.valueOf(secondsLeft)) + (arena.type == me.arcade.games.arena.GameType.TEMPLE_RUN ? " &6| Coins: " + coins.getOrDefault(p.getUniqueId(), 0) : ""));
            }
        }
        for (Player p : alivePlayers()) {
            if (arena.type == me.arcade.games.arena.GameType.TEMPLE_RUN) {
                if (p.getLocation().getY() < arena.minY() - 2) {
                    if (cfg().getBoolean("course.return-to-checkpoint-on-fall", true)) {
                        Location cp = checkpoints.getOrDefault(p.getUniqueId(), spawnFor(0));
                        p.teleport(cp); p.setFallDistance(0); p.setHealth(Math.min(20.0, p.getAttribute(org.bukkit.attribute.Attribute.MAX_HEALTH).getValue())); p.setFoodLevel(20);
                        Msg.send(p, "&eYou fell! Returned to your latest checkpoint.");
                    } else eliminate(p);
                    continue;
                }
            } else if (p.getLocation().getY() < arena.minY() - 2) { eliminate(p); continue; }
            if (arena.type == me.arcade.games.arena.GameType.TNT_TAG && tagged.contains(p.getUniqueId()) && ticks >= tagTimes.getOrDefault(p.getUniqueId(), Long.MAX_VALUE)) {
                broadcast("&c" + p.getName() + " exploded!"); eliminate(p);
            }
        }
        if (arena.type == me.arcade.games.arena.GameType.TEMPLE_RUN && ticks % 100 == 0) {
            for (Player p : alivePlayers()) p.addPotionEffect(new PotionEffect(PotionEffectType.SPEED, 120, Math.min(2, (int)(ticks / 1200)), false, false));
        }
    }

    private void setTaggedHelmet(UUID id, boolean enabled) {
        Player p = org.bukkit.Bukkit.getPlayer(id);
        if (p == null) return;
        if (enabled) {
            oldHelmets.putIfAbsent(id, p.getInventory().getHelmet() == null ? null : p.getInventory().getHelmet().clone());
            p.getInventory().setHelmet(new ItemStack(Material.TNT));
        } else {
            p.getInventory().setHelmet(oldHelmets.remove(id));
        }
    }

    /** Collects the half-block display coins configured for Temple Run. */
    public void tryCollectNearbyCoin(Player player) {
        if (arena.type != me.arcade.games.arena.GameType.TEMPLE_RUN || !alive.contains(player.getUniqueId()) || !cfg().getBoolean("course.coins-enabled", true)) return;
        for (org.bukkit.entity.Entity entity : player.getNearbyEntities(0.8, 0.8, 0.8)) {
            if (entity instanceof Item && entity.getScoreboardTags().contains("ag_temple_coin_display")) {
                entity.remove();
                int value = Math.max(1, cfg().getInt("course.coin-value", 1));
                coins.merge(player.getUniqueId(), value, Integer::sum);
                Msg.bar(player, "&6+" + value + " coin &8| &fTotal: &e" + coins.get(player.getUniqueId()));
            }
        }
    }

    /** Called by the central pickup listener for older tagged Temple Run item entities. */
    public boolean onCoinPickup(Player player, Item item) {
        if (arena.type != me.arcade.games.arena.GameType.TEMPLE_RUN || !item.getScoreboardTags().contains("ag_temple_coin") || !alive.contains(player.getUniqueId())) return false;
        item.remove();
        int value = Math.max(1, cfg().getInt("course.coin-value", 1));
        coins.merge(player.getUniqueId(), value, Integer::sum);
        Msg.bar(player, "&6+" + value + " coin &8| &fTotal: &e" + coins.get(player.getUniqueId()));
        return true;
    }

    @Override public void onDamage(Player victim, EntityDamageEvent e) {
        if (arena.type == me.arcade.games.arena.GameType.TNT_TAG && e instanceof EntityDamageByEntityEvent by) {
            Player attacker = by.getDamager() instanceof Player p ? p : (cfg().getBoolean("tagging.allow-projectile-tagging", false) && by.getDamager() instanceof Projectile pr && pr.getShooter() instanceof Player shooter ? shooter : null);
            boolean bareHands = !cfg().getBoolean("tagging.bare-hands-only", true) || attacker != null && attacker.getInventory().getItemInMainHand().getType().isAir();
            boolean directHit = by.getDamager() instanceof Player || cfg().getBoolean("tagging.allow-projectile-tagging", false);
            if (attacker != null && bareHands && directHit && alive.contains(attacker.getUniqueId()) && alive.contains(victim.getUniqueId()) && tagged.contains(attacker.getUniqueId()) && !tagged.contains(victim.getUniqueId())) {
                tagged.remove(attacker.getUniqueId()); tagTimes.remove(attacker.getUniqueId()); setTaggedHelmet(attacker.getUniqueId(), false);
                tagged.add(victim.getUniqueId()); tagTimes.put(victim.getUniqueId(), ticks + 20L * cfg().getInt("tag-fuse-seconds", 20)); setTaggedHelmet(victim.getUniqueId(), true);
                Msg.send(victim, "&cYou are IT! Run!"); Msg.send(attacker, "&aYou passed the tag!");
            }
            e.setCancelled(true); return;
        }
        if (arena.type == me.arcade.games.arena.GameType.QUAKE && e instanceof EntityDamageByEntityEvent by) {
            Player attacker = by.getDamager() instanceof Player p ? p : (by.getDamager() instanceof Projectile pr && pr.getShooter() instanceof Player p ? p : null);
            if (attacker != null && attacker != victim && alive.contains(attacker.getUniqueId())) {
                e.setCancelled(true); broadcast("&e" + victim.getName() + " was blasted by " + attacker.getName() + "!"); eliminate(victim); return;
            }
        }
        e.setCancelled(true);
    }

    @Override public void onInteract(Player p, PlayerInteractEvent e) {
        if (arena.type != me.arcade.games.arena.GameType.SPLEGG || !alive.contains(p.getUniqueId())) return;
        if (e.getItem() == null || e.getItem().getType() != Material.EGG) return;
        e.setCancelled(true);
        Egg egg = p.launchProjectile(Egg.class); egg.setVelocity(p.getLocation().getDirection().multiply(1.6)); egg.addScoreboardTag("ag_splegg");
    }

    @Override public boolean allowBreak(Player p, Block b) {
        if (arena.type != me.arcade.games.arena.GameType.SPLEGG || !alive.contains(p.getUniqueId()) || !arena.contains(b)) return false;
        changed.putIfAbsent(b, b.getBlockData()); b.setType(Material.AIR, false); return true;
    }

    @Override public boolean allowPlace(Player p, Block b) { return false; }

    @Override protected void checkEnd() {
        if (arena.type == me.arcade.games.arena.GameType.TEMPLE_RUN) {
            if (!alive.isEmpty()) return;
            finishTempleResults();
            return;
        }
        if (alive.size() > 1) return;
        String winner = alive.isEmpty() ? "Nobody" : nameOf(alive.iterator().next());
        end("&6" + arena.type.display + " winner: &e" + winner);
    }

    private void finishByScore() {
        if (arena.type == me.arcade.games.arena.GameType.TEMPLE_RUN) { finishTempleResults(); return; }
        String winner = "Nobody";
        if (!alive.isEmpty()) winner = nameOf(alive.iterator().next());
        end("&6" + arena.type.display + " time is up! Winner: &e" + winner);
    }

    /** Handles configured Temple Run checkpoint and finish pressure plates. */
    public void onCourseStep(Player player, Block feetBlock) {
        if (arena.type != me.arcade.games.arena.GameType.TEMPLE_RUN || !alive.contains(player.getUniqueId())) return;
        tryCollectNearbyCoin(player);
        Location here = feetBlock.getLocation();
        if (cfg().getBoolean("course.checkpoints-enabled", true)) for (Map.Entry<Integer, Location> entry : arena.checkpoints.entrySet()) {
            Location marker = entry.getValue();
            if (sameBlock(marker, here) && marker.getBlock().getType() == Material.LIGHT_WEIGHTED_PRESSURE_PLATE) {
                Location cp = marker.clone().add(0.5, 0.15, 0.5);
                Location old = checkpoints.get(player.getUniqueId());
                if (old == null || old.distanceSquared(cp) > 0.5) {
                    checkpoints.put(player.getUniqueId(), cp);
                    Msg.send(player, "&aCheckpoint " + entry.getKey() + " saved!");
                }
                return;
            }
        }
        if (cfg().getBoolean("course.finishes-enabled", true)) for (Location marker : arena.finishes.values()) {
            if (sameBlock(marker, here) && marker.getBlock().getType() == Material.HEAVY_WEIGHTED_PRESSURE_PLATE) {
                completeRunner(player); return;
            }
        }
    }

    private boolean sameBlock(Location a, Location b) {
        return a != null && a.getWorld() != null && a.getWorld().equals(b.getWorld()) && a.getBlockX() == b.getBlockX() && a.getBlockY() == b.getBlockY() && a.getBlockZ() == b.getBlockZ();
    }

    private void completeRunner(Player player) {
        UUID id = player.getUniqueId();
        if (!completedRunners.add(id)) return;
        templeFinishOrder.add(id); alive.remove(id);
        int place = templeFinishOrder.size();
        String label = place == 1 ? "1st" : place == 2 ? "2nd" : place == 3 ? "3rd" : place + "th";
        if (cfg().getBoolean("course.announce-finish-order", true)) broadcast("&6" + player.getName() + " finished Temple Run in " + label + " place!");
        player.getInventory().clear(); player.setGameMode(GameMode.SPECTATOR); player.teleport(arena.spectatorSpawn());
        if (alive.isEmpty()) finishTempleResults();
    }

    private void finishTempleResults() {
        if (state != State.ACTIVE) return;
        List<String> result = new ArrayList<>(); result.add("&6Temple Run results:");
        String[] places = {"&6🥇 1st", "&7🥈 2nd", "&c🥉 3rd"};
        int resultCount = Math.max(1, Math.min(3, cfg().getInt("course.results-count", 3)));
        for (int i = 0; i < Math.min(resultCount, templeFinishOrder.size()); i++) {
            UUID id = templeFinishOrder.get(i); result.add(places[i] + " &f" + nameOf(id));
        }
        if (templeFinishOrder.isEmpty()) result.add("&7Nobody reached the finish.");
        end(result.toArray(new String[0]));
    }

    @Override protected void onEnd() {
        for (Map.Entry<Block, BlockData> entry : changed.entrySet()) entry.getKey().setBlockData(entry.getValue(), false);
        for (UUID id : new HashSet<>(tagged)) setTaggedHelmet(id, false);
        changed.clear(); tagged.clear(); tagTimes.clear(); oldHelmets.clear(); checkpoints.clear(); coins.clear(); templeFinishOrder.clear(); completedRunners.clear();
        if (arena.world() != null) for (org.bukkit.entity.Entity entity : arena.world().getEntities()) if ((entity.getScoreboardTags().contains("ag_temple_coin") || entity.getScoreboardTags().contains("ag_temple_coin_display")) && arena.contains(entity.getLocation().getBlock())) entity.remove();
    }
}
