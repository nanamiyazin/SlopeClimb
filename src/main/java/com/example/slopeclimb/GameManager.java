package com.example.slopeclimb;

import io.papermc.paper.entity.TeleportFlag;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.title.Title;
import org.bukkit.Bukkit;
import org.bukkit.Color;
import org.bukkit.FireworkEffect;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Particle;
import org.bukkit.Registry;
import org.bukkit.Sound;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.block.BlockFace;
import org.bukkit.block.data.BlockData;
import org.bukkit.boss.BossBar;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.Ageable;
import org.bukkit.entity.ArmorStand;
import org.bukkit.entity.Boss;
import org.bukkit.entity.Entity;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.Firework;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Minecart;
import org.bukkit.entity.Player;
import org.bukkit.inventory.meta.FireworkMeta;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.util.BoundingBox;
import org.bukkit.util.Vector;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collection;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

public final class GameManager {

    public enum Role { ENEMY, ALLY }

    /** seconds = 押す時間(秒)、power = 押す力(競合時に強い方が勝つ)、sureHit = 必中 */
    public static final class MobInfo {
        private final Role role;
        private final double seconds;
        private final int power;
        private final MobSpec spec;
        final double speed;       // 1tickあたりのマス数
        final boolean sureHit;    // 必中（横にずれていても、通過すれば必ず接触する）
        double dirT;              // 進む向き（+1 = ゴール側へ / -1 = スタート側へ）。横にはずれず、まっすぐ進む
        final double dirS = 0;
        boolean riding = false;   // 押している最中（プレイヤーについていく状態）

        MobInfo(Role role, double seconds, int power, MobSpec spec, double speed, boolean sureHit) {
            this.role = role;
            this.seconds = seconds;
            this.power = power;
            this.spec = spec;
            this.speed = speed;
            this.sureHit = sureHit;
        }

        public Role role() { return role; }
        public double seconds() { return seconds; }
        public int power() { return power; }
        public MobSpec spec() { return spec; }
    }

    private record Force(int enemyPower, double enemySec, int allyPower, double allySec) {}

    private static final class Push {
        final int sign;          // -1 = 坂の下へ押される(敵) / +1 = 坂の上へ押される(味方)
        double total;            // 押される総距離(マス)。壁で動けなくても、この距離分はそのまま押し続ける
        final double speed;
        final boolean back;
        final List<UUID> riders; // 一緒に押されている mob（勝った側も負けた側も）
        int power;               // 今押している側の押す力
        double moved = 0;
        int played = 0;
        double pressed = 0;      // 壁に押し付けられた距離
        int pressedCounted = 0;

        Push(int sign, double total, double speed, boolean back, List<UUID> riders, int power) {
            this.sign = sign;
            this.total = total;
            this.speed = speed;
            this.back = back;
            this.riders = riders;
            this.power = power;
        }
    }

    private static final class Cinematic {
        final Player player;
        final Location origLoc;
        final GameMode origMode;
        final ArmorStand camera;
        final LivingEntity boss;
        final Vector fwd;     // ボスが進む方向（水平・正規化）
        final long start;
        final long maxTicks;
        Vector cam = null;

        Cinematic(Player player, Location origLoc, GameMode origMode, ArmorStand camera,
                  LivingEntity boss, Vector fwd, long start, long maxTicks) {
            this.player = player;
            this.origLoc = origLoc;
            this.origMode = origMode;
            this.camera = camera;
            this.boss = boss;
            this.fwd = fwd;
            this.start = start;
            this.maxTicks = maxTicks;
        }
    }

    private static final double[] LANES = {0, -1, 1};

    private final SlopeClimbPlugin plugin;
    private Course course;
    private boolean busy = false;
    private long tick = 0;
    private boolean internalMove = false;

    private final Map<UUID, MobInfo> mobs = new HashMap<>();
    private final Map<UUID, Push> pushes = new HashMap<>();
    private final Map<UUID, Cinematic> cinematics = new HashMap<>();
    private final Map<UUID, Long> immuneUntil = new HashMap<>();
    private final Map<UUID, Long> startTimes = new HashMap<>();
    private final Map<UUID, Long> holdSince = new HashMap<>();
    private final Set<UUID> finished = new HashSet<>();
    private final Map<UUID, Double> gauge = new HashMap<>();
    private final Map<UUID, net.kyori.adventure.bossbar.BossBar> gaugeBars = new HashMap<>();
    private final Map<UUID, Long> gaugeResetAt = new HashMap<>();   // ゲージが 0 になった後、満タンに戻す時刻(tick)
    private final Map<UUID, net.kyori.adventure.bossbar.BossBar> distBars = new HashMap<>();
    private final Map<UUID, UUID> carts = new HashMap<>();          // mob の UUID → 乗っているトロッコの UUID
    private final Map<UUID, Long> forceHold = new HashMap<>();      // スコアボードの「力」の表示を残す期限(tick)
    private final Set<UUID> dying = new HashSet<>();                // 倒されて死亡アニメーション中の mob

    private final Records records;

    public GameManager(SlopeClimbPlugin plugin) {
        this.plugin = plugin;
        this.records = new Records(plugin);
        this.records.enable();
    }

    public Records records() { return records; }

    private FileConfiguration cfg() { return plugin.getConfig(); }

    public Course getCourse() { return course; }
    public int mobCount() { return mobs.size(); }
    public boolean isBusy() { return busy; }
    public boolean isInternalMove() { return internalMove; }

    public boolean isSlopeMob(Entity e) { return e != null && mobs.containsKey(e.getUniqueId()); }

    public Role roleOf(Entity e) {
        MobInfo mi = e == null ? null : mobs.get(e.getUniqueId());
        return mi == null ? null : mi.role();
    }

    private static String mobsKey(Role role) {
        return role == Role.ENEMY ? "enemy-mobs" : "ally-mobs";
    }

    /** config の enemy-mobs / ally-mobs に書かれている mob 名（旧形式のリストにも対応） */
    public List<String> allowedMobs(Role role) {
        ConfigurationSection sec = cfg().getConfigurationSection(mobsKey(role));
        if (sec != null) return new ArrayList<>(sec.getKeys(false));
        return cfg().getStringList(mobsKey(role));
    }

    /** mob ごとの設定値（なければ mob-defaults） */
    private double mobDouble(Role role, MobSpec spec, String field, double fallback) {
        String path = mobsKey(role) + "." + spec.key() + "." + field;
        if (cfg().isSet(path)) return cfg().getDouble(path, fallback);
        return cfg().getDouble("mob-defaults." + field, fallback);
    }

    private boolean mobBool(Role role, MobSpec spec, String field, boolean fallback) {
        String path = mobsKey(role) + "." + spec.key() + "." + field;
        if (cfg().isSet(path)) return cfg().getBoolean(path, fallback);
        return cfg().getBoolean("mob-defaults." + field, fallback);
    }

    /** 許可リストにある mob のみ MobSpec を返す。無ければ null */
    public MobSpec resolveSpec(String name, Role role) {
        String n = name.toLowerCase(Locale.ROOT);
        if (!allowedMobs(role).contains(n)) return null;
        MobSpec spec = MobSpec.of(n, cfg().getDouble("giant-scale", 3.0));
        EntityType type = entityType(spec);
        if (type == null) return null;
        Class<?> cls = type.getEntityClass();
        if (cls == null || !LivingEntity.class.isAssignableFrom(cls)) return null;
        return spec;
    }

    private EntityType entityType(MobSpec spec) {
        return Registry.ENTITY_TYPE.get(NamespacedKey.minecraft(spec.entityKey()));
    }

    private static String fmtSec(double v) {
        return v == Math.rint(v) ? String.valueOf((long) v) : String.format(Locale.ROOT, "%.1f", v);
    }

    private static double clamp(double v, double lo, double hi) {
        return Math.max(lo, Math.min(hi, v));
    }

    /** mob を動かす。トロッコに乗っていれば、乗せたままトロッコごと動かす */
    private void moveMob(LivingEntity le, Location dest) {
        internalMove = true;
        try {
            Entity cart = cartOf(le.getUniqueId());
            if (cart != null && cart.isValid() && le.getVehicle() == cart) {
                cart.teleport(dest, TeleportFlag.EntityState.RETAIN_PASSENGERS);
                le.setRotation(dest.getYaw(), 0f);
            } else {
                le.teleport(dest);
            }
        } finally {
            internalMove = false;
        }
    }

    private Entity cartOf(UUID mobId) {
        UUID cid = carts.get(mobId);
        return cid == null ? null : Bukkit.getEntity(cid);
    }

    public boolean isCart(Entity e) {
        return e != null && carts.containsValue(e.getUniqueId());
    }

    public boolean isDying(Entity e) {
        return e != null && dying.contains(e.getUniqueId());
    }

    /** 弱い側の mob を倒す。死亡のアニメーションと音を出して消える（ドロップや経験値はなし） */
    private void killMobs(List<Entity> list) {
        for (Entity e : list) killMob(e);
    }

    private void killMob(Entity e) {
        UUID id = e.getUniqueId();
        mobs.remove(id);
        Entity cart = cartOf(id);
        carts.remove(id);
        if (cart != null) cart.remove();

        Location l = e.getLocation().add(0, e.getHeight() / 2, 0);
        e.getWorld().spawnParticle(Particle.POOF, l, 16, 0.3, 0.3, 0.3, 0.03);
        if (e instanceof LivingEntity le && le.isValid()) {
            dying.add(id);
            le.setInvulnerable(false);
            le.setAI(false);
            le.setHealth(0.0);   // 死亡（ドロップは MobListener で消す）
            // 念のため、少し待っても残っていたら確実に消す
            Bukkit.getScheduler().runTaskLater(plugin, () -> {
                Entity x = Bukkit.getEntity(id);
                if (x != null) x.remove();
                dying.remove(id);
            }, 40L);
        } else {
            e.remove();
        }
    }

    /** mob を消す（乗っているトロッコも一緒に） */
    private void discardMob(UUID mobId) {
        Entity cart = cartOf(mobId);
        carts.remove(mobId);
        Entity mob = Bukkit.getEntity(mobId);
        if (mob != null) mob.remove();
        if (cart != null) cart.remove();
    }

    // ---------- コース生成 / 削除 ----------

    private Material material(String key, Material def) {
        Material m = Material.matchMaterial(cfg().getString("materials." + key, def.name()));
        return (m != null && m.isBlock()) ? m : def;
    }

    /** 素材名を検証して返す。使えない素材なら null（stair は階段、それ以外は落下しない完全なブロック） */
    public Material parseMaterial(String kind, String name) {
        Material m = Material.matchMaterial(name);
        if (m == null || m.isLegacy() || !m.isBlock()) return null;
        return (m.isSolid() && m.isOccluding() && !m.hasGravity()) ? m : null;
    }

    /** タブ補完用: 使える素材名（小文字）を最大 30 件 */
    public List<String> suggestMaterials(String kind, String prefix) {
        List<String> out = new ArrayList<>();
        String pre = prefix.toLowerCase(Locale.ROOT);
        for (Material m : Material.values()) {
            if (out.size() >= 30) break;
            String n = m.name().toLowerCase(Locale.ROOT);
            if (!n.startsWith(pre)) continue;
            if (parseMaterial(kind, n) != null) out.add(n);
        }
        return out;
    }

    /** 素材設定を config.yml に保存する。@return エラーメッセージ。成功時は null */
    public String setMaterial(String kind, String name) {
        if (!List.of("floor", "fill", "pad", "goal", "wall").contains(kind)) {
            return "種類は floor / fill / pad / goal / wall のいずれかです。";
        }
        Material m = parseMaterial(kind, name);
        if (m == null) {
            return "使えない素材です。落下せず、完全な立方体のブロックを指定してください。";
        }
        cfg().set("materials." + kind, m.name());
        plugin.saveConfig();
        return null;
    }

    /** @return エラーメッセージ。成功時は null */
    public String create(Player creator, int length, Material floorMat) {
        if (busy) return "処理中です。しばらく待ってください。";
        if (course != null) return "既にコースがあります。先に /koth remove を実行してください。";

        Location pl = creator.getLocation();
        BlockFace dir = facingOf(pl.getYaw());
        int baseY = pl.getBlockY();
        int x0 = pl.getBlockX() + dir.getModX();
        int z0 = pl.getBlockZ() + dir.getModZ();

        if (baseY + Course.WALL_HEIGHT + 4 >= pl.getWorld().getMaxHeight()) {
            return "高さ制限を超えます。低い場所で実行してください。";
        }
        if (baseY - 2 <= pl.getWorld().getMinHeight()) {
            return "低すぎる場所です。";
        }

        Course c = new Course(pl.getWorld(), x0, baseY, z0, dir, length);

        BlockData floor = (floorMat != null ? floorMat : material("floor", Material.STONE_BRICKS)).createBlockData();
        BlockData fill = material("fill", Material.STONE_BRICKS).createBlockData();
        BlockData pad = material("pad", Material.SMOOTH_STONE).createBlockData();
        BlockData goal = material("goal", Material.GOLD_BLOCK).createBlockData();
        BlockData wall = material("wall", Material.STONE_BRICKS).createBlockData();

        course = c;
        busy = true;
        creator.sendMessage(Component.text("コースを生成中...", NamedTextColor.YELLOW));
        runBatched(c.buildJobs(floor, fill, pad, goal, wall).iterator(), () -> {
            c.ready = true;
            busy = false;
            if (creator.isOnline()) {
                creator.sendMessage(Component.text("コース完成！ 方向=" + dir + " / 幅=" + Course.WIDTH
                        + " / 長さ=" + length + "マス（奥がゴール）", NamedTextColor.GREEN));
            }
        });
        return null;
    }

    public String remove() {
        if (busy) return "処理中です。しばらく待ってください。";
        if (course == null) return "コースがありません。";
        Course c = course;
        endAllCinematics();
        clearMobs(null);
        for (UUID id : new ArrayList<>(pushes.keySet())) endPush(id);
        hideAllGauges();
        hideAllDist();
        endAllBuffs();
        startTimes.clear();
        finished.clear();
        holdSince.clear();
        c.ready = false;
        busy = true;
        runBatched(c.restoreJobs().iterator(), () -> {
            c.clearBackups();
            if (course == c) course = null;
            busy = false;
            Bukkit.getConsoleSender().sendMessage("[SlopeClimb] コースを元に戻しました。");
        });
        return null;
    }

    public void shutdown() {
        records.disable();
        endAllCinematics();
        hideAllGauges();
        hideAllDist();
        endAllBuffs();
        clearMobs(null);
        if (course != null && course.ready && cfg().getBoolean("restore-on-disable", true)) {
            for (Runnable r : course.restoreJobs()) r.run();
            course.clearBackups();
        }
        course = null;
    }

    private void runBatched(Iterator<Runnable> it, Runnable done) {
        new BukkitRunnable() {
            @Override
            public void run() {
                long end = System.nanoTime() + 20_000_000L; // 1tickあたり最大約20ms
                while (it.hasNext() && System.nanoTime() < end) {
                    it.next().run();
                }
                if (!it.hasNext()) {
                    cancel();
                    done.run();
                }
            }
        }.runTaskTimer(plugin, 1L, 1L);
    }

    private static BlockFace facingOf(float yawRaw) {
        float yaw = ((yawRaw % 360) + 360) % 360;
        return switch (Math.round(yaw / 90f) % 4) {
            case 0 -> BlockFace.SOUTH;
            case 1 -> BlockFace.WEST;
            case 2 -> BlockFace.NORTH;
            default -> BlockFace.EAST;
        };
    }

    // ---------- Mob ----------

    /** プレイヤーの (t, s) から dist マス離れたランダムな地点 {t, s}（敵=坂の上側、味方=坂の下側） */
    private double[] spawnPoint(Course c, double pt, double ps, boolean front, double dist, boolean free) {
        ThreadLocalRandom r = ThreadLocalRandom.current();
        double s = r.nextDouble(-Course.MOB_S, Course.MOB_S);
        double dl = s - ps;
        double dt = Math.sqrt(Math.max(1.0, dist * dist - dl * dl));   // 直線距離がちょうど dist になる
        double t = front ? pt + dt : pt - dt;
        if (!free) t = clamp(t, -2.0, c.length + Course.PLATFORM - 0.5);
        return new double[]{t, s};
    }

    /** @return メッセージ */
    public String spawn(Role role, MobSpec spec, int count, Player anchor) {
        Course c = course;
        if (c == null || !c.ready) return "コースがまだありません（生成中の可能性もあります）。";
        EntityType type = entityType(spec);
        if (type == null) return "この mob は使用できません。";

        Player ap = null;
        if (anchor != null && anchor.getGameMode() != GameMode.SPECTATOR && c.contains(anchor.getLocation())) {
            ap = anchor;
        } else {
            List<Player> ps = participants(c);
            if (!ps.isEmpty()) ap = ps.get(0);
        }
        if (ap == null) return "コース上にプレイヤーがいません。コースの上に立ってから実行してください。";

        double pt = c.t(ap.getLocation());
        double psS = clamp(c.s(ap.getLocation()), -Course.PLAYER_S, Course.PLAYER_S);

        boolean intro = spec.intro() && cfg().getBoolean("intro.enabled", true)
                && !cinematics.containsKey(ap.getUniqueId());
        double dist = intro ? cfg().getDouble("intro.distance", 30.0) : cfg().getDouble("spawn-distance", 10.0);
        boolean front = role == Role.ENEMY;   // 敵はゴール側から、味方はスタート側から近づく
        // 押す時間・押す力・速さ・必中は config の mob 設定から読む
        double seconds = mobDouble(role, spec, "seconds", 2.0);
        int power = (int) Math.round(mobDouble(role, spec, "power", 5));
        double speedPs = mobDouble(role, spec, "speed", 4.0);
        boolean sureHit = mobBool(role, spec, "hit", true);

        int spawned = 0;
        int noCart = 0;
        LivingEntity firstBoss = null;
        MobInfo firstInfo = null;
        for (int i = 0; i < count; i++) {
            boolean free = intro && spec.move() == MobSpec.Move.FLY;
            double[] pos = spawnPoint(c, pt, psS, front, dist, free);
            double t = pos[0], s = pos[1];

            // トロッコに乗って、出現したレーンをまっすぐ進む（敵=スタート側へ / 味方=ゴール側へ）
            MobInfo mi = new MobInfo(role, seconds, power, spec, speedPs / 20.0, sureHit);
            mi.dirT = front ? -1 : 1;

            Location loc = c.at(t, s);
            loc.setYaw((float) Math.toDegrees(Math.atan2(-mi.dirT * c.dx, mi.dirT * c.dz)) + spec.yawOffset());
            loc.setPitch(0f);

            Entity ent = c.world.spawnEntity(loc, type);
            if (!(ent instanceof LivingEntity le)) {
                ent.remove();
                continue;
            }
            le.setInvulnerable(true);
            le.setPersistent(false);
            le.setRemoveWhenFarAway(false);
            le.setCanPickupItems(false);
            le.setCollidable(false);
            le.setAI(false);          // 直線移動のみ（AI・経路探索は使わない）
            le.setGravity(false);
            if (le instanceof Ageable a) a.setAdult();
            if (spec.scale() != 1.0) {
                AttributeInstance ai = le.getAttribute(Attribute.SCALE);
                if (ai != null) ai.setBaseValue(spec.scale());
            }
            if (spec.silent()) le.setSilent(true);
            if (le instanceof Boss boss) {
                BossBar bar = boss.getBossBar();
                if (bar != null) bar.setVisible(false);
            }
            le.customName(Component.text(
                    (role == Role.ENEMY ? "敵" : "味方") + " 力" + power + " / " + fmtSec(seconds) + "秒"
                            + (sureHit ? " [必中]" : ""),
                    role == Role.ENEMY ? NamedTextColor.RED : NamedTextColor.GREEN));
            le.setCustomNameVisible(true);

            mobs.put(le.getUniqueId(), mi);

            // トロッコに乗せる（乗れない mob はトロッコなしでそのまま進む）
            Minecart cart = c.world.spawn(loc, Minecart.class, m -> {
                m.setInvulnerable(true);
                m.setPersistent(false);
                m.setGravity(false);
                m.setSilent(true);
                m.setSlowWhenEmpty(false);
            });
            if (cart.addPassenger(le)) {
                carts.put(le.getUniqueId(), cart.getUniqueId());
            } else {
                cart.remove();
                noCart++;
            }
            if (firstBoss == null) {
                firstBoss = le;
                firstInfo = mi;
            }
            spawned++;
        }

        if (intro && firstBoss != null) {
            startCinematic(c, ap, firstBoss, firstInfo);
        }

        return (role == Role.ENEMY ? "敵" : "味方") + " " + spec.key() + " を " + spawned
                + " 体召喚しました（押す時間 " + fmtSec(seconds) + "秒 / 押す力 " + power
                + " / 速さ " + fmtSec(speedPs) + "マス/秒 / " + (sureHit ? "必中" : "通常接触") + "）"
                + (noCart > 0 ? "［" + noCart + " 体はトロッコに乗れませんでした］" : "");
    }

    public int clearMobs(Role only) {
        int n = 0;
        Iterator<Map.Entry<UUID, MobInfo>> it = mobs.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<UUID, MobInfo> e = it.next();
            if (only != null && e.getValue().role() != only) continue;
            discardMob(e.getKey());
            it.remove();
            n++;
        }
        return n;
    }

    // ---------- 強化（バフ） ----------

    private final Map<Role, Long> buffUntil = new EnumMap<>(Role.class);
    private final Map<Role, Long> buffLen = new EnumMap<>(Role.class);
    private final Map<Role, net.kyori.adventure.bossbar.BossBar> buffBars = new EnumMap<>(Role.class);

    private static String roleKey(Role role) {
        return role == Role.ENEMY ? "enemy" : "ally";
    }

    private static String roleName(Role role) {
        return role == Role.ENEMY ? "敵" : "味方";
    }

    /** 強化中なら config の倍率、そうでなければ 1.0 */
    private double buffMult(Role role, String key) {
        if (!buffUntil.containsKey(role)) return 1.0;
        return cfg().getDouble("buff." + roleKey(role) + "." + key, 1.0);
    }

    private BlockData buffBlockData(Role role) {
        Material def = role == Role.ENEMY ? Material.SOUL_SAND : Material.ICE;
        Material m = Material.matchMaterial(cfg().getString("buff." + roleKey(role) + ".block", def.name()));
        return ((m != null && m.isBlock()) ? m : def).createBlockData();
    }

    /** 敵 / 味方の強化を始める（強化中にもう一度実行すると時間がリセットされる）。@return エラーメッセージ。成功時は null */
    public String buff(Role role) {
        Course c = course;
        if (c == null || !c.ready) return "コースがまだありません。";
        long len = Math.max(20L, Math.round(cfg().getDouble("buff.duration-seconds", 30.0) * 20));
        boolean already = buffUntil.containsKey(role);
        buffUntil.put(role, tick + len);
        buffLen.put(role, len);

        String name = roleName(role);
        if (!already) {
            // 味方=進行方向に向かって左半分が氷 / 敵=右半分がソウルサンド
            c.setHalf(role == Role.ALLY, buffBlockData(role));
            net.kyori.adventure.bossbar.BossBar bar = net.kyori.adventure.bossbar.BossBar.bossBar(
                    Component.text(name + "強化中", role == Role.ENEMY ? NamedTextColor.RED : NamedTextColor.GREEN), 1f,
                    role == Role.ENEMY ? net.kyori.adventure.bossbar.BossBar.Color.RED
                            : net.kyori.adventure.bossbar.BossBar.Color.GREEN,
                    net.kyori.adventure.bossbar.BossBar.Overlay.PROGRESS);
            buffBars.put(role, bar);
            for (Player p : c.world.getPlayers()) p.showBossBar(bar);
            Bukkit.broadcast(Component.text("⚡ " + name + "の mob が強化された！（" + (len / 20) + "秒）"
                    + (role == Role.ENEMY ? " 道の右半分がソウルサンドに" : " 道の左半分が氷に"),
                    role == Role.ENEMY ? NamedTextColor.RED : NamedTextColor.GREEN));
        } else {
            Bukkit.broadcast(Component.text("⚡ " + name + "の強化を延長！（" + (len / 20) + "秒）",
                    role == Role.ENEMY ? NamedTextColor.RED : NamedTextColor.GREEN));
        }
        return null;
    }

    /** 強化をすべて終わらせる。@return エラーメッセージ。成功時は null */
    public String buffOff() {
        if (buffUntil.isEmpty()) return "強化中ではありません。";
        for (Role role : new ArrayList<>(buffUntil.keySet())) endBuff(role, true);
        return null;
    }

    private void endAllBuffs() {
        for (Role role : new ArrayList<>(buffUntil.keySet())) endBuff(role, false);
    }

    private void endBuff(Role role, boolean announce) {
        if (buffUntil.remove(role) == null) return;
        buffLen.remove(role);
        net.kyori.adventure.bossbar.BossBar bar = buffBars.remove(role);
        if (bar != null) {
            for (Player p : Bukkit.getOnlinePlayers()) p.hideBossBar(bar);
        }
        Course c = course;
        if (c != null && c.ready) c.setHalf(role == Role.ALLY, c.blockData());   // 元のブロックに戻す
        if (announce) {
            Bukkit.broadcast(Component.text("⚡ " + roleName(role) + "の強化が終わった", NamedTextColor.GRAY));
        }
    }

    private void tickBuffs() {
        if (buffUntil.isEmpty()) return;
        for (Role role : new ArrayList<>(buffUntil.keySet())) {
            long remain = buffUntil.get(role) - tick;
            if (remain <= 0) {
                endBuff(role, true);
                continue;
            }
            net.kyori.adventure.bossbar.BossBar bar = buffBars.get(role);
            if (bar != null) {
                long len = buffLen.getOrDefault(role, remain);
                bar.progress((float) clamp(remain / (double) len, 0, 1));
                bar.name(Component.text(roleName(role) + "強化中  あと " + ((remain + 19) / 20) + " 秒",
                        role == Role.ENEMY ? NamedTextColor.RED : NamedTextColor.GREEN));
            }
        }
    }

    // ---------- 乱入演出（カメラワーク） ----------

    private void startCinematic(Course c, Player p, LivingEntity boss, MobInfo mi) {
        UUID id = p.getUniqueId();
        if (cinematics.containsKey(id)) return;
        if (pushes.containsKey(id)) endPush(id);

        Location orig = p.getLocation().clone();
        GameMode mode = p.getGameMode();

        ArmorStand cam = c.world.spawn(boss.getLocation(), ArmorStand.class, a -> {
            a.setInvisible(true);
            a.setMarker(true);
            a.setGravity(false);
            a.setInvulnerable(true);
            a.setPersistent(false);
            a.setSilent(true);
        });

        Vector fwd = new Vector(mi.dirT * c.dx + mi.dirS * c.lx, 0, mi.dirT * c.dz + mi.dirS * c.lz);
        if (fwd.lengthSquared() < 1e-6) fwd = new Vector(-c.dx, 0, -c.dz);
        fwd.normalize();

        long max = cfg().getLong("intro.max-ticks", 200L);
        Cinematic cin = new Cinematic(p, orig, mode, cam, boss, fwd, tick, max);
        cinematics.put(id, cin);

        p.setGameMode(GameMode.SPECTATOR);
        p.setSpectatorTarget(cam);

        MobSpec spec = mi.spec();
        p.showTitle(Title.title(
                Component.text("⚠ " + spec.jpName() + " 乱入！", NamedTextColor.RED),
                Component.text("遠くからこちらへ向かってくる…", NamedTextColor.GRAY),
                Title.Times.times(Duration.ofMillis(200), Duration.ofMillis(2200), Duration.ofMillis(500))));
        boss.getWorld().playSound(boss.getLocation(), spec.introSound(), 4f, 1f);
        tickCinematic(cin);
    }

    private void tickCinematics() {
        if (cinematics.isEmpty()) return;
        for (Cinematic cin : new ArrayList<>(cinematics.values())) {
            if (!tickCinematic(cin)) endCinematic(cin);
        }
    }

    /** @return 演出を続けるなら true */
    private boolean tickCinematic(Cinematic cin) {
        Player p = cin.player;
        if (!p.isOnline() || !cin.boss.isValid() || !cin.camera.isValid()) return false;

        long e = tick - cin.start;
        Location bl = cin.boss.getLocation().add(0, cin.boss.getHeight() * 0.6, 0);
        Location pl = cin.origLoc;
        double distToPlayer = Math.hypot(bl.getX() - pl.getX(), bl.getZ() - pl.getZ());
        if (e >= cin.maxTicks || distToPlayer < 7.0) return false;

        if (p.getGameMode() != GameMode.SPECTATOR) p.setGameMode(GameMode.SPECTATOR);
        if (p.getSpectatorTarget() != cin.camera) p.setSpectatorTarget(cin.camera);

        Vector fwd = cin.fwd;
        Vector side = new Vector(-fwd.getZ(), 0, fwd.getX());
        Vector desired;
        if (distToPlayer < 20.0) {
            // 迫ってくるボスをプレイヤーの背後から見る
            desired = pl.toVector().add(fwd.clone().multiply(4)).add(new Vector(0, 3, 0));
        } else if (e < 50) {
            // 登場シーン: ボスの正面斜め前から見上げる
            desired = bl.toVector().add(fwd.clone().multiply(15)).add(side.clone().multiply(7)).add(new Vector(0, 4, 0));
        } else {
            // 並走ショット: ボスの横について行く
            desired = bl.toVector().add(side.clone().multiply(11)).add(new Vector(0, 3, 0));
        }
        if (cin.cam == null) {
            cin.cam = desired.clone();
        } else {
            cin.cam.add(desired.clone().subtract(cin.cam).multiply(0.12));
        }

        Location cl = new Location(cin.boss.getWorld(), cin.cam.getX(),
                cin.cam.getY() - cin.camera.getEyeHeight(), cin.cam.getZ());
        cl.setDirection(bl.toVector().subtract(cin.cam));
        cin.camera.teleport(cl);
        return true;
    }

    private void endCinematic(Cinematic cin) {
        cinematics.remove(cin.player.getUniqueId());
        Player p = cin.player;
        cin.camera.remove();
        if (p.isOnline()) {
            p.setSpectatorTarget(null);
            p.setGameMode(cin.origMode);
            p.teleport(cin.origLoc);
        }
    }

    public void endCinematicFor(Player p) {
        Cinematic cin = cinematics.get(p.getUniqueId());
        if (cin != null) endCinematic(cin);
    }

    public void endAllCinematics() {
        for (Cinematic cin : new ArrayList<>(cinematics.values())) endCinematic(cin);
    }

    // ---------- メインループ ----------

    private List<Player> participants(Course c) {
        List<Player> list = new ArrayList<>();
        for (Player p : c.world.getPlayers()) {
            if (p.getGameMode() == GameMode.SPECTATOR || p.isDead()) continue;
            if (c.contains(p.getLocation())) list.add(p);
        }
        return list;
    }

    private static Player nearest(List<Player> ps, Location from) {
        Player best = null;
        double bd = Double.MAX_VALUE;
        for (Player p : ps) {
            double d = p.getLocation().distanceSquared(from);
            if (d < bd) { bd = d; best = p; }
        }
        return best;
    }

    public void tick() {
        tick++;
        tickCinematics();
        tickBuffs();
        Course c = course;
        if (c == null || !c.ready) return;

        List<Player> ps = participants(c);

        // mob の移動（直線のみ）
        Iterator<Map.Entry<UUID, MobInfo>> it = mobs.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<UUID, MobInfo> e = it.next();
            Entity ent = Bukkit.getEntity(e.getKey());
            if (!(ent instanceof LivingEntity le) || !le.isValid()) {
                discardMob(e.getKey());
                it.remove();
                continue;
            }
            MobInfo mi = e.getValue();
            if (mi.riding) continue;   // 押している最中はプレイヤーについていく
            if (moveStraight(c, le, mi)) {
                discardMob(e.getKey());
                it.remove();
            }
        }

        // プレイヤー処理
        Set<UUID> active = new HashSet<>();
        for (Player p : ps) {
            active.add(p.getUniqueId());
            processPlayer(c, p);
        }
        tickGauges();
        updateDistanceBars(c, ps);
        // コース外に出たプレイヤーの記録を消す
        for (Player p : Bukkit.getOnlinePlayers()) {
            UUID id = p.getUniqueId();
            if (!active.contains(id) && !cinematics.containsKey(id)) {
                startTimes.remove(id);
                finished.remove(id);
                holdSince.remove(id);
                endPush(id);
                hideGauge(p);
                hideDist(p);
                records.hide(p);
            }
        }
    }

    /** mob をトロッコに乗せたまま、自分のレーンをまっすぐ動かす。コースの外へ抜けたら true（削除） */
    private boolean moveStraight(Course c, LivingEntity ent, MobInfo mi) {
        MobSpec spec = mi.spec();
        Location ml = ent.getLocation();
        double t = c.t(ml), s = c.s(ml);

        double sp = mi.speed * buffMult(mi.role(), "speed-multiplier");
        double nt = t + mi.dirT * sp;

        double lo = -Course.PAD - 0.5;
        double hi = c.length + Course.PLATFORM + 0.5;
        if ((nt > hi && mi.dirT > 0) || (nt < lo && mi.dirT < 0)) return true;

        Location dest = c.at(nt, s);
        dest.setYaw((float) Math.toDegrees(Math.atan2(-mi.dirT * c.dx, mi.dirT * c.dz)) + spec.yawOffset());
        dest.setPitch(0f);
        moveMob(ent, dest);
        return false;
    }

    private void processPlayer(Course c, Player p) {
        UUID id = p.getUniqueId();
        records.touch(p);
        Location loc = p.getLocation();
        double t = c.t(loc);

        if (t >= 0) startTimes.putIfAbsent(id, System.currentTimeMillis());

        // ゴールキープ判定（ゴールの台に一定時間とどまれば勝ち）
        long holdTicks = Math.round(cfg().getDouble("summit-hold-seconds", 15.0) * 20);
        if (c.onSummit(loc)) {
            if (!finished.contains(id)) {
                long since = holdSince.computeIfAbsent(id, k -> tick);
                long elapsed = tick - since;
                if (elapsed >= holdTicks) {
                    finished.add(id);
                    holdSince.remove(id);
                    win(c, p);
                    return;
                } else if (elapsed % 20 == 0) {
                    long remain = (holdTicks - elapsed + 19) / 20;
                    p.sendActionBar(Component.text("ゴールキープ中！ あと " + remain + " 秒", NamedTextColor.GOLD));
                    p.playSound(p.getLocation(), Sound.BLOCK_NOTE_BLOCK_HAT, 0.8f, 1.5f);
                }
            }
        } else {
            if (holdSince.remove(id) != null) {
                p.sendActionBar(Component.text("ゴールから離れた！ カウントリセット", NamedTextColor.RED));
            }
            if (t < c.length - 1) finished.remove(id);
        }

        // 押し合いが終わって少したったら、スコアボードの「力」の表示を 0 に戻す
        if (!pushes.containsKey(id) && tick >= forceHold.getOrDefault(id, 0L)) records.setForces(p, 0, 0);

        Push push = pushes.get(id);
        if (push == null && tick < immuneUntil.getOrDefault(id, 0L)) return;

        List<Entity> touching = findTouching(c, p);

        // 押されている最中でも、新しく触れた mob は押しに加わる（押し返しもできる）
        if (push != null) {
            if (!touching.isEmpty()) joinPush(p, push, touching);
            Push cur = pushes.get(id);
            if (cur != null) stepPush(c, p, cur);
            return;
        }

        if (touching.isEmpty()) return;

        Force f = forceOf(infosOf(touching));
        showForces(p, f);
        boolean back;
        double sec;
        int power;
        if (f.enemyPower() > f.allyPower()) {
            back = true;
            sec = f.enemySec();
            power = f.enemyPower();
        } else if (f.allyPower() > f.enemyPower()) {
            back = false;
            sec = f.allySec();
            power = f.allyPower();
        } else {
            // 力が同じ → 押し合い互角（誰も動かず、mob も消えない。そのまま直進を続ける）
            p.sendActionBar(Component.text("押し合い互角！", NamedTextColor.YELLOW));
            forceHold.put(id, tick + 40);
            immuneUntil.put(id, tick + 20);
            return;
        }

        // 強い側の mob は押している間プレイヤーについていく。弱い側の mob は倒されて消える
        Role winRole = back ? Role.ENEMY : Role.ALLY;
        List<UUID> riders = new ArrayList<>();
        List<Entity> losers = new ArrayList<>();
        for (Entity ent : touching) {
            MobInfo mi = mobs.get(ent.getUniqueId());
            if (mi == null) continue;
            if (mi.role() == winRole) {
                mi.riding = true;
                riders.add(ent.getUniqueId());
            } else {
                losers.add(ent);
            }
        }
        killMobs(losers);

        // 押す時間(秒) × push-speed(1tickあたりのマス数) × 20tick = 押される総距離
        double speed = cfg().getDouble("push-speed", 0.18);
        double total = Math.max(speed, sec * 20.0 * speed);
        pushes.put(id, new Push(back ? -1 : 1, total, speed, back, riders, power));
        if (back) {
            p.sendActionBar(Component.text("敵に " + fmtSec(sec) + " 秒押し戻される！（合計力 " + power + "）", NamedTextColor.RED));
        } else {
            p.sendActionBar(Component.text("味方が " + fmtSec(sec) + " 秒押してくれる！（合計力 " + power + "）", NamedTextColor.GREEN));
        }
    }

    /** スコアボードに「押す力（味方）」「押し戻す力（敵）」を出す。合計はスコアボード側で足し引きする */
    private void showForces(Player p, Force f) {
        records.setForces(p, Math.max(0, f.allyPower()), Math.max(0, f.enemyPower()));
    }

    /** プレイヤーに接触している（押し中でない）mob を集める */
    private List<Entity> findTouching(Course c, Player p) {
        Location loc = p.getLocation();
        double t = c.t(loc);
        double range = cfg().getDouble("contact-range", 0.25);
        BoundingBox pb = p.getBoundingBox().clone().expand(range);
        List<Entity> out = new ArrayList<>();
        for (Entity ent : p.getNearbyEntities(8, 8, 8)) {
            MobInfo mi = mobs.get(ent.getUniqueId());
            if (mi == null || mi.riding) continue;
            Location el = ent.getLocation();
            boolean hit;
            double cr = mi.spec().contactRadius();
            if (mi.sureHit) {
                // 必中: 横にずれていても、プレイヤーの位置（坂の進行方向）を通過したら必ず接触する
                double tol = Math.max(0.8, cr) + mi.speed * 1.5;
                hit = Math.abs(c.t(el) - t) <= tol && Math.abs(el.getY() - loc.getY()) <= 8;
            } else if (cr > 0) {
                double ddx = el.getX() - loc.getX(), ddz = el.getZ() - loc.getZ();
                hit = ddx * ddx + ddz * ddz <= cr * cr && Math.abs(el.getY() - loc.getY()) <= 6;
            } else {
                Entity cart = cartOf(ent.getUniqueId());
                hit = pb.overlaps(ent.getBoundingBox()) || (cart != null && pb.overlaps(cart.getBoundingBox()));
            }
            if (hit) out.add(ent);
        }
        return out;
    }

    /**
     * 敵側・味方側それぞれの合計の押す力と、押す時間を求める。
     * 同じ種類の mob どうしは足し算しない（種類ごとに最大の力を1つだけ数える）。
     * 違う種類の mob の力は足し算する。例: ゾンビ5 + ゾンビ5 + ウィザー10 = 15
     * 押す時間は、その側で一番力の強い mob のもの（同じ力なら長い方）。
     */
    private Force forceOf(Collection<MobInfo> infos) {
        Map<String, Integer> enemy = new HashMap<>();
        Map<String, Integer> ally = new HashMap<>();
        int eTop = -1, aTop = -1;
        double eSec = 0, aSec = 0;
        for (MobInfo mi : infos) {
            int pw = effPower(mi);
            double sc = effSec(mi);
            if (mi.role() == Role.ENEMY) {
                enemy.merge(mi.spec().key(), pw, Math::max);
                if (pw > eTop || (pw == eTop && sc > eSec)) {
                    eTop = pw;
                    eSec = sc;
                }
            } else {
                ally.merge(mi.spec().key(), pw, Math::max);
                if (pw > aTop || (pw == aTop && sc > aSec)) {
                    aTop = pw;
                    aSec = sc;
                }
            }
        }
        int ep = enemy.isEmpty() ? -1 : enemy.values().stream().mapToInt(Integer::intValue).sum();
        int ap = ally.isEmpty() ? -1 : ally.values().stream().mapToInt(Integer::intValue).sum();
        return new Force(ep, eSec, ap, aSec);
    }

    /** 強化中なら倍率をかけた押す力 / 押す時間 */
    private int effPower(MobInfo mi) {
        return (int) Math.round(mi.power() * buffMult(mi.role(), "power-multiplier"));
    }

    private double effSec(MobInfo mi) {
        return mi.seconds() * buffMult(mi.role(), "seconds-multiplier");
    }

    private List<MobInfo> infosOf(Collection<Entity> entities) {
        List<MobInfo> out = new ArrayList<>();
        for (Entity ent : entities) {
            MobInfo mi = mobs.get(ent.getUniqueId());
            if (mi != null) out.add(mi);
        }
        return out;
    }

    /** 押されている最中に新しく触れた mob の処理（押し返し・互角・加勢）。力は全員分を合計し直す */
    private void joinPush(Player p, Push push, List<Entity> touching) {
        UUID id = p.getUniqueId();

        // これまで一緒に押されている mob + 新しく触れた mob 全員で、力を合計し直す
        List<MobInfo> all = new ArrayList<>();
        for (UUID u : push.riders) {
            MobInfo mi = mobs.get(u);
            if (mi != null) all.add(mi);
        }
        all.addAll(infosOf(touching));
        Force f = forceOf(all);
        showForces(p, f);

        int samePower = push.back ? f.enemyPower() : f.allyPower();
        double sameSec = push.back ? f.enemySec() : f.allySec();
        int oppPower = push.back ? f.allyPower() : f.enemyPower();
        double oppSec = push.back ? f.allySec() : f.enemySec();
        Role sameRole = push.back ? Role.ENEMY : Role.ALLY;   // 今押している側

        // 合計の力が同じ → 互角。押しが止まり、mob は消えずに直進に戻る
        if (oppPower >= 0 && oppPower == samePower) {
            for (MobInfo mi : all) mi.riding = false;
            pushes.remove(id);
            forceHold.put(id, tick + 40);
            p.sendActionBar(Component.text("押し合い互角！（合計力 " + samePower + " 同士）", NamedTextColor.YELLOW));
            immuneUntil.put(id, tick + 20);
            return;
        }

        List<Entity> dead = new ArrayList<>();
        if (oppPower > samePower) {
            // 逆側の合計のほうが強い → 押し返される。今まで押していた mob は倒され、
            // 向きが反転して、押す時間も逆側のものになる
            for (UUID u : push.riders) {
                Entity e = Bukkit.getEntity(u);
                if (e != null) dead.add(e);
                else {
                    mobs.remove(u);
                    discardMob(u);
                }
            }
            List<UUID> newRiders = new ArrayList<>();
            for (Entity ent : touching) {
                MobInfo mi = mobs.get(ent.getUniqueId());
                if (mi == null) continue;
                if (mi.role() == sameRole) {
                    dead.add(ent);
                } else {
                    mi.riding = true;
                    newRiders.add(ent.getUniqueId());
                }
            }
            killMobs(dead);

            boolean newBack = !push.back;
            double total = Math.max(push.speed, oppSec * 20.0 * push.speed);
            pushes.put(id, new Push(newBack ? -1 : 1, total, push.speed, newBack, newRiders, oppPower));
            if (newBack) {
                p.sendActionBar(Component.text("敵に押し返された！（合計力 " + oppPower + " 対 " + samePower + "）", NamedTextColor.RED));
            } else {
                p.sendActionBar(Component.text("味方が押し返した！（合計力 " + oppPower + " 対 " + samePower + "）", NamedTextColor.GREEN));
            }
            p.playSound(p.getLocation(), Sound.ITEM_SHIELD_BLOCK, 1f, 1f);
        } else {
            // 押している側が優勢のまま → 逆側の弱い mob は倒され、同じ側の mob は加勢して一緒に押す
            for (Entity ent : touching) {
                MobInfo mi = mobs.get(ent.getUniqueId());
                if (mi == null) continue;
                if (mi.role() == sameRole) {
                    mi.riding = true;
                    push.riders.add(ent.getUniqueId());
                } else {
                    dead.add(ent);
                }
            }
            killMobs(dead);
            // 押す時間が今より長ければ延長する
            double remaining = push.total - push.moved;
            double extra = sameSec * 20.0 * push.speed;
            if (extra > remaining) push.total = push.moved + extra;
            push.power = samePower;
        }
    }

    /** 接触した mob を消す */
    private void consume(List<Entity> list) {
        for (Entity e : list) {
            mobs.remove(e.getUniqueId());
            Location l = e.getLocation().add(0, e.getHeight() / 2, 0);
            e.getWorld().spawnParticle(Particle.POOF, l, 12, 0.3, 0.3, 0.3, 0.02);
            discardMob(e.getUniqueId());
        }
    }

    /** 押しの終了処理: ついてきていた mob を消す */
    private void endPush(UUID id) {
        Push push = pushes.remove(id);
        if (push == null) return;
        List<Entity> list = new ArrayList<>();
        for (UUID u : push.riders) {
            Entity ent = Bukkit.getEntity(u);
            if (ent != null) list.add(ent);
            else {
                mobs.remove(u);
                discardMob(u);
            }
        }
        consume(list);
    }

    /** 押されている最中の mob をプレイヤーの両脇に付き添わせる（敵=ゴール側、味方=スタート側） */
    private void moveRiders(Course c, Push push, double pt, double ps) {
        int ie = 0, ia = 0;
        for (UUID u : push.riders) {
            Entity e = Bukkit.getEntity(u);
            MobInfo mi = mobs.get(u);
            if (!(e instanceof LivingEntity le) || mi == null || !le.isValid()) continue;
            boolean enemy = mi.role() == Role.ENEMY;
            int idx = enemy ? ie++ : ia++;
            double row = (idx / 3) * 1.2;
            double rt = enemy ? pt + 1.3 + row : pt - 1.3 - row;
            rt = clamp(rt, -Course.PAD + 0.3, c.length + Course.PLATFORM - 0.3);
            double rs = clamp(ps + LANES[idx % 3], -Course.MOB_S, Course.MOB_S);
            Location dest = c.at(rt, rs);
            int sign = enemy ? -1 : 1;   // 敵はスタート側向き、味方はゴール側向きにプレイヤーを見る
            dest.setDirection(new Vector(sign * c.dx, 0, sign * c.dz));
            dest.setYaw(dest.getYaw() + mi.spec().yawOffset());
            moveMob(le, dest);
        }
    }

    /** 押された距離（1マスごと）に合わせて効果音を鳴らす */
    private void pushSound(Player p, Push push) {
        if (push.back) {
            p.playSound(p.getLocation(), Sound.BLOCK_NOTE_BLOCK_BASS, 1f, 0.6f);
        } else {
            p.playSound(p.getLocation(), Sound.BLOCK_NOTE_BLOCK_CHIME, 1f, Math.min(2f, 0.8f + 0.1f * push.played));
        }
    }

    private void stepPush(Course c, Player p, Push push) {
        UUID id = p.getUniqueId();
        Location loc = p.getLocation();
        double t = c.t(loc);
        double s = clamp(c.s(loc), -Course.PLAYER_S, Course.PLAYER_S);

        double intended = Math.min(push.speed, push.total - push.moved);
        double nt = clamp(t + push.sign * intended, c.minT(), c.maxT());   // 上下の壁より先へは進めない
        double blocked = intended - Math.abs(nt - t);                       // 壁に阻まれた分

        Location dest = c.at(nt, s);
        dest.setYaw(loc.getYaw());
        dest.setPitch(loc.getPitch());
        p.teleport(dest);
        p.setFallDistance(0f);
        moveRiders(c, push, nt, s);
        push.moved += intended;

        // 下の壁に押し付けられている間は、1マス分ごとにダメージゲージが減る
        if (push.back && blocked > 1e-6 && cfg().getBoolean("wall-damage.enabled", true)) {
            push.pressed += blocked;
            int should = (int) Math.floor(push.pressed + 1e-6);
            while (push.pressedCounted < should) {
                push.pressedCounted++;
                if (damageGauge(p)) {          // ゲージが尽きた
                    endPush(id);
                    immuneUntil.put(id, tick + 60);
                    return;
                }
            }
        }

        // 1マス進むごとに効果音
        int should = (int) Math.floor(push.moved + 1e-6);
        while (push.played < should && push.played < (int) Math.round(push.total)) {
            push.played++;
            pushSound(p, push);
        }

        if (push.total - push.moved < 0.01) {
            while (push.played < (int) Math.round(push.total)) {
                push.played++;
                pushSound(p, push);
            }
            endPush(id);   // 押す距離の分だけ押し終わったら、付き添っていた mob は消える
            immuneUntil.put(id, tick + cfg().getLong("push-cooldown-ticks", 30L));
        }
    }

    // ---------- ダメージゲージ ----------

    private static int num(double v) {
        return (int) Math.round(v);
    }

    private Component gaugeTitle(double v, double max) {
        if (v <= 0) {
            return Component.text("ダメージゲージ 0 / " + num(max) + "   -1win", NamedTextColor.DARK_RED);
        }
        return Component.text("ダメージゲージ " + num(v) + " / " + num(max), NamedTextColor.RED);
    }

    /** ゲージを減らす。尽きた（0 になった）瞬間だけ true */
    private boolean damageGauge(Player p) {
        UUID id = p.getUniqueId();
        if (gaugeResetAt.containsKey(id)) return false;   // 0 になった直後は、満タンに戻るまでダメージなし

        double max = cfg().getDouble("wall-damage.gauge-max", 100.0);
        double per = cfg().getDouble("wall-damage.per-block", 10.0);
        double v = Math.max(0, gauge.getOrDefault(id, max) - per);
        gauge.put(id, v);

        net.kyori.adventure.bossbar.BossBar bar = gaugeBars.get(id);
        if (bar == null) {
            bar = net.kyori.adventure.bossbar.BossBar.bossBar(
                    gaugeTitle(max, max), 1f,
                    net.kyori.adventure.bossbar.BossBar.Color.RED,
                    net.kyori.adventure.bossbar.BossBar.Overlay.NOTCHED_10);
            gaugeBars.put(id, bar);
            p.showBossBar(bar);
        }
        bar.name(gaugeTitle(v, max));
        bar.progress((float) clamp(v / max, 0, 1));
        p.playSound(p.getLocation(), Sound.ENTITY_PLAYER_HURT, 1f, 1f);

        if (v > 0) return false;

        // ゲージが 0 になった → -1win
        records.addLoss(p, cfg().getBoolean("scoreboard.minus-win-on-loss", false));
        Bukkit.broadcast(Component.text("✖ " + p.getName() + " は壁に押しつぶされた！  -1win"
                + "  通算 " + records.wins(p.getName()) + "勝 " + records.losses(p.getName()) + "敗", NamedTextColor.DARK_RED));
        p.showTitle(Title.title(Component.text("-1win", NamedTextColor.DARK_RED),
                Component.text("ダメージゲージが 0 になった", NamedTextColor.GRAY)));
        gaugeResetAt.put(id, tick + cfg().getLong("wall-damage.reset-delay-ticks", 60L));
        if (cfg().getBoolean("wall-damage.kill-on-zero", false)) p.setHealth(0.0);
        return true;
    }

    private void tickGauges() {
        double max = cfg().getDouble("wall-damage.gauge-max", 100.0);

        // 0 になってしばらくしたら満タンに戻す
        if (!gaugeResetAt.isEmpty()) {
            for (Iterator<Map.Entry<UUID, Long>> it = gaugeResetAt.entrySet().iterator(); it.hasNext(); ) {
                Map.Entry<UUID, Long> e = it.next();
                if (tick < e.getValue()) continue;
                it.remove();
                gauge.put(e.getKey(), max);
                net.kyori.adventure.bossbar.BossBar bar = gaugeBars.get(e.getKey());
                if (bar != null) {
                    bar.name(gaugeTitle(max, max));
                    bar.progress(1f);
                }
            }
        }

        double regen = cfg().getDouble("wall-damage.regen-per-second", 0.0);
        if (regen <= 0 || tick % 20 != 0 || gauge.isEmpty()) return;
        for (Map.Entry<UUID, Double> e : gauge.entrySet()) {
            if (e.getValue() >= max || e.getValue() <= 0 || pushes.containsKey(e.getKey())
                    || gaugeResetAt.containsKey(e.getKey())) continue;
            double v = Math.min(max, e.getValue() + regen);
            e.setValue(v);
            net.kyori.adventure.bossbar.BossBar bar = gaugeBars.get(e.getKey());
            if (bar != null) {
                bar.name(gaugeTitle(v, max));
                bar.progress((float) clamp(v / max, 0, 1));
            }
        }
    }

    // ---------- ゴールまでの距離 ----------

    private void updateDistanceBars(Course c, List<Player> ps) {
        if (!cfg().getBoolean("goal-distance.enabled", true)) return;
        if (tick % 4 != 0) return;
        for (Player p : ps) {
            Location loc = p.getLocation();
            double t = c.t(loc);
            int remain = (int) Math.max(0, Math.ceil(c.length - t));
            net.kyori.adventure.bossbar.BossBar bar = distBars.get(p.getUniqueId());
            if (bar == null) {
                bar = net.kyori.adventure.bossbar.BossBar.bossBar(
                        Component.text(""), 0f,
                        net.kyori.adventure.bossbar.BossBar.Color.YELLOW,
                        net.kyori.adventure.bossbar.BossBar.Overlay.PROGRESS);
                distBars.put(p.getUniqueId(), bar);
                p.showBossBar(bar);
            }
            if (c.onSummit(loc)) {
                bar.name(Component.text("ゴールの台の上！", NamedTextColor.GOLD));
                bar.progress(1f);
            } else {
                bar.name(Component.text("ゴールまで " + remain + " マス", NamedTextColor.YELLOW));
                bar.progress((float) clamp(t / c.length, 0, 1));
            }
        }
    }

    private void hideDist(Player p) {
        net.kyori.adventure.bossbar.BossBar bar = distBars.remove(p.getUniqueId());
        if (bar != null) p.hideBossBar(bar);
    }

    private void hideAllDist() {
        for (UUID id : new ArrayList<>(distBars.keySet())) {
            Player p = Bukkit.getPlayer(id);
            if (p != null) hideDist(p);
        }
        distBars.clear();
    }

    private void hideGauge(Player p) {
        net.kyori.adventure.bossbar.BossBar bar = gaugeBars.remove(p.getUniqueId());
        if (bar != null) p.hideBossBar(bar);
        gauge.remove(p.getUniqueId());
        gaugeResetAt.remove(p.getUniqueId());
    }

    private void hideAllGauges() {
        for (UUID id : new ArrayList<>(gaugeBars.keySet())) {
            Player p = Bukkit.getPlayer(id);
            if (p != null) hideGauge(p);
        }
        gaugeBars.clear();
        gauge.clear();
        gaugeResetAt.clear();
    }

    private void win(Course c, Player p) {
        UUID id = p.getUniqueId();
        Long start = startTimes.get(id);
        String time = "";
        if (start != null) {
            double sec = (System.currentTimeMillis() - start) / 1000.0;
            time = String.format(Locale.ROOT, "（タイム %.1f 秒）", sec);
        }
        records.addWin(p);
        Bukkit.broadcast(Component.text("★ " + p.getName() + " がゴールを守りきって勝利！" + time
                + "  通算 " + records.wins(p.getName()) + "勝 " + records.losses(p.getName()) + "敗", NamedTextColor.GOLD));
        p.playSound(p.getLocation(), Sound.UI_TOAST_CHALLENGE_COMPLETE, 1f, 1f);

        Location l = c.at(c.length + 1.5, 0);
        l.getWorld().spawn(l, Firework.class, fw -> {
            FireworkMeta m = fw.getFireworkMeta();
            m.addEffect(FireworkEffect.builder()
                    .with(FireworkEffect.Type.BALL_LARGE)
                    .withColor(Color.YELLOW, Color.ORANGE)
                    .withFade(Color.WHITE)
                    .build());
            m.setPower(1);
            fw.setFireworkMeta(m);
        });

        // スタート台へテレポート
        endPush(id);
        holdSince.remove(id);
        startTimes.remove(id);
        hideGauge(p);
        Location bottom = c.at(-1.5, 0);
        bottom.setYaw((float) Math.toDegrees(Math.atan2(-c.dx, c.dz)));
        bottom.setPitch(0f);
        p.teleport(bottom);
        p.setFallDistance(0f);
        immuneUntil.put(id, tick + 40);
    }
}
