package com.example.slopeclimb;

import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public final class SlopeCommand implements CommandExecutor, TabCompleter {

    private final SlopeClimbPlugin plugin;
    private final GameManager game;

    public SlopeCommand(SlopeClimbPlugin plugin, GameManager game) {
        this.plugin = plugin;
        this.game = game;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command cmd, String label, String[] a) {
        if (a.length == 0) {
            help(sender, label);
            return true;
        }
        switch (a[0].toLowerCase(Locale.ROOT)) {
            case "create" -> create(sender, a);
            case "enemy" -> spawn(sender, a, GameManager.Role.ENEMY, label);
            case "ally" -> spawn(sender, a, GameManager.Role.ALLY, label);
            case "clear" -> {
                GameManager.Role only = null;
                if (a.length > 1) {
                    switch (a[1].toLowerCase(Locale.ROOT)) {
                        case "enemy" -> only = GameManager.Role.ENEMY;
                        case "ally" -> only = GameManager.Role.ALLY;
                        default -> { }
                    }
                }
                sender.sendMessage("§a" + game.clearMobs(only) + " 体の mob を削除しました。");
            }
            case "remove" -> {
                String err = game.remove();
                sender.sendMessage(err == null ? "§aコースを元に戻しています..." : "§c" + err);
            }
            case "info" -> info(sender);
            case "reload" -> {
                plugin.reloadConfig();
                sender.sendMessage("§a設定を再読み込みしました。");
            }
            default -> help(sender, label);
        }
        return true;
    }

    private void help(CommandSender s, String l) {
        s.sendMessage("§6=== KOTH (SlopeClimb) ===");
        s.sendMessage("§e/" + l + " create <長さ> §7- 今向いている方向に幅3の坂を生成（階段+ブロックの2マスで1段、両脇バリア）");
        s.sendMessage("§e/" + l + " enemy <mob> <押し戻す距離> [押す力] [数] [プレイヤー] §7- 敵を前方に召喚");
        s.sendMessage("§e/" + l + " ally <mob> <押す距離> [押す力] [数] [プレイヤー] §7- 味方を後方に召喚");
        s.sendMessage("§e/" + l + " clear [enemy|ally] §7- mob を削除");
        s.sendMessage("§e/" + l + " remove §7- コースを元に戻す");
        s.sendMessage("§e/" + l + " info / reload");
        s.sendMessage("§7※ 押す力を省略すると、押す距離と同じ値になります。");
    }

    private void create(CommandSender sender, String[] a) {
        if (!(sender instanceof Player p)) {
            sender.sendMessage("§cプレイヤーのみ実行できます。");
            return;
        }
        if (a.length < 2) {
            sender.sendMessage("§c使い方: /koth create <長さ>");
            return;
        }
        Integer len = parseInt(a[1]);
        int max = plugin.getConfig().getInt("max-length", 200);
        if (len == null || len < 4 || len > max) {
            sender.sendMessage("§c長さは 4〜" + max + " の整数で指定してください（奇数は偶数に切り上げ）。");
            return;
        }
        String err = game.create(p, len);
        if (err != null) sender.sendMessage("§c" + err);
    }

    private void spawn(CommandSender sender, String[] a, GameManager.Role role, String label) {
        if (a.length < 3) {
            sender.sendMessage("§c使い方: /" + label + " " + a[0] + " <mob> <距離> [押す力] [数] [プレイヤー]");
            return;
        }
        MobSpec spec = game.resolveSpec(a[1], role);
        if (spec == null) {
            sender.sendMessage("§c使用できない mob です。許可: " + String.join(", ", game.allowedMobs(role)));
            return;
        }
        Integer dist = parseInt(a[2]);
        if (dist == null || dist < 1 || dist > 200) {
            sender.sendMessage("§c距離は 1〜200 の整数で指定してください。");
            return;
        }
        Integer power = a.length > 3 ? parseInt(a[3]) : dist;
        if (power == null || power < 0 || power > 100000) {
            sender.sendMessage("§c押す力は 0 以上の整数で指定してください。");
            return;
        }
        Integer count = a.length > 4 ? parseInt(a[4]) : 1;
        if (count == null || count < 1 || count > 20) {
            sender.sendMessage("§c数は 1〜20 で指定してください。");
            return;
        }
        Player anchor = null;
        if (a.length > 5) {
            anchor = Bukkit.getPlayerExact(a[5]);
            if (anchor == null) {
                sender.sendMessage("§cプレイヤーが見つかりません: " + a[5]);
                return;
            }
        } else if (sender instanceof Player p) {
            anchor = p;
        }
        sender.sendMessage("§a" + game.spawn(role, spec, dist, power, count, anchor));
    }

    private void info(CommandSender s) {
        Course c = game.getCourse();
        if (c == null) {
            s.sendMessage("§7コースはありません。");
            return;
        }
        s.sendMessage("§6ワールド: §f" + c.world.getName() + " §6方向: §f" + c.dir + " §6長さ: §f" + c.length
                + " §6頂上Y: §f" + (c.baseY + c.height) + " §6状態: §f" + (c.ready ? "完成" : "生成中")
                + " §6mob数: §f" + game.mobCount());
    }

    private static Integer parseInt(String s) {
        try {
            return Integer.parseInt(s);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command cmd, String alias, String[] a) {
        List<String> out = new ArrayList<>();
        if (a.length == 1) {
            out.addAll(List.of("create", "enemy", "ally", "clear", "remove", "info", "reload", "help"));
        } else if (a.length == 2) {
            switch (a[0].toLowerCase(Locale.ROOT)) {
                case "enemy" -> out.addAll(game.allowedMobs(GameManager.Role.ENEMY));
                case "ally" -> out.addAll(game.allowedMobs(GameManager.Role.ALLY));
                case "clear" -> out.addAll(List.of("enemy", "ally"));
                case "create" -> out.addAll(List.of("20", "50", "100"));
                default -> { }
            }
        } else if (a.length == 3 && (a[0].equalsIgnoreCase("enemy") || a[0].equalsIgnoreCase("ally"))) {
            out.addAll(List.of("3", "5", "10"));
        } else if (a.length == 6 && (a[0].equalsIgnoreCase("enemy") || a[0].equalsIgnoreCase("ally"))) {
            for (Player p : Bukkit.getOnlinePlayers()) out.add(p.getName());
        }
        String last = a[a.length - 1].toLowerCase(Locale.ROOT);
        out.removeIf(s -> !s.toLowerCase(Locale.ROOT).startsWith(last));
        return out;
    }
}
