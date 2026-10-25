package com.example.slopeclimb;

import io.papermc.paper.scoreboard.numbers.NumberFormat;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.scoreboard.Criteria;
import org.bukkit.scoreboard.DisplaySlot;
import org.bukkit.scoreboard.Objective;
import org.bukkit.scoreboard.Scoreboard;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * 勝ち数・負け数の記録とスコアボード表示。
 * 数値はサーバーのメインスコアボードの objective に保存されるので、再起動しても残る。
 *   koth_wins   : 勝ち数   koth_losses : 負け数   （/scoreboard players get <名前> koth_wins などで読める）
 * 画面右のサイドバーには名前を出さず、各プレイヤーに「自分の」勝ち・負けだけを表示する
 * （コースの上にいる間だけ、そのプレイヤー専用のスコアボードに切り替える）。
 */
public final class Records {

    public static final String WINS = "koth_wins";
    public static final String LOSSES = "koth_losses";
    private static final String LEGACY_BOARD = "koth_board";   // 旧バージョンの共有サイドバー（残っていたら消す）

    private final SlopeClimbPlugin plugin;
    private Scoreboard main;
    private Objective wins, losses;
    private final Set<String> known = new LinkedHashSet<>();
    private final Map<UUID, Scoreboard> personal = new HashMap<>();
    private final Set<UUID> shown = new HashSet<>();
    private final Map<UUID, int[]> forces = new HashMap<>();   // {押す力(味方), 押し戻す力(敵)}

    public Records(SlopeClimbPlugin plugin) {
        this.plugin = plugin;
    }

    private boolean showBoard() {
        return plugin.getConfig().getBoolean("scoreboard.enabled", true);
    }

    public void enable() {
        main = Bukkit.getScoreboardManager().getMainScoreboard();
        Objective legacy = main.getObjective(LEGACY_BOARD);
        if (legacy != null) legacy.unregister();

        wins = main.getObjective(WINS);
        if (wins == null) wins = main.registerNewObjective(WINS, Criteria.DUMMY, Component.text("勝ち数"));
        losses = main.getObjective(LOSSES);
        if (losses == null) losses = main.registerNewObjective(LOSSES, Criteria.DUMMY, Component.text("負け数"));

        known.clear();
        for (String entry : main.getEntries()) {
            if (wins.getScore(entry).isScoreSet() || losses.getScore(entry).isScoreSet()) known.add(entry);
        }
    }

    /** /koth reload のとき: タイトルや表示のオン・オフを反映する（表示は次の tick で作り直される） */
    public void reload() {
        restoreAll();
    }

    public void disable() {
        restoreAll();
    }

    private void restoreAll() {
        for (UUID id : new ArrayList<>(shown)) {
            Player p = Bukkit.getPlayer(id);
            if (p != null) p.setScoreboard(main);
        }
        shown.clear();
        personal.clear();
    }

    public int wins(String name) {
        return wins == null ? 0 : wins.getScore(name).getScore();
    }

    public int losses(String name) {
        return losses == null ? 0 : losses.getScore(name).getScore();
    }

    /** コースの上にいるプレイヤーを登録（初めてなら 勝0 / 負0）し、そのプレイヤー専用のスコアボードを出す */
    public void touch(Player p) {
        String name = p.getName();
        if (known.add(name)) {
            wins.getScore(name).setScore(wins(name));
            losses.getScore(name).setScore(losses(name));
        }
        show(p);
    }

    private void show(Player p) {
        if (!showBoard()) return;
        UUID id = p.getUniqueId();
        if (shown.contains(id)) return;

        Scoreboard b = personal.get(id);
        if (b == null) {
            b = Bukkit.getScoreboardManager().getNewScoreboard();
            String title = plugin.getConfig().getString("scoreboard.title", "KOTH 戦績");
            Objective o = b.registerNewObjective("koth", Criteria.DUMMY, Component.text(title, NamedTextColor.GOLD));
            o.numberFormat(NumberFormat.blank());
            o.setDisplaySlot(DisplaySlot.SIDEBAR);
            personal.put(id, b);
        }
        p.setScoreboard(b);
        shown.add(id);
        update(p);
    }

    /** コースから離れたら、元のスコアボードに戻す */
    public void hide(Player p) {
        if (shown.remove(p.getUniqueId())) p.setScoreboard(main);
    }

    /** 退出したプレイヤーの表示を片付ける */
    public void forget(Player p) {
        forces.remove(p.getUniqueId());
        shown.remove(p.getUniqueId());
        personal.remove(p.getUniqueId());
    }

    /** 押し合いの力を更新する（push = 押す力（味方の合計）、back = 押し戻す力（敵の合計）） */
    public void setForces(Player p, int push, int back) {
        UUID id = p.getUniqueId();
        int[] cur = forces.get(id);
        if (cur == null ? (push == 0 && back == 0) : (cur[0] == push && cur[1] == back)) return;
        forces.put(id, new int[]{push, back});
        if (shown.contains(id)) update(p);
    }

    private void update(Player p) {
        Scoreboard b = personal.get(p.getUniqueId());
        if (b == null) return;
        Objective o = b.getObjective("koth");
        if (o == null) return;
        String name = p.getName();
        int[] f = forces.getOrDefault(p.getUniqueId(), new int[]{0, 0});
        int net = f[0] - f[1];

        // 名前は載せず、数だけを出す（上から 勝ち / 負け / 勝敗合計 / 区切り / 押す力 / 押し戻す力 / 力の合計）
        int w = wins(name), l = losses(name), tot = w - l;
        line(o, "wins", 7, Component.text("勝ち  " + w, NamedTextColor.GREEN));
        line(o, "losses", 6, Component.text("負け  " + l, NamedTextColor.RED));
        line(o, "total", 5, Component.text("勝敗合計  " + (tot > 0 ? "+" : "") + tot,
                tot > 0 ? NamedTextColor.GREEN : (tot < 0 ? NamedTextColor.RED : NamedTextColor.YELLOW)));
        line(o, "sep", 4, Component.text("──────────", NamedTextColor.DARK_GRAY));
        line(o, "push", 3, Component.text("押す力(味方)  " + f[0], NamedTextColor.AQUA));
        line(o, "back", 2, Component.text("押し戻す力(敵)  " + f[1], NamedTextColor.LIGHT_PURPLE));
        line(o, "net", 1, Component.text("力の合計  " + (net > 0 ? "+" : "") + net,
                net > 0 ? NamedTextColor.GREEN : (net < 0 ? NamedTextColor.RED : NamedTextColor.YELLOW)));
    }

    private static void line(Objective o, String entry, int score, Component text) {
        var sc = o.getScore(entry);
        sc.setScore(score);
        sc.customName(text);
    }

    private void updateOnline(String name) {
        Player p = Bukkit.getPlayerExact(name);
        if (p != null && shown.contains(p.getUniqueId())) update(p);
    }

    public void addWin(Player p) {
        touch(p);
        String name = p.getName();
        wins.getScore(name).setScore(wins(name) + 1);
        update(p);
    }

    /** 負けを 1 増やす。minusWin が true なら勝ち数も 1 減らす（0 未満にはならない） */
    public void addLoss(Player p, boolean minusWin) {
        touch(p);
        String name = p.getName();
        losses.getScore(name).setScore(losses(name) + 1);
        if (minusWin) wins.getScore(name).setScore(Math.max(0, wins(name) - 1));
        update(p);
    }

    /** @return リセットした人数 */
    public int reset(String nameOrNull) {
        int n = 0;
        if (nameOrNull == null) {
            for (String name : new ArrayList<>(known)) {
                main.resetScores(name);
                n++;
            }
            known.clear();
        } else {
            String match = null;
            for (String name : known) {
                if (name.equalsIgnoreCase(nameOrNull)) match = name;
            }
            if (match != null) {
                main.resetScores(match);
                known.remove(match);
                n = 1;
            }
        }
        for (UUID id : new ArrayList<>(shown)) {
            Player p = Bukkit.getPlayer(id);
            if (p != null) update(p);
        }
        return n;
    }

    /** 成績の多い順（勝ち数が多い順、同じなら負け数が少ない順）。チャットの /koth score 用 */
    public List<String> ranking() {
        List<String> names = new ArrayList<>(known);
        names.sort(Comparator.<String>comparingInt(this::wins).reversed().thenComparingInt(this::losses));
        return names;
    }
}
