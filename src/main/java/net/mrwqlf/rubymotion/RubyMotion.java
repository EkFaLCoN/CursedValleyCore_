package net.mrwqlf.rubymotion;

import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.ProxiedCommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.util.Vector;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * RubyMotion — datapack hareketini "tp" yerine sunucu tarafli HIZ (velocity) ile uretir.
 *
 * Neden: tp, hile korumalarinin (Grim, Matrix, NCP, Vulcan...) gozunde gecersiz konum
 * degisimidir; koruma oyuncuyu geri isinlar (rubber-band). Sunucunun kendi gonderdigi
 * velocity paketi ise korumalar tarafindan tahmin edilip kabul edilir — yay, TNT,
 * havai fisek ve knockback tam olarak boyle calisir.
 *
 * Kullanim (datapack fonksiyonundan, oyuncu olarak calistirilir):
 *   execute as @s at @s run rubyvel push <ivme> <tavan>
 *   execute as @s at @s run rubyvel dash <guc> <yukari>
 *   execute as @s at @s run rubyvel stop
 */
public final class RubyMotion extends JavaPlugin implements CommandExecutor, TabCompleter {

    // Guvenlik tavanlari — komut yanlislikla/kotu niyetle cagirilsa bile bunlari asamaz.
    private static final double MAX_ACCEL = 0.25;   // blok / tick
    private static final double MAX_CAP   = 3.00;   // blok / tick
    private static final double MAX_POWER = 2.00;   // blok / tick
    private static final double MAX_UP    = 1.00;   // blok / tick

    @Override
    public void onEnable() {
        var cmd = getCommand("rubyvel");
        if (cmd == null) {
            getLogger().severe("rubyvel komutu plugin.yml'de bulunamadi, eklenti kapaniyor.");
            getServer().getPluginManager().disablePlugin(this);
            return;
        }
        cmd.setExecutor(this);
        cmd.setTabCompleter(this);
        getLogger().info("RubyMotion etkin — hareket velocity ile uretiliyor, tp kullanilmiyor.");
    }

    /** Komutu calistiran oyuncuyu bulur (dogrudan oyuncu, function/execute proxy'si veya isim argumani). */
    private Player resolve(CommandSender sender, String[] args) {
        if (sender instanceof Player p) return p;
        if (sender instanceof ProxiedCommandSender proxy && proxy.getCallee() instanceof Player p) return p;
        // son care: son arguman oyuncu adi olabilir
        if (args.length > 0) {
            Player p = Bukkit.getPlayerExact(args[args.length - 1]);
            if (p != null) return p;
        }
        return null;
    }

    private static double num(String s, double def, double min, double max) {
        try {
            double v = Double.parseDouble(s);
            if (Double.isNaN(v) || Double.isInfinite(v)) return def;
            return Math.max(min, Math.min(max, v));
        } catch (NumberFormatException e) {
            return def;
        }
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (args.length == 0) {
            sender.sendMessage("Kullanim: /rubyvel push <ivme> <tavan> | dash <guc> <yukari> | stop");
            return true;
        }

        Player player = resolve(sender, args);
        if (player == null) {
            sender.sendMessage("RubyMotion: hedef oyuncu bulunamadi.");
            return true;
        }

        switch (args[0].toLowerCase()) {

            // Goktasi: bakis yonunde kademeli hizlanma.
            // Mevcut hiz bakis yonune izdusurulur; tavanin altindaysa aradaki fark
            // kadar (en fazla "ivme") eklenir. Boylece hiz asla tavani asmaz ve
            // hizlanma yumusak kalir.
            case "push" -> {
                double accel = num(args.length > 1 ? args[1] : "", 0.05, 0.0, MAX_ACCEL);
                double cap   = num(args.length > 2 ? args[2] : "", 1.50, 0.0, MAX_CAP);

                Vector dir = player.getLocation().getDirection();
                if (dir.lengthSquared() < 1.0E-6) return true;
                dir.normalize();

                Vector vel = player.getVelocity();
                double along = vel.dot(dir);
                if (along >= cap) return true;

                double add = Math.min(accel, cap - along);
                player.setVelocity(vel.add(dir.multiply(add)));
            }

            // Atilim: tek seferlik ileri itis. Duvar/tavan kontrolu gerekmiyor,
            // carpismayi motorun kendisi hallediyor.
            case "dash" -> {
                double power = num(args.length > 1 ? args[1] : "", 1.10, 0.0, MAX_POWER);
                double up    = num(args.length > 2 ? args[2] : "", 0.30, -MAX_UP, MAX_UP);

                Vector dir = player.getLocation().getDirection();
                dir.setY(0.0);
                if (dir.lengthSquared() < 1.0E-6) return true;
                dir.normalize().multiply(power);
                dir.setY(up);

                player.setVelocity(dir);
            }

            // Yetenek biterken kalan ivmeyi yumusakca kes.
            case "stop" -> {
                Vector vel = player.getVelocity();
                player.setVelocity(vel.multiply(0.6));
            }

            default -> sender.sendMessage("Bilinmeyen alt komut: " + args[0]);
        }
        return true;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (args.length == 1) {
            List<String> out = new ArrayList<>();
            for (String s : Arrays.asList("push", "dash", "stop")) {
                if (s.startsWith(args[0].toLowerCase())) out.add(s);
            }
            return out;
        }
        return List.of();
    }
}
