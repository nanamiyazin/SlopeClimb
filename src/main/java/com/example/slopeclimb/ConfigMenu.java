package com.example.slopeclimb;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.BiConsumer;

/**
 * ゲーム内で config.yml の設定を変えるメニュー画面（/koth config）。
 * オン・オフはクリックで切り替え、数値は 左クリック=増やす / 右クリック=減らす / Shift で 10 倍。
 * 変更はすぐ config.yml に保存され、反映される。
 */
public final class ConfigMenu implements Listener {

    private enum Type { BOOL, INT, DOUBLE }

    /** fallback: 個別に設定されていないときに使う値の場所（mob ごとの設定 → mob-defaults） */
    private record Setting(String path, String name, Material icon, Type type,
                           double step, double min, double max, String desc, String fallback, boolean nextSpawn) {}

    private enum Page { MAIN, GENERAL, WALL, DISPLAY, INTRO, BUFF, MOB_DEFAULTS, ENEMY_LIST, ALLY_LIST, MOB }

    private static final class Holder implements InventoryHolder {
        final Page page;
        final String arg;                                   // MOB ページでは "enemy:zombie" など
        Inventory inv;
        final Map<Integer, BiConsumer<Player, ClickType>> actions = new HashMap<>();

        Holder(Page page, String arg) {
            this.page = page;
            this.arg = arg;
        }

        @Override
        public Inventory getInventory() {
            return inv;
        }
    }

    private static final int[] SLOTS = {10, 11, 12, 13, 14, 15, 16, 19, 20, 21, 22, 23, 24, 25, 28, 29, 30, 31, 32, 33, 34};

    private final SlopeClimbPlugin plugin;
    private final GameManager game;

    public ConfigMenu(SlopeClimbPlugin plugin, GameManager game) {
        this.plugin = plugin;
        this.game = game;
    }

    private FileConfiguration cfg() {
        return plugin.getConfig();
    }

    // ============================================================
    //  設定項目の定義
    // ============================================================

    private static Setting bool(String path, String name, Material icon, String desc) {
        return new Setting(path, name, icon, Type.BOOL, 0, 0, 0, desc, null, false);
    }

    private static Setting num(String path, String name, Material icon, Type t, double step, double min, double max, String desc) {
        return new Setting(path, name, icon, t, step, min, max, desc, null, false);
    }

    private static List<Setting> general() {
        return List.of(
                num("max-length", "道の最大の長さ", Material.RAIL, Type.INT, 10, 4, 200, "/koth create で作れる道の最大の長さ（マス）"),
                num("spawn-distance", "mob の出現距離", Material.COMPASS, Type.DOUBLE, 1, 3, 50, "プレイヤーから何マス離れた所に mob が出るか"),
                bool("spawn-lane-center", "出現する列を真ん中に固定", Material.RAIL, "オン = mob は道の真ん中の列に出る / オフ = 横のランダムな列に出る"),
                num("push-speed", "押されるときの速さ", Material.PISTON, Type.DOUBLE, 0.01, 0.02, 1.0, "1tick あたりのマス数（0.18 = 約3.6マス/秒）。遅いほど押される距離は短くなる"),
                num("summit-hold-seconds", "ゴールで耐える秒数", Material.GOLD_BLOCK, Type.DOUBLE, 1, 1, 300, "ゴールの台にこの秒数とどまると勝ち"),
                num("push-cooldown-ticks", "押された後の無敵時間", Material.SHIELD, Type.INT, 5, 0, 200, "押された後、次に押されるまでの時間（tick、20 = 1秒）"),
                num("contact-range", "接触判定の余裕", Material.SLIME_BALL, Type.DOUBLE, 0.05, 0, 3, "大きいほど、遠くからでも接触したことになる（ブロック）"),
                num("giant-scale", "巨大 mob の大きさ", Material.CAT_SPAWN_EGG, Type.DOUBLE, 0.5, 1, 10, "巨大ねこ・巨大ウーパールーパーの大きさ（倍）。次の召喚から反映"),
                bool("restore-on-disable", "停止時にコースを戻す", Material.STRUCTURE_VOID, "サーバー停止・プラグイン無効化のとき、コースを元のブロックに戻す"));
    }

    private static List<Setting> wall() {
        return List.of(
                bool("wall-damage.enabled", "ダメージゲージ", Material.REDSTONE, "スタート地点の壁に押し付けられるとゲージが減る"),
                num("wall-damage.gauge-max", "ゲージの最大値", Material.RED_DYE, Type.INT, 10, 10, 10000, "ゲージの数値の最大"),
                num("wall-damage.per-block", "1マスで減る量", Material.IRON_SWORD, Type.INT, 1, 1, 1000, "壁に押し付けられた1マスごとに減る量"),
                num("wall-damage.regen-per-second", "自然回復（1秒）", Material.GLISTERING_MELON_SLICE, Type.DOUBLE, 1, 0, 100, "1秒ごとの自然回復量（0 = 回復しない）"),
                num("wall-damage.reset-delay-ticks", "満タンに戻る時間", Material.CLOCK, Type.INT, 10, 0, 600, "0 になって「-1win」と出てから満タンに戻るまで（tick）"),
                bool("wall-damage.kill-on-zero", "0でプレイヤーが死亡", Material.WITHER_SKELETON_SKULL, "オンにすると、ゲージが 0 になったときプレイヤーが死亡する"));
    }

    private static List<Setting> display() {
        return List.of(
                bool("scoreboard.enabled", "スコアボード", Material.OAK_SIGN, "画面右に 勝ち・負け・押す力・押し戻す力・合計 を表示する（記録は残る）"),
                bool("scoreboard.minus-win-on-loss", "負けで勝ち数も-1", Material.BARRIER, "オンにすると、負けたとき勝ち数も 1 減る（0 未満にはならない）"),
                bool("goal-distance.enabled", "ゴールまでの距離", Material.ENDER_EYE, "画面上部に「ゴールまで N マス」を表示する"));
    }

    private static List<Setting> intro() {
        return List.of(
                bool("intro.enabled", "乱入演出", Material.DRAGON_HEAD, "ウォーデン・ドラゴン・ウィザーが遠くから現れ、カメラが追う"),
                num("intro.distance", "出現する距離", Material.COMPASS, Type.DOUBLE, 5, 10, 100, "演出で mob が現れる距離（マス）"),
                num("intro.max-ticks", "演出の最大の長さ", Material.CLOCK, Type.INT, 20, 40, 600, "tick（20 = 1秒）。mob が近づくと自動で終わる"));
    }

    private static List<Setting> buff() {
        return List.of(
                num("buff.duration-seconds", "強化の長さ", Material.CLOCK, Type.INT, 5, 5, 600, "/koth buff の強化が続く秒数"),
                num("buff.ally.power-multiplier", "味方: 押す力の倍率", Material.IRON_SWORD, Type.DOUBLE, 0.1, 0.1, 10, "強化中の味方の押す力（倍）"),
                num("buff.ally.seconds-multiplier", "味方: 押す時間の倍率", Material.CLOCK, Type.DOUBLE, 0.1, 0.1, 10, "強化中の味方の押す時間（倍）"),
                num("buff.ally.speed-multiplier", "味方: 速さの倍率", Material.FEATHER, Type.DOUBLE, 0.1, 0.1, 10, "強化中の味方の速さ（倍）"),
                num("buff.enemy.power-multiplier", "敵: 押す力の倍率", Material.IRON_SWORD, Type.DOUBLE, 0.1, 0.1, 10, "強化中の敵の押す力（倍）"),
                num("buff.enemy.seconds-multiplier", "敵: 押す時間の倍率", Material.CLOCK, Type.DOUBLE, 0.1, 0.1, 10, "強化中の敵の押す時間（倍）"),
                num("buff.enemy.speed-multiplier", "敵: 速さの倍率", Material.FEATHER, Type.DOUBLE, 0.1, 0.1, 10, "強化中の敵の速さ（倍）"));
    }

    /** mob の設定項目。prefix = "mob-defaults" か "enemy-mobs.zombie" など */
    private static List<Setting> mobFields(String prefix, boolean perMob) {
        String fb = perMob ? "mob-defaults." : null;
        return List.of(
                new Setting(prefix + ".seconds", "押す時間（秒）", Material.CLOCK, Type.DOUBLE, 0.5, 0.5, 120,
                        "接触したとき押す・押し戻す時間。距離 = 秒数 × 押される速さ", fb == null ? null : fb + "seconds", true),
                new Setting(prefix + ".power", "押す力", Material.IRON_SWORD, Type.INT, 1, 0, 100000,
                        "押し合いで強い方が勝つ。同じ種類は足し算されず、違う種類は足し算される", fb == null ? null : fb + "power", true),
                new Setting(prefix + ".speed", "移動速度（マス/秒）", Material.FEATHER, Type.DOUBLE, 0.5, 0.5, 40,
                        "mob がトロッコで進む速さ", fb == null ? null : fb + "speed", true),
                new Setting(prefix + ".hit", "必中", Material.ENDER_EYE, Type.BOOL, 0, 0, 0,
                        "オン = 横にずれていても通過すれば必ず接触 / オフ = 体が触れたときだけ接触", fb == null ? null : fb + "hit", true));
    }

    // ============================================================
    //  開く・描画
    // ============================================================

    public void open(Player p) {
        open(p, Page.MAIN, null);
    }

    private void open(Player p, Page page, String arg) {
        Holder h = new Holder(page, arg);
        String title = switch (page) {
            case MAIN -> "KOTH 設定";
            case GENERAL -> "設定 › 基本";
            case WALL -> "設定 › ダメージゲージ";
            case DISPLAY -> "設定 › 表示・スコアボード";
            case INTRO -> "設定 › 乱入演出";
            case BUFF -> "設定 › 強化";
            case MOB_DEFAULTS -> "設定 › mob の初期値";
            case ENEMY_LIST -> "設定 › 敵 mob";
            case ALLY_LIST -> "設定 › 味方 mob";
            case MOB -> "設定 › " + (arg != null && arg.startsWith("enemy:") ? "敵 " : "味方 ") + (arg == null ? "" : arg.substring(arg.indexOf(':') + 1));
        };
        h.inv = Bukkit.createInventory(h, 54, Component.text(title));
        render(h);
        p.openInventory(h.inv);
        p.playSound(p.getLocation(), Sound.UI_BUTTON_CLICK, 0.6f, 1.2f);
    }

    private void later(Player p, Page page, String arg) {
        Bukkit.getScheduler().runTask(plugin, () -> open(p, page, arg));
    }

    private void render(Holder h) {
        h.inv.clear();
        h.actions.clear();

        ItemStack pane = item(Material.BLACK_STAINED_GLASS_PANE, Component.text(" "));
        for (int i = 0; i < 54; i++) h.inv.setItem(i, pane);

        switch (h.page) {
            case MAIN -> renderMain(h);
            case GENERAL -> renderSettings(h, general());
            case WALL -> renderSettings(h, wall());
            case DISPLAY -> renderSettings(h, display());
            case INTRO -> renderSettings(h, intro());
            case BUFF -> renderSettings(h, buff());
            case MOB_DEFAULTS -> renderSettings(h, mobFields("mob-defaults", false));
            case ENEMY_LIST -> renderMobList(h, GameManager.Role.ENEMY);
            case ALLY_LIST -> renderMobList(h, GameManager.Role.ALLY);
            case MOB -> renderMob(h);
        }

        // 下の段: 戻る / 閉じる
        if (h.page == Page.MAIN) {
            put(h, 49, item(Material.BARRIER, name("閉じる", NamedTextColor.RED)), (p, c) -> p.closeInventory());
        } else {
            Page back = switch (h.page) {
                case MOB -> (h.arg != null && h.arg.startsWith("enemy:")) ? Page.ENEMY_LIST : Page.ALLY_LIST;
                default -> Page.MAIN;
            };
            put(h, 49, item(Material.ARROW, name("戻る", NamedTextColor.YELLOW)), (p, c) -> later(p, back, null));
        }
    }

    private void renderMain(Holder h) {
        h.inv.setItem(4, item(Material.NETHER_STAR, name("KOTH 設定", NamedTextColor.GOLD),
                lore("変えたい項目を選んでください。", "変更はすぐ保存され、反映されます。",
                        "", "道の素材は /koth material で変更します。")));

        menuButton(h, 10, Material.COMPARATOR, "基本設定", "速さ・距離・耐える秒数 など", Page.GENERAL, null);
        menuButton(h, 11, Material.REDSTONE, "ダメージゲージ", "壁に押し付けられたときの減り方", Page.WALL, null);
        menuButton(h, 12, Material.OAK_SIGN, "表示・スコアボード", "スコアボード・ゴールまでの距離", Page.DISPLAY, null);
        menuButton(h, 13, Material.DRAGON_HEAD, "乱入演出", "ウォーデン・ドラゴン・ウィザーの登場", Page.INTRO, null);
        menuButton(h, 14, Material.BEACON, "強化", "強化の長さと倍率", Page.BUFF, null);
        menuButton(h, 15, Material.EGG, "mob の初期値", "mob ごとの設定がないときに使う値", Page.MOB_DEFAULTS, null);
        menuButton(h, 16, Material.ZOMBIE_HEAD, "敵 mob の設定", "mob ごとの押す力・時間・速さ・必中", Page.ENEMY_LIST, null);
        menuButton(h, 19, Material.GOLDEN_APPLE, "味方 mob の設定", "mob ごとの押す力・時間・速さ・必中", Page.ALLY_LIST, null);

        put(h, 25, item(Material.PAPER, name("config.yml を読み込み直す", NamedTextColor.AQUA),
                lore("ファイルを直接編集したときに押します。")), (p, c) -> {
            plugin.reloadConfig();
            game.records().reload();
            p.sendMessage(Component.text("設定を再読み込みしました。", NamedTextColor.GREEN));
            click(p);
            render(h);
        });
    }

    private void menuButton(Holder h, int slot, Material icon, String title, String desc, Page page, String arg) {
        put(h, slot, item(icon, name(title, NamedTextColor.GREEN), lore(desc, "", "クリックで開く")),
                (p, c) -> later(p, page, arg));
    }

    private void renderSettings(Holder h, List<Setting> list) {
        for (int i = 0; i < list.size() && i < SLOTS.length; i++) {
            Setting s = list.get(i);
            put(h, SLOTS[i], settingItem(s), (p, c) -> {
                change(p, s, c);
                render(h);
            });
        }
    }

    private void renderMobList(Holder h, GameManager.Role role) {
        String sec = role == GameManager.Role.ENEMY ? "enemy-mobs" : "ally-mobs";
        String tag = role == GameManager.Role.ENEMY ? "enemy" : "ally";
        List<String> mobs = game.allowedMobs(role);
        for (int i = 0; i < mobs.size() && i < 45; i++) {
            String key = mobs.get(i);
            List<Component> lines = new ArrayList<>();
            lines.add(plain("押す時間  " + fmt(mobValue(sec, key, "seconds")) + " 秒", NamedTextColor.GRAY));
            lines.add(plain("押す力  " + fmt(mobValue(sec, key, "power")), NamedTextColor.GRAY));
            lines.add(plain("速さ  " + fmt(mobValue(sec, key, "speed")) + " マス/秒", NamedTextColor.GRAY));
            lines.add(plain("必中  " + (mobBool(sec, key) ? "オン" : "オフ"), NamedTextColor.GRAY));
            lines.add(Component.empty());
            lines.add(plain("クリックで編集", NamedTextColor.YELLOW));
            put(h, i, item(mobIcon(key), name(key, role == GameManager.Role.ENEMY ? NamedTextColor.RED : NamedTextColor.GREEN), lines),
                    (p, c) -> later(p, Page.MOB, tag + ":" + key));
        }
    }

    private void renderMob(Holder h) {
        String arg = h.arg == null ? "enemy:zombie" : h.arg;
        String tag = arg.substring(0, arg.indexOf(':'));
        String key = arg.substring(arg.indexOf(':') + 1);
        String sec = tag.equals("enemy") ? "enemy-mobs" : "ally-mobs";

        h.inv.setItem(4, item(mobIcon(key), name((tag.equals("enemy") ? "敵 " : "味方 ") + key,
                tag.equals("enemy") ? NamedTextColor.RED : NamedTextColor.GREEN),
                lore("この mob だけの設定です。", "Q キーで「初期値を使う」に戻せます。", "※ 次に召喚する mob から反映されます。")));

        List<Setting> list = mobFields(sec + "." + key, true);
        int[] slots = {19, 21, 23, 25};
        for (int i = 0; i < list.size(); i++) {
            Setting s = list.get(i);
            put(h, slots[i], settingItem(s), (p, c) -> {
                change(p, s, c);
                render(h);
            });
        }
    }

    // ============================================================
    //  値の読み書き
    // ============================================================

    private boolean hasOwn(Setting s) {
        return cfg().contains(s.path(), true);
    }

    private Object read(Setting s) {
        if (s.fallback() != null && !hasOwn(s)) return cfg().get(s.fallback());
        return cfg().get(s.path());
    }

    private double num(Setting s) {
        Object v = read(s);
        return v instanceof Number n ? n.doubleValue() : 0;
    }

    private boolean flag(Setting s) {
        Object v = read(s);
        return v instanceof Boolean b && b;
    }

    private double mobValue(String sec, String key, String field) {
        String own = sec + "." + key + "." + field;
        if (cfg().contains(own, true)) return cfg().getDouble(own);
        return cfg().getDouble("mob-defaults." + field);
    }

    private boolean mobBool(String sec, String key) {
        String own = sec + "." + key + ".hit";
        if (cfg().contains(own, true)) return cfg().getBoolean(own);
        return cfg().getBoolean("mob-defaults.hit", true);
    }

    private void change(Player p, Setting s, ClickType c) {
        // Q（ドロップ）・ホイールクリック: 初期値に戻す
        if (c == ClickType.DROP || c == ClickType.CONTROL_DROP || c == ClickType.MIDDLE) {
            if (s.fallback() != null) {
                cfg().set(s.path(), null);        // mob ごとの設定を消して、初期値を使う
            } else {
                Object def = cfg().getDefaults() == null ? null : cfg().getDefaults().get(s.path());
                if (def == null) {
                    p.sendMessage(Component.text("初期値が見つかりません。", NamedTextColor.RED));
                    return;
                }
                cfg().set(s.path(), def);
            }
            saved(p, s.path());
            return;
        }

        if (s.type() == Type.BOOL) {
            if (c != ClickType.LEFT && c != ClickType.RIGHT && c != ClickType.SHIFT_LEFT && c != ClickType.SHIFT_RIGHT) return;
            cfg().set(s.path(), !flag(s));
            saved(p, s.path());
            return;
        }

        double mult = (c == ClickType.SHIFT_LEFT || c == ClickType.SHIFT_RIGHT) ? 10 : 1;
        double dir;
        if (c == ClickType.LEFT || c == ClickType.SHIFT_LEFT) dir = 1;
        else if (c == ClickType.RIGHT || c == ClickType.SHIFT_RIGHT) dir = -1;
        else return;

        double v = Math.max(s.min(), Math.min(s.max(), num(s) + dir * s.step() * mult));
        v = Math.round(v * 100.0) / 100.0;
        if (s.type() == Type.INT) cfg().set(s.path(), (int) Math.round(v));
        else cfg().set(s.path(), v);
        saved(p, s.path());
    }

    /** 空の mob 設定（zombie: {}）は保存時に消えてしまうので、目印の値を入れて残す */
    private void keepMobs() {
        for (String root : new String[]{"enemy-mobs", "ally-mobs"}) {
            org.bukkit.configuration.ConfigurationSection sec = cfg().getConfigurationSection(root);
            if (sec == null) continue;
            for (String k : sec.getKeys(false)) {
                org.bukkit.configuration.ConfigurationSection m = sec.getConfigurationSection(k);
                if (m != null && m.getKeys(false).isEmpty()) m.set("enabled", true);
            }
        }
    }

    private void saved(Player p, String path) {
        keepMobs();
        plugin.saveConfig();
        if (path.startsWith("scoreboard.")) game.records().reload();
        click(p);
    }

    private void click(Player p) {
        p.playSound(p.getLocation(), Sound.UI_BUTTON_CLICK, 0.6f, 1.4f);
    }

    // ============================================================
    //  アイテム作り
    // ============================================================

    private ItemStack settingItem(Setting s) {
        List<Component> lines = new ArrayList<>();
        lines.add(plain(s.desc(), NamedTextColor.GRAY));
        lines.add(Component.empty());

        boolean own = s.fallback() == null || hasOwn(s);
        Material icon = s.icon();
        if (s.type() == Type.BOOL) {
            boolean on = flag(s);
            icon = on ? Material.LIME_DYE : Material.GRAY_DYE;
            lines.add(plain("現在:  " + (on ? "オン" : "オフ") + (own ? "" : "（初期値を使用）"),
                    on ? NamedTextColor.GREEN : NamedTextColor.RED));
            lines.add(Component.empty());
            lines.add(plain("クリック: オン / オフを切り替え", NamedTextColor.YELLOW));
        } else {
            lines.add(plain("現在:  " + fmt(num(s)) + (own ? "" : "（初期値を使用）"), NamedTextColor.GOLD));
            lines.add(plain("範囲:  " + fmt(s.min()) + " ～ " + fmt(s.max()), NamedTextColor.DARK_GRAY));
            lines.add(Component.empty());
            lines.add(plain("左クリック: +" + fmt(s.step()), NamedTextColor.YELLOW));
            lines.add(plain("右クリック: -" + fmt(s.step()), NamedTextColor.YELLOW));
            lines.add(plain("Shift + クリック: 10倍", NamedTextColor.YELLOW));
        }
        lines.add(plain(s.fallback() != null ? "Q キー: 初期値（mob の初期値）を使う" : "Q キー: 最初の値に戻す", NamedTextColor.YELLOW));
        if (s.nextSpawn()) lines.add(plain("※ 次に召喚する mob から反映", NamedTextColor.DARK_GRAY));
        return item(icon, name(s.name(), NamedTextColor.WHITE), lines);
    }

    private Material mobIcon(String key) {
        return switch (key) {
            case "ender_dragon" -> Material.DRAGON_HEAD;
            case "wither" -> Material.NETHER_STAR;
            case "iron_golem" -> Material.IRON_BLOCK;
            case "giant_cat" -> Material.CAT_SPAWN_EGG;
            case "giant_axolotl" -> Material.AXOLOTL_SPAWN_EGG;
            default -> {
                Material m = Material.matchMaterial(key.toLowerCase(Locale.ROOT) + "_spawn_egg");
                yield m != null ? m : Material.EGG;
            }
        };
    }

    private static Component name(String text, NamedTextColor color) {
        return Component.text(text, color).decoration(TextDecoration.ITALIC, false).decoration(TextDecoration.BOLD, true);
    }

    private static Component plain(String text, NamedTextColor color) {
        return Component.text(text, color).decoration(TextDecoration.ITALIC, false);
    }

    private static List<Component> lore(String... lines) {
        List<Component> out = new ArrayList<>();
        for (String l : lines) out.add(plain(l, NamedTextColor.GRAY));
        return out;
    }

    private static ItemStack item(Material m, Component name) {
        return item(m, name, List.of());
    }

    private static ItemStack item(Material m, Component name, List<Component> lore) {
        ItemStack it = new ItemStack(m);
        ItemMeta meta = it.getItemMeta();
        meta.displayName(name);
        if (!lore.isEmpty()) meta.lore(lore);
        it.setItemMeta(meta);
        return it;
    }

    private static void put(Holder h, int slot, ItemStack it, BiConsumer<Player, ClickType> action) {
        h.inv.setItem(slot, it);
        h.actions.put(slot, action);
    }

    private static String fmt(double v) {
        if (v == Math.rint(v)) return String.valueOf((long) v);
        return String.format(Locale.ROOT, "%.2f", v).replaceAll("0+$", "").replaceAll("\\.$", "");
    }

    // ============================================================
    //  クリックの処理
    // ============================================================

    @EventHandler
    public void onClick(InventoryClickEvent e) {
        if (!(e.getView().getTopInventory().getHolder() instanceof Holder h)) return;
        e.setCancelled(true);                                   // メニューの中のアイテムは動かせない
        if (!(e.getWhoClicked() instanceof Player p)) return;
        if (e.getClickedInventory() != e.getView().getTopInventory()) return;
        if (!p.hasPermission("slopeclimb.admin")) {
            p.closeInventory();
            return;
        }
        BiConsumer<Player, ClickType> action = h.actions.get(e.getSlot());
        if (action != null) action.accept(p, e.getClick());
    }

    @EventHandler
    public void onDrag(InventoryDragEvent e) {
        if (e.getView().getTopInventory().getHolder() instanceof Holder) e.setCancelled(true);
    }
}
