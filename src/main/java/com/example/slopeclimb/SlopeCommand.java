package com.example.slopeclimb;

import org.bukkit.Bukkit;
import org.bukkit.Material;
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
            case "material" -> material(sender, a);
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
        s.sendMessage("§e/" + l + " create <長さ> [階段素材] [ブロック素材] §7- 向いている方向に幅6の坂を生成（階段+ブロックの2マスで1段、両脇バリア、一番下に壁）");
        s.sendMessage("§e/" + l + " material <stair|block|fill|pad|goal|wall> <素材> §7- 坂の素材を変更して保存");
        s.sendMessage("§e/" + l + " enemy <mob> <押し戻す秒数> [押す力] [数] [方向] [プレイヤー] §7- 敵を召喚");
        s.sendMessage("§e/" + l + " ally <mob> <押す秒数> [押す力] [数] [方向] [プレイヤー] §7- 味方を召喚");
        s.sendMessage("§7  方向(近づいてくる側): front=坂の上 / back=坂の下 / left / right / random");
        s.sendMessage("§7  省略時: 敵=front、味方=back。出現位置はプレイヤーから10マス離れたランダムな場所");
        s.sendMessage("§e/" + l + " clear [enemy|ally] §7- mob を削除");
        s.sendMessage("§e/" + l + " remove §7- コースを元に戻す");
        s.sendMessage("§e/" + l + " info / reload");
        s.sendMessage("§7※ 押す力を省略すると、押す秒数（四捨五入）と同じ値になります。");
    }

    private void create(CommandSender sender, String[] a) {
        if (!(sender instanceof Player p)) {
            sender.sendMessage("§cプレイヤーのみ実行できます。");
            return;
        }
        if (a.length < 2) {
            sender.sendMessage("§c使い方: /koth create <長さ> [階段素材] [ブロック素材]");
            return;
        }
        Integer len = parseInt(a[1]);
        int max = plugin.getConfig().getInt("max-length", 200);
        if (len == null || len < 4 || len > max) {
            sender.sendMessage("§c長さは 4〜" + max + " の整数で指定してください（奇数は偶数に切り上げ）。");
            return;
        }
        Material stair = null, block = null;
        if (a.length > 2) {
            stair = game.parseMaterial("stair", a[2]);
            if (stair == null) {
                sender.sendMessage("§c階段素材が使えません: " + a[2] + "（例: stone_brick_stairs）");
                return;
            }
        }
        if (a.length > 3) {
            block = game.parseMaterial("block", a[3]);
            if (block == null) {
                sender.sendMessage("§cブロック素材が使えません: " + a[3] + "（落下せず完全な立方体のブロックのみ）");
                return;
            }
        }
        String err = game.create(p, len, stair, block);
        if (err != null) sender.sendMessage("§c" + err);
    }

    private void material(CommandSender sender, String[] a) {
        if (a.length < 3) {
            var cfg = plugin.getConfig();
            sender.sendMessage("§6現在の素材 §7(/koth material <種類> <素材> で変更)");
            for (String k : List.of("stair", "block", "fill", "pad", "goal", "wall")) {
                sender.sendMessage("§e" + k + " §f" + cfg.getString("materials." + k, "-"));
            }
            return;
        }
        String err = game.setMaterial(a[1].toLowerCase(Locale.ROOT), a[2]);
        if (err != null) {
            sender.sendMessage("§c" + err);
        } else {
            sender.sendMessage("§a" + a[1].toLowerCase(Locale.ROOT) + " を " + a[2] + " に変更しました。次に作るコースから反映されます。");
        }
    }

    private void spawn(CommandSender sender, String[] a, GameManager.Role role, String label) {
        if (a.length < 3) {
            sender.sendMessage("§c使い方: /" + label + " " + a[0] + " <mob> <秒数> [押す力] [数] [方向] [プレイヤー]");
            return;
        }
        MobSpec spec = game.resolveSpec(a[1], role);
        if (spec == null) {
            sender.sendMessage("§c使用できない mob です。許可: " + String.join(", ", game.allowedMobs(role)));
            return;
        }
        Double secs = parseDouble(a[2]);
        if (secs == null || secs < 0.5 || secs > 120) {
            sender.sendMessage("§c押す時間は 0.5〜120（秒）で指定してください。");
            return;
        }
        Integer power = a.length > 3 ? parseInt(a[3]) : (int) Math.round(secs);
        if (power == null || power < 0 || power > 100000) {
            sender.sendMessage("§c押す力は 0 以上の整数で指定してください。");
            return;
        }
        Integer count = a.length > 4 ? parseInt(a[4]) : 1;
        if (count == null || count < 1 || count > 20) {
            sender.sendMessage("§c数は 1〜20 で指定してください。");
            return;
        }
        GameManager.Side side = null;
        if (a.length > 5) {
            side = GameManager.Side.parse(a[5]);
            if (side == null) {
                sender.sendMessage("§c方向は front / back / left / right / random のいずれかで指定してください。");
                return;
            }
        }
        Player anchor = null;
        if (a.length > 6) {
            anchor = Bukkit.getPlayerExact(a[6]);
            if (anchor == null) {
                sender.sendMessage("§cプレイヤーが見つかりません: " + a[6]);
                return;
            }
        } else if (sender instanceof Player p) {
            anchor = p;
        }
        sender.sendMessage("§a" + game.spawn(role, spec, secs, power, count, side, anchor));
    }

    private void info(CommandSender s) {
        Course c = game.getCourse();
        if (c == null) {
            s.sendMessage("§7コースはありません。");
            return;
        }
        s.sendMessage("§6ワールド: §f" + c.world.getName() + " §6方向: §f" + c.dir + " §6幅: §f" + Course.WIDTH + " §6長さ: §f" + c.length
                + " §6頂上Y: §f" + (c.baseY + c.height) + " §6状態: §f" + (c.ready ? "完成" : "生成中")
                + " §6mob数: §f" + game.mobCount());
    }

    private static Double parseDouble(String s) {
        try {
            double v = Double.parseDouble(s);
            return Double.isFinite(v) ? v : null;
        } catch (NumberFormatException e) {
            return null;
        }
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
            out.addAll(List.of("create", "material", "enemy", "ally", "clear", "remove", "info", "reload", "help"));
        } else if (a.length == 2) {
            switch (a[0].toLowerCase(Locale.ROOT)) {
                case "enemy" -> out.addAll(game.allowedMobs(GameManager.Role.ENEMY));
                case "ally" -> out.addAll(game.allowedMobs(GameManager.Role.ALLY));
                case "clear" -> out.addAll(List.of("enemy", "ally"));
                case "create" -> out.addAll(List.of("20", "50", "100"));
                case "material" -> out.addAll(List.of("stair", "block", "fill", "pad", "goal", "wall"));
                default -> { }
            }
        } else if (a.length == 3 && a[0].equalsIgnoreCase("create")) {
            out.addAll(game.suggestMaterials("stair", a[2]));
        } else if (a.length == 4 && a[0].equalsIgnoreCase("create")) {
            out.addAll(game.suggestMaterials("block", a[3]));
        } else if (a.length == 3 && a[0].equalsIgnoreCase("material")) {
            out.addAll(game.suggestMaterials(a[1].toLowerCase(Locale.ROOT), a[2]));
        } else if (a.length == 3 && (a[0].equalsIgnoreCase("enemy") || a[0].equalsIgnoreCase("ally"))) {
            out.addAll(List.of("1", "3", "5", "10"));
        } else if (a.length == 6 && (a[0].equalsIgnoreCase("enemy") || a[0].equalsIgnoreCase("ally"))) {
            out.addAll(List.of("front", "back", "left", "right", "random"));
        } else if (a.length == 7 && (a[0].equalsIgnoreCase("enemy") || a[0].equalsIgnoreCase("ally"))) {
            for (Player p : Bukkit.getOnlinePlayers()) out.add(p.getName());
        }
        String last = a[a.length - 1].toLowerCase(Locale.ROOT);
        out.removeIf(s -> !s.toLowerCase(Locale.ROOT).startsWith(last));
        return out;
    }
}
