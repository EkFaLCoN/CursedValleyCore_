package net.cursedvalley.core;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.Location;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Item;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.ItemDespawnEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

/**
 * Cursed Valley
 *  - Bowl (merkez boss alani) icinde olunce esyalar duser.
 *  - Bowl disinda, AMA AYNI DUNYADA, envanter korunur.
 *  - Baska dunyalarda plugin hicbir seye karismaz.
 *  - Yere dusen esyalar erken silinmez.
 */
public final class CursedValleyCore extends JavaPlugin implements Listener {

    private static final String VERSION = "2.0.0";

    private String worldName;
    private double centerX, centerZ, radiusSq, radius, minY, maxY;
    private boolean dropExpInBowl, keepExpOutside, warnOnEnter;
    private boolean protectDrops;
    private int lifetimeMinutes;
    private String msgDropped, msgKept, msgEnter, msgExit;

    private final Set<UUID> inside = new HashSet<>();
    private NamespacedKey cycleKey;

    @Override
    public void onEnable() {
        cycleKey = new NamespacedKey(this, "despawn_cycles");
        saveDefaultConfig();
        load();
        getServer().getPluginManager().registerEvents(this, this);
        getLogger().info("Aktif dunya: " + worldName + " | bowl (" + (int) centerX + ", "
                + (int) centerZ + ") r=" + (int) radius);
        if (getServer().getWorld(worldName) == null) {
            getLogger().warning("'" + worldName + "' dunyasi henuz yuklu degil. "
                    + "Multiverse ile olusturduktan sonra /cv reload calistir.");
        }
    }

    private void load() {
        reloadConfig();
        worldName       = getConfig().getString("world", "cursedvalley");
        centerX         = getConfig().getDouble("bowl.center-x", 0);
        centerZ         = getConfig().getDouble("bowl.center-z", 0);
        radius          = getConfig().getDouble("bowl.radius", 70);
        radiusSq        = radius * radius;
        minY            = getConfig().getDouble("bowl.min-y", -64);
        maxY            = getConfig().getDouble("bowl.max-y", 320);
        dropExpInBowl   = getConfig().getBoolean("drop-experience-in-bowl", true);
        keepExpOutside  = getConfig().getBoolean("keep-experience-outside", true);
        warnOnEnter     = getConfig().getBoolean("warn-on-enter", true);
        protectDrops    = getConfig().getBoolean("drops.protect", true);
        lifetimeMinutes = getConfig().getInt("drops.lifetime-minutes", 15);
        msgDropped      = getConfig().getString("message-dropped", "");
        msgKept         = getConfig().getString("message-kept", "");
        msgEnter        = getConfig().getString("message-enter", "");
        msgExit         = getConfig().getString("message-exit", "");
        inside.clear();
    }

    // ---------------------------------------------------------------- DUNYA KONTROLU
    /** Plugin SADECE bu dunyada is yapar. Diger dunyalarda hicbir olaya karismaz. */
    private boolean inWorld(World world) {
        return world != null && world.getName().equalsIgnoreCase(worldName);
    }

    private boolean inBowl(Location loc) {
        if (loc == null || !inWorld(loc.getWorld())) return false;
        if (loc.getY() < minY || loc.getY() > maxY) return false;
        double dx = loc.getX() - centerX, dz = loc.getZ() - centerZ;
        return dx * dx + dz * dz <= radiusSq;
    }

    // ---------------------------------------------------------------- OLUM
    @EventHandler(priority = EventPriority.HIGHEST)
    public void onPlayerDeath(PlayerDeathEvent event) {
        Player victim = event.getEntity();

        // BASKA DUNYA -> hic dokunma. Vanilla/diger pluginler ne diyorsa o.
        if (!inWorld(victim.getWorld())) return;

        if (inBowl(victim.getLocation())) {
            event.setKeepInventory(false);
            event.setKeepLevel(!dropExpInBowl);
            send(victim, msgDropped);
        } else {
            event.setKeepInventory(true);
            event.getDrops().clear();
            if (keepExpOutside) {
                event.setKeepLevel(true);
                event.setDroppedExp(0);
            }
            send(victim, msgKept);
        }
        inside.remove(victim.getUniqueId());
    }

    // ---------------------------------------------------------------- DROP KORUMASI
    /**
     * Vanilla item entity'leri 5 dakikada (6000 tick) yok olur.
     * Her yok olma denemesinde olayi iptal edip sayaci sifirliyoruz,
     * boylece esya lifetime-minutes suresi boyunca yerde kaliyor.
     */
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onItemDespawn(ItemDespawnEvent event) {
        if (!protectDrops) return;
        Item item = event.getEntity();
        if (!inWorld(item.getWorld())) return;

        if (lifetimeMinutes <= 0) {          // 0 = hic silinmesin
            event.setCancelled(true);
            item.setTicksLived(1);
            return;
        }

        int allowed = Math.max(1, lifetimeMinutes / 5);   // her dongu 5 dakika
        PersistentDataContainer pdc = item.getPersistentDataContainer();
        int done = pdc.getOrDefault(cycleKey, PersistentDataType.INTEGER, 0) + 1;
        if (done >= allowed) return;                      // suresi doldu, dussun

        pdc.set(cycleKey, PersistentDataType.INTEGER, done);
        event.setCancelled(true);
        item.setTicksLived(1);
    }

    // ---------------------------------------------------------------- BOLGE UYARISI
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onMove(PlayerMoveEvent event) {
        if (!warnOnEnter) return;
        if (!event.hasChangedBlock()) return;
        Player player = event.getPlayer();
        if (!inWorld(player.getWorld())) {
            inside.remove(player.getUniqueId());
            return;
        }
        UUID id = player.getUniqueId();
        boolean now = inBowl(event.getTo()), was = inside.contains(id);
        if (now == was) return;
        if (now) { inside.add(id); send(player, msgEnter); }
        else     { inside.remove(id); send(player, msgExit); }
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) { inside.remove(event.getPlayer().getUniqueId()); }

    private void send(Player p, String legacy) {
        if (legacy == null || legacy.isEmpty()) return;
        p.sendMessage(LegacyComponentSerializer.legacyAmpersand().deserialize(legacy));
    }

    // ---------------------------------------------------------------- KOMUT
    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (args.length > 0 && args[0].equalsIgnoreCase("reload")) {
            load();
            sender.sendMessage(Component.text("Ayarlar yeniden yuklendi.", NamedTextColor.GREEN));
            return true;
        }
        if (args.length > 0 && args[0].equalsIgnoreCase("setworld")) {
            if (!(sender instanceof Player p)) {
                sender.sendMessage(Component.text("Oyun ici komut.", NamedTextColor.RED)); return true;
            }
            getConfig().set("world", p.getWorld().getName());
            saveConfig(); load();
            sender.sendMessage(Component.text("Aktif dunya: " + worldName, NamedTextColor.GREEN));
            return true;
        }
        if (args.length > 0 && args[0].equalsIgnoreCase("setbowl")) {
            if (!(sender instanceof Player p)) {
                sender.sendMessage(Component.text("Oyun ici komut.", NamedTextColor.RED)); return true;
            }
            double r = radius;
            if (args.length > 1) {
                try { r = Double.parseDouble(args[1]); }
                catch (NumberFormatException ex) {
                    sender.sendMessage(Component.text("Gecersiz yaricap.", NamedTextColor.RED)); return true;
                }
            }
            Location l = p.getLocation();
            getConfig().set("world", l.getWorld().getName());
            getConfig().set("bowl.center-x", Math.floor(l.getX()));
            getConfig().set("bowl.center-z", Math.floor(l.getZ()));
            getConfig().set("bowl.radius", r);
            saveConfig(); load();
            sender.sendMessage(Component.text("Bowl: " + worldName + " (" + (int) centerX + ", "
                    + (int) centerZ + ") r=" + (int) radius, NamedTextColor.GREEN));
            return true;
        }
        World w = getServer().getWorld(worldName);
        sender.sendMessage(Component.text("CursedValleyCore " + VERSION, NamedTextColor.GOLD));
        sender.sendMessage(Component.text("  dunya: " + worldName
                + (w == null ? " (YUKLU DEGIL!)" : " (yuklu)"), NamedTextColor.GRAY));
        sender.sendMessage(Component.text("  bowl: (" + (int) centerX + ", " + (int) centerZ
                + ") r=" + (int) radius, NamedTextColor.GRAY));
        sender.sendMessage(Component.text("  drop koruma: " + protectDrops
                + " / " + (lifetimeMinutes <= 0 ? "sinirsiz" : lifetimeMinutes + " dk"), NamedTextColor.GRAY));
        sender.sendMessage(Component.text("  /cv reload | /cv setworld | /cv setbowl <r>", NamedTextColor.DARK_GRAY));
        return true;
    }
}
