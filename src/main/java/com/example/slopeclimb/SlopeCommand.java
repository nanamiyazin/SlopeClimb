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
            case "buff" -> buff(sender, a);
            case "score" -> score(sender, a);
            case "material" -> material(sender, a);
            case "info" -> info(sender);
            case "reload" -> {
                plugin.reloadConfig();
                game.records().reload();
                sender.sendMessage("§a設定を再読み込みしました。");
            }
            default -> help(sender, label);
        }
        return true;
    }

    private void help(CommandSender s, String l) {
        s.sendMessage("§6=== KOTH (SlopeClimb) ===");
        s.sendMessage("§e/" + l + " create <長さ> [床素材] §7- 向いている方向に幅6マスのまっすぐな道を生成（両脇バリア、スタート側に壁、奥にゴールの台）");
        s.sendMessage("§e/" + l + " material <floor|fill|pad|goal|wall> <素材> §7- 道の素材を変更して保存");
        s.sendMessage("§e/" + l + " enemy <mob> [数] §7- 敵をゴール側に召喚（押す力・時間・速さ・必中は config.yml の enemy-mobs で設定）");
        s.sendMessage("§e/" + l + " ally <mob> [数] §7- 味方をスタート側に召喚（config.yml の ally-mobs で設定）");
        s.sendMessage("§e/" + l + " buff <enemy|ally|off> §7- 敵/味方を強化（味方=道の左半分が氷、敵=右半分がソウルサンド）");
        s.sendMessage("§7  mob はトロッコに乗って、出現したレーンをまっすぐ進みます。出現位置はプレイヤーから10マス離れたランダムな場所");
        s.sendMessage("§e/" + l + " score [reset [プレイヤー名]] §7- 戦績ランキングをチャットに表示 / リセット（画面右のスコアボードには自分の勝ち・負けだけを表示）");
        s.sendMessage("§e/" + l + " clear [enemy|ally] §7- mob を削除");
        s.sendMessage("§e/" + l + " remove §7- コースを元に戻す");
        s.sendMessage("§e/" + l + " info / reload");
    }

    private void create(CommandSender sender, String[] a) {
        if (!(sender instanceof Player p)) {
            sender.sendMessage("§cプレイヤーのみ実行できます。");
            return;
        }
        if (a.length < 2) {
            sender.sendMessage("§c使い方: /koth create <長さ> [床素材]");
            return;
        }
        Integer len = parseInt(a[1]);
        int max = plugin.getConfig().getInt("max-length", 200);
        if (len == null || len < 4 || len > max) {
            sender.sendMessage("§c長さは 4〜" + max + " の整数で指定してください。");
            return;
        }
        Material floor = null;
        if (a.length > 2) {
            floor = game.parseMaterial("floor", a[2]);
            if (floor == null) {
                sender.sendMessage("§c床素材が使えません: " + a[2] + "（落下せず完全な立方体のブロックのみ）");
                return;
            }
        }
        String err = game.create(p, len, floor);
        if (err != null) sender.sendMessage("§c" + err);
    }

    private void material(CommandSender sender, String[] a) {
        if (a.length < 3) {
            var cfg = plugin.getConfig();
            sender.sendMessage("§6現在の素材 §7(/koth material <種類> <素材> で変更)");
            for (String k : List.of("floor", "fill", "pad", "goal", "wall")) {
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
        if (a.length < 2) {
            sender.sendMessage("§c使い方: /" + label + " " + a[0] + " <mob> [数]");
            return;
        }
        MobSpec spec = game.resolveSpec(a[1], role);
        if (spec == null) {
            sender.sendMessage("§c使用できない mob です。許可: " + String.join(", ", game.allowedMobs(role)));
            return;
        }
        int count = 1;
        if (a.length > 2) {
            Integer v = parseInt(a[2]);
            if (v == null || v < 1 || v > 20) {
                sender.sendMessage("§c数は 1〜20 で指定してください: " + a[2]);
                return;
            }
            count = v;
        }
        Player anchor = sender instanceof Player sp ? sp : null;
        sender.sendMessage("§a" + game.spawn(role, spec, count, anchor));
    }

    private void score(CommandSender sender, String[] a) {
        Records r = game.records();
        if (a.length >= 2 && a[1].equalsIgnoreCase("reset")) {
            String target = a.length > 2 ? a[2] : null;
            int n = r.reset(target);
            sender.sendMessage(n > 0
                    ? "§a" + (target == null ? "全員" : target) + " の勝ち数・負け数をリセットしました。"
                    : "§c記録がありません: " + target);
            return;
        }
        List<String> rank = r.ranking();
        if (rank.isEmpty()) {
            sender.sendMessage("§7まだ記録がありません。");
            return;
        }
        sender.sendMessage("§6=== KOTH 戦績 ===");
        int i = 1;
        for (String name : rank) {
            sender.sendMessage("§e" + i++ + ". §f" + name + "  §a勝 " + r.wins(name) + " §7/ §c負 " + r.losses(name));
            if (i > 10) break;
        }
    }

    private void buff(CommandSender sender, String[] a) {
        if (a.length < 2) {
            sender.sendMessage("§c使い方: /koth buff <enemy|ally|off>");
            return;
        }
        String err;
        switch (a[1].toLowerCase(Locale.ROOT)) {
            case "enemy" -> err = game.buff(GameManager.Role.ENEMY);
            case "ally" -> err = game.buff(GameManager.Role.ALLY);
            case "off" -> err = game.buffOff();
            default -> err = "enemy / ally / off のいずれかを指定してください。";
        }
        if (err != null) sender.sendMessage("§c" + err);
    }

    private void info(CommandSender s) {
        Course c = game.getCourse();
        if (c == null) {
            s.sendMessage("§7コースはありません。");
            return;
        }
        s.sendMessage("§6ワールド: §f" + c.world.getName() + " §6方向: §f" + c.dir + " §6幅: §f" + Course.WIDTH + " §6長さ: §f" + c.length
                + " §6状態: §f" + (c.ready ? "完成" : "生成中")
                + " §6mob数: §f" + game.mobCount());
    }

    private static Boolean parseBool(String s) {
        return switch (s.toLowerCase(Locale.ROOT)) {
            case "true", "on", "yes", "1" -> Boolean.TRUE;
            case "false", "off", "no", "0" -> Boolean.FALSE;
            default -> null;
        };
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
            out.addAll(List.of("create", "material", "enemy", "ally", "buff", "score", "clear", "remove", "info", "reload", "help"));
        } else if (a.length == 2) {
            switch (a[0].toLowerCase(Locale.ROOT)) {
                case "enemy" -> out.addAll(game.allowedMobs(GameManager.Role.ENEMY));
                case "ally" -> out.addAll(game.allowedMobs(GameManager.Role.ALLY));
                case "clear" -> out.addAll(List.of("enemy", "ally"));
                case "buff" -> out.addAll(List.of("enemy", "ally", "off"));
                case "score" -> out.add("reset");
                case "create" -> out.addAll(List.of("20", "50", "100"));
                case "material" -> out.addAll(List.of("floor", "fill", "pad", "goal", "wall"));
                default -> { }
            }
        } else if (a.length == 3 && a[0].equalsIgnoreCase("score") && a[1].equalsIgnoreCase("reset")) {
            out.addAll(game.records().ranking());
        } else if (a.length == 3 && a[0].equalsIgnoreCase("create")) {
            out.addAll(game.suggestMaterials("floor", a[2]));
        } else if (a.length == 3 && a[0].equalsIgnoreCase("material")) {
            out.addAll(game.suggestMaterials(a[1].toLowerCase(Locale.ROOT), a[2]));
        } else if (a.length == 3 && (a[0].equalsIgnoreCase("enemy") || a[0].equalsIgnoreCase("ally"))) {
            out.addAll(List.of("1", "3", "5", "10"));
        }
        String last = a[a.length - 1].toLowerCase(Locale.ROOT);
        out.removeIf(s -> !s.toLowerCase(Locale.ROOT).startsWith(last));
        return out;
    }
}
