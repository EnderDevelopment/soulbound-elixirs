package com.enderdevelopment.plugin;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerItemHeldEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.util.Vector;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;

public final class Main extends JavaPlugin implements Listener {
    private enum Soul {
        CINDER("Cinder", NamedTextColor.RED, "Furnace Pulse", "Heatproof", "Blast nearby foes with a ring of fire.", 8000),
        TIDEBORN("Tideborn", NamedTextColor.AQUA, "Undertow", "Deep Lungs", "Drag nearby foes toward your gaze and slow them.", 10000),
        GALE("Gale", NamedTextColor.WHITE, "Updraft", "Lightfoot", "Launch yourself upward and gain a brief burst of speed.", 7000),
        ROOTBOUND("Rootbound", NamedTextColor.GREEN, "Bramble Guard", "Stone Stance", "Gain a temporary protective ward.", 12000),
        ECHO("Echo", NamedTextColor.LIGHT_PURPLE, "Resonant Step", "Keen Hearing", "Blink a short distance forward through open space.", 9000);
        private final String display, ability, passive, description;
        private final NamedTextColor color;
        private final long cooldown;
        Soul(String display, NamedTextColor color, String ability, String passive, String description, long cooldown) {
            this.display = display; this.color = color; this.ability = ability; this.passive = passive; this.description = description; this.cooldown = cooldown;
        }
        private static Soul fromName(String text) {
            String name = text.replace("_", "").replace("-", "").toLowerCase(Locale.ROOT);
            for (Soul soul : values()) if (soul.display.toLowerCase(Locale.ROOT).equals(name) || soul.name().toLowerCase(Locale.ROOT).equals(name)) return soul;
            return null;
        }
    }

    private final Map<UUID, Long> cooldowns = new ConcurrentHashMap<>();
    private final Map<UUID, PotionEffect> passives = new ConcurrentHashMap<>();
    private NamespacedKey soulKey;

    @Override public void onEnable() {
        soulKey = new NamespacedKey(this, "soul_bottle");
        Bukkit.getPluginManager().registerEvents(this, this);
        Bukkit.getScheduler().runTaskTimer(this, this::refreshPassives, 20L, 20L);
        getLogger().info("SoulBottles enabled with five original souls.");
    }
    @Override public void onDisable() { for (Player player : Bukkit.getOnlinePlayers()) clearPassive(player); cooldowns.clear(); }

    @Override public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (args.length == 1 && args[0].equalsIgnoreCase("list")) {
            sender.sendMessage(Component.text("Available souls:", NamedTextColor.GOLD));
            for (Soul soul : Soul.values()) sender.sendMessage(Component.text("• " + soul.display + " — " + soul.ability + " / " + soul.passive, soul.color));
            return true;
        }
        if (args.length == 3 && args[0].equalsIgnoreCase("give")) {
            Player target = Bukkit.getPlayerExact(args[1]);
            Soul soul = Soul.fromName(args[2]);
            if (target == null) { sender.sendMessage(Component.text("That player is not online.", NamedTextColor.RED)); return true; }
            if (soul == null) { sender.sendMessage(Component.text("Unknown soul. Use /soul list.", NamedTextColor.RED)); return true; }
            target.getInventory().addItem(createBottle(soul)).values().forEach(item -> target.getWorld().dropItemNaturally(target.getLocation(), item));
            sender.sendMessage(Component.text("Gave " + soul.display + " Soul Bottle to " + target.getName() + ".", NamedTextColor.GREEN));
            if (!sender.equals(target)) target.sendMessage(Component.text("You received a " + soul.display + " Soul Bottle! Right-click it to use the ability.", soul.color));
            return true;
        }
        sender.sendMessage(Component.text("Usage: /soul give <player> <soul> or /soul list", NamedTextColor.YELLOW));
        return true;
    }

    private ItemStack createBottle(Soul soul) {
        ItemStack item = new ItemStack(Material.POTION);
        ItemMeta meta = item.getItemMeta();
        if (meta == null) return item;
        meta.displayName(Component.text(soul.display + " Soul Bottle", soul.color));
        List<Component> lore = new ArrayList<>();
        lore.add(Component.text("Ability: " + soul.ability, NamedTextColor.GOLD));
        lore.add(Component.text("Passive: " + soul.passive, NamedTextColor.GREEN));
        lore.add(Component.text(soul.description, NamedTextColor.GRAY));
        lore.add(Component.text("Right-click to unleash", NamedTextColor.DARK_GRAY));
        meta.lore(lore);
        meta.getPersistentDataContainer().set(soulKey, PersistentDataType.STRING, soul.name());
        item.setItemMeta(meta);
        return item;
    }

    @EventHandler public void onInteract(PlayerInteractEvent event) {
        if (event.getAction() != Action.RIGHT_CLICK_AIR && event.getAction() != Action.RIGHT_CLICK_BLOCK) return;
        Soul soul = getSoul(event.getItem());
        if (soul == null) return;
        event.setCancelled(true);
        Player player = event.getPlayer();
        long remaining = cooldowns.getOrDefault(player.getUniqueId(), 0L) - System.currentTimeMillis();
        if (remaining > 0) { player.sendMessage(Component.text("That soul is recharging for " + ((remaining + 999) / 1000) + "s.", NamedTextColor.GRAY)); return; }
        cooldowns.put(player.getUniqueId(), System.currentTimeMillis() + soul.cooldown);
        activate(player, soul);
    }

    private void activate(Player player, Soul soul) {
        Location location = player.getLocation();
        switch (soul) {
            case CINDER -> { player.getWorld().spawnParticle(Particle.FLAME, location, 65, 3, .8, 3, .04); player.getWorld().playSound(location, Sound.ITEM_FIRECHARGE_USE, 1, .8f); for (LivingEntity target : nearby(player, 4)) { target.damage(5, player); target.setFireTicks(60); } }
            case TIDEBORN -> { player.getWorld().spawnParticle(Particle.SPLASH, location, 55, 3.5, 1, 3.5, .15); for (LivingEntity target : nearby(player, 8)) { Vector pull = player.getLocation().toVector().subtract(target.getLocation().toVector()); if (pull.lengthSquared() > .1) target.setVelocity(pull.normalize().multiply(.8).setY(.18)); target.addPotionEffect(new PotionEffect(PotionEffectType.SLOWNESS, 60, 1)); } }
            case GALE -> { player.setVelocity(player.getLocation().getDirection().multiply(.45).setY(1.05)); player.addPotionEffect(new PotionEffect(PotionEffectType.SPEED, 100, 1)); player.getWorld().spawnParticle(Particle.CLOUD, location, 35, .8, .3, .8, .12); }
            case ROOTBOUND -> { player.addPotionEffect(new PotionEffect(PotionEffectType.RESISTANCE, 100, 1)); player.addPotionEffect(new PotionEffect(PotionEffectType.ABSORPTION, 100, 1)); player.getWorld().spawnParticle(Particle.BLOCK, location, 60, 1.4, 1, 1.4, Material.MOSS_BLOCK.createBlockData()); }
            case ECHO -> { Vector direction = location.getDirection().normalize(); Location safe = null; for (double distance = 1; distance <= 7; distance += .5) { Location candidate = location.clone().add(direction.clone().multiply(distance)); if (candidate.getBlock().isPassable() && candidate.clone().add(0, 1, 0).getBlock().isPassable() && candidate.clone().subtract(0, 1, 0).getBlock().getType().isSolid()) safe = candidate; } if (safe != null) { player.getWorld().spawnParticle(Particle.REVERSE_PORTAL, location, 25, .4, .8, .4, .1); player.teleport(safe.setDirection(direction)); player.getWorld().spawnParticle(Particle.REVERSE_PORTAL, safe, 25, .4, .8, .4, .1); } else player.sendMessage(Component.text("No clear path to resonate through.", NamedTextColor.GRAY)); }
        }
        player.sendMessage(Component.text(soul.ability + " unleashed!", soul.color));
    }

    private List<LivingEntity> nearby(Player player, double radius) { return player.getNearbyEntities(radius, radius, radius).stream().filter(entity -> entity instanceof LivingEntity).map(entity -> (LivingEntity) entity).toList(); }
    private Soul getSoul(ItemStack item) { if (item == null || item.getType() != Material.POTION || !item.hasItemMeta()) return null; String name = item.getItemMeta().getPersistentDataContainer().get(soulKey, PersistentDataType.STRING); if (name == null) return null; try { return Soul.valueOf(name); } catch (IllegalArgumentException exception) { return null; } }
    private void refreshPassives() { for (Player player : Bukkit.getOnlinePlayers()) { Soul soul = getSoul(player.getInventory().getItemInMainHand()); if (soul == null) soul = getSoul(player.getInventory().getItemInOffHand()); PotionEffect old = passives.remove(player.getUniqueId()); if (old != null) player.removePotionEffect(old.getType()); if (soul == null) continue; PotionEffect effect = switch (soul) { case CINDER -> new PotionEffect(PotionEffectType.FIRE_RESISTANCE, 60, 0, true, false, true); case TIDEBORN -> new PotionEffect(PotionEffectType.WATER_BREATHING, 60, 0, true, false, true); case GALE -> new PotionEffect(PotionEffectType.SPEED, 60, 0, true, false, true); case ROOTBOUND -> new PotionEffect(PotionEffectType.RESISTANCE, 60, 0, true, false, true); case ECHO -> new PotionEffect(PotionEffectType.NIGHT_VISION, 260, 0, true, false, true); }; player.addPotionEffect(effect); passives.put(player.getUniqueId(), effect); } }
    private void clearPassive(Player player) { PotionEffect effect = passives.remove(player.getUniqueId()); if (effect != null) player.removePotionEffect(effect.getType()); }
    @EventHandler public void onHeldItemChange(PlayerItemHeldEvent event) { Bukkit.getScheduler().runTask(this, this::refreshPassives); }
    @EventHandler public void onQuit(PlayerQuitEvent event) { clearPassive(event.getPlayer()); cooldowns.remove(event.getPlayer().getUniqueId()); }
}
