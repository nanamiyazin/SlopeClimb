package com.example.slopeclimb;

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
import org.bukkit.block.data.Bisected;
import org.bukkit.block.data.BlockData;
import org.bukkit.block.data.Directional;
import org.bukkit.block.data.type.Stairs;
import org.bukkit.boss.BossBar;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.Ageable;
import org.bukkit.entity.ArmorStand;
import org.bukkit.entity.Boss;
import org.bukkit.entity.Entity;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.Firework;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.inventory.meta.FireworkMeta;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.util.BoundingBox;
import org.bukkit.util.Vector;

import java.time.Duration;
import java.util.ArrayList;
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

    /** mob がプレイヤーに近づいてくる側（坂の上=FRONT / 下=BACK / 左右=壁際） */
    public enum Side {
        FRONT("前(坂の上)"), BACK("後ろ(坂の下)"), LEFT("左"), RIGHT("右"), RANDOM("ランダム");

        public final String label;
        Side(String label) { this.label = label; }

        public static Side parse(String s) {
            return switch (s.toLowerCase(Locale.ROOT)) {
                case "front", "up" -> FRONT;
                case "back", "down" -> BACK;
                case "left" -> LEFT;
                case "right" -> RIGHT;
                case "random" -> RANDOM;
                default -> null;
            };
        }
    }

    /** seconds = 押す時間(秒)、power = 押す力(競合時に強い方が勝つ) */
    public static final class MobInfo {
        private final Role role;
        private final double seconds;
        private final int power;
        private final MobSpec spec;
        double dirT, dirS;      // 進む方向（コース座標・正規化）
        boolean riding = false; // 押している最中（プレイヤーについていく状態）

        MobInfo(Role role, double seconds, int power, MobSpec spec) {
            this.role = role;
            this.seconds = seconds;
            this.power = power;
            this.spec = spec;
        }

        public Role role() { return role; }
        public double seconds() { return seconds; }
        public int power() { return power; }
        public MobSpec spec() { return spec; }
    }

    private static final class Push {
        final int sign;          // -1 = 坂の下へ押される(敵) / +1 = 坂の上へ押される(味方)
        final double total;      // 押される総距離(マス)。壁で動けなくても、この距離分はそのまま押し続ける
        final double speed;
        final boolean back;
        final List<UUID> riders; // 一緒に押されている mob（勝った側も負けた側も）
        double moved = 0;
        int played = 0;
        double pressed = 0;      // 壁に押し付けられた距離
        int pressedCounted = 0;

        Push(int sign, double total, double speed, boolean back, List<UUID> riders) {
            this.sign = sign;
            this.total = total;
            this.speed = speed;
            this.back = back;
            this.riders = riders;
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

    public GameManager(SlopeClimbPlugin plugin) {
        this.plugin = plugin;
    }

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

    public List<String> allowedMobs(Role role) {
        return cfg().getStringList(role == Role.ENEMY ? "enemy-mobs" : "ally-mobs");
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

    private void teleportInternal(Entity e, Location dest) {
        internalMove = true;
        try {
            e.teleport(dest);
        } finally {
            internalMove = false;
        }
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
        if (kind.equals("stair")) {
            return (m.createBlockData() instanceof Stairs) ? m : null;
        }
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
        if (!List.of("stair", "block", "fill", "pad", "goal", "wall").contains(kind)) {
            return "種類は stair / block / fill / pad / goal / wall のいずれかです。";
        }
        Material m = parseMaterial(kind, name);
        if (m == null) {
            return kind.equals("stair")
                    ? "階段ブロックを指定してください（例: stone_brick_stairs）。"
                    : "使えない素材です。落下せず、完全な立方体のブロックを指定してください。";
        }
        cfg().set("materials." + kind, m.name());
        plugin.saveConfig();
        return null;
    }

    /** @return エラーメッセージ。成功時は null */
    public String create(Player creator, int requested, Material stairMat, Material blockMat) {
        if (busy) return "処理中です。しばらく待ってください。";
        if (course != null) return "既にコースがあります。先に /koth remove を実行してください。";

        int length = requested + (requested % 2);   // 階段+ブロックの2マス単位なので偶数に切り上げ
        int height = length / 2;

        Location pl = creator.getLocation();
        BlockFace dir = facingOf(pl.getYaw());
        int baseY = pl.getBlockY();
        int x0 = pl.getBlockX() + dir.getModX();
        int z0 = pl.getBlockZ() + dir.getModZ();

        if (baseY + height + Course.WALL_HEIGHT + 4 >= pl.getWorld().getMaxHeight()) {
            return "高さ制限を超えます。長さを短くするか、低い場所で実行してください。";
        }
        if (baseY - 2 <= pl.getWorld().getMinHeight()) {
            return "低すぎる場所です。";
        }

        Course c = new Course(pl.getWorld(), x0, baseY, z0, dir, length);

        BlockData stair = (stairMat != null ? stairMat : material("stair", Material.STONE_BRICK_STAIRS)).createBlockData();
        if (stair instanceof Directional d) d.setFacing(dir);
        if (stair instanceof Bisected b) b.setHalf(Bisected.Half.BOTTOM);
        BlockData block = (blockMat != null ? blockMat : material("block", Material.STONE_BRICKS)).createBlockData();
        BlockData fill = material("fill", Material.STONE_BRICKS).createBlockData();
        BlockData pad = material("pad", Material.SMOOTH_STONE).createBlockData();
        BlockData goal = material("goal", Material.GOLD_BLOCK).createBlockData();
        BlockData wall = material("wall", Material.STONE_BRICKS).createBlockData();

        course = c;
        busy = true;
        creator.sendMessage(Component.text("コースを生成中...", NamedTextColor.YELLOW));
        runBatched(c.buildJobs(stair, block, fill, pad, goal, wall).iterator(), () -> {
            c.ready = true;
            busy = false;
            if (creator.isOnline()) {
                creator.sendMessage(Component.text("コース完成！ 方向=" + dir + " / 幅=" + Course.WIDTH
                        + " / 長さ=" + length + "マス（" + height + "段）/ 頂上の高さ Y=" + (c.baseY + height)
                        + (requested != length ? " ※奇数のため偶数に切り上げました" : ""), NamedTextColor.GREEN));
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
        endAllCinematics();
        hideAllGauges();
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

    /** プレイヤーの (t, s) から dist マス離れたランダムな地点を返す {t, s} */
    private double[] spawnPoint(Course c, double pt, double ps, Side side, double dist, boolean free) {
        ThreadLocalRandom r = ThreadLocalRandom.current();
        double t, s;
        switch (side) {
            case LEFT, RIGHT -> {
                s = side == Side.LEFT ? -Course.MOB_S : Course.MOB_S;
                t = pt + r.nextDouble(-3, 3);
                if (Math.abs(s - ps) < 3.0) {
                    // プレイヤーが同じ壁際にいるときは、坂に沿って少し離す
                    t = pt + (r.nextBoolean() ? 1 : -1) * r.nextDouble(5, 8);
                }
            }
            default -> {
                s = r.nextDouble(-Course.MOB_S, Course.MOB_S);
                double dl = s - ps;
                double dt = Math.sqrt(Math.max(1.0, dist * dist - dl * dl));   // 直線距離がちょうど dist になる
                t = side == Side.BACK ? pt - dt : pt + dt;
            }
        }
        if (!free) t = clamp(t, -2.0, c.length + Course.PLATFORM - 0.5);
        return new double[]{t, s};
    }

    /** @return メッセージ */
    public String spawn(Role role, MobSpec spec, double seconds, int power, int count, Side side, Player anchor) {
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
        if (ap == null) return "コース上にプレイヤーがいません。坂の上に立ってから実行してください。";

        double pt = c.t(ap.getLocation());
        double psS = clamp(c.s(ap.getLocation()), -Course.PLAYER_S, Course.PLAYER_S);

        boolean intro = spec.intro() && cfg().getBoolean("intro.enabled", true)
                && !cinematics.containsKey(ap.getUniqueId());
        double dist = intro ? cfg().getDouble("intro.distance", 30.0) : cfg().getDouble("spawn-distance", 10.0);
        Side base = side != null ? side : (role == Role.ENEMY ? Side.FRONT : Side.BACK);

        ThreadLocalRandom rnd = ThreadLocalRandom.current();
        int spawned = 0;
        LivingEntity firstBoss = null;
        MobInfo firstInfo = null;
        for (int i = 0; i < count; i++) {
            Side sd = base == Side.RANDOM ? Side.values()[rnd.nextInt(4)] : base;
            if (intro && (sd == Side.LEFT || sd == Side.RIGHT)) {
                sd = role == Role.ENEMY ? Side.FRONT : Side.BACK;   // 遠くからの登場は坂の軸に沿って
            }
            boolean free = intro && spec.move() == MobSpec.Move.FLY;
            double[] pos = spawnPoint(c, pt, psS, sd, dist, free);
            double t = pos[0], s = pos[1];

            // 進む方向 = 出現地点からプレイヤーの位置へ向かう直線
            double dT = pt - t, dS = psS - s;
            double len = Math.hypot(dT, dS);
            if (len < 0.01) {
                dT = sd == Side.BACK ? 1 : -1;
                dS = 0;
                len = 1;
            }

            Location loc = c.at(t, s);
            loc.add(0, spec.hover(), 0);
            double vx = (dT / len) * c.dx + (dS / len) * c.lx;
            double vz = (dT / len) * c.dz + (dS / len) * c.lz;
            loc.setYaw((float) Math.toDegrees(Math.atan2(-vx, vz)) + spec.yawOffset());
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
                    (role == Role.ENEMY ? "敵" : "味方") + " 力" + power + " / " + fmtSec(seconds) + "秒",
                    role == Role.ENEMY ? NamedTextColor.RED : NamedTextColor.GREEN));
            le.setCustomNameVisible(true);

            MobInfo mi = new MobInfo(role, seconds, power, spec);
            mi.dirT = dT / len;
            mi.dirS = dS / len;
            mobs.put(le.getUniqueId(), mi);
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
                + " 体召喚しました（押す時間 " + fmtSec(seconds) + "秒 / 押す力 " + power + " / 近づく側 "
                + base.label + "）";
    }

    public int clearMobs(Role only) {
        int n = 0;
        Iterator<Map.Entry<UUID, MobInfo>> it = mobs.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<UUID, MobInfo> e = it.next();
            if (only != null && e.getValue().role() != only) continue;
            Entity ent = Bukkit.getEntity(e.getKey());
            if (ent != null) ent.remove();
            it.remove();
            n++;
        }
        return n;
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
        Course c = course;
        if (c == null || !c.ready) return;

        List<Player> ps = participants(c);

        // mob の移動（直線のみ）
        Iterator<Map.Entry<UUID, MobInfo>> it = mobs.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<UUID, MobInfo> e = it.next();
            Entity ent = Bukkit.getEntity(e.getKey());
            if (!(ent instanceof LivingEntity le) || !le.isValid()) {
                it.remove();
                continue;
            }
            MobInfo mi = e.getValue();
            if (mi.riding) continue;   // 押している最中はプレイヤーについていく
            if (moveStraight(c, le, mi, ps)) {
                le.remove();
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
        // コース外に出たプレイヤーの記録を消す
        for (Player p : Bukkit.getOnlinePlayers()) {
            UUID id = p.getUniqueId();
            if (!active.contains(id) && !cinematics.containsKey(id)) {
                startTimes.remove(id);
                finished.remove(id);
                holdSince.remove(id);
                endPush(id);
                hideGauge(p);
            }
        }
    }

    /** mob を決められた方向へ直線に動かす。コースの外へ抜けたら true（削除） */
    private boolean moveStraight(Course c, LivingEntity ent, MobInfo mi, List<Player> ps) {
        MobSpec spec = mi.spec();
        Location ml = ent.getLocation();
        double t = c.t(ml), s = c.s(ml);

        if (cfg().getBoolean("mob-homing", false) && !ps.isEmpty()) {
            Player target = nearest(ps, ml);
            if (target != null) {
                double dT = c.t(target.getLocation()) - t;
                double dS = clamp(c.s(target.getLocation()), -Course.MOB_S, Course.MOB_S) - s;
                double len = Math.hypot(dT, dS);
                if (len > 0.05) {
                    mi.dirT = dT / len;
                    mi.dirS = dS / len;
                }
            }
        }

        double speed = cfg().getDouble("manual-speed", 0.2) * cfg().getDouble("mob-speed", 1.0);
        double nt = t + mi.dirT * speed;
        double ns = clamp(s + mi.dirS * speed, -Course.MOB_S, Course.MOB_S);

        double lo = -Course.PAD - 0.5;
        double hi = c.length + Course.PLATFORM + 0.5;
        if ((nt > hi && mi.dirT > 0) || (nt < lo && mi.dirT < 0)) return true;

        Location dest = c.at(nt, ns);
        dest.add(0, spec.hover(), 0);
        double vx = mi.dirT * c.dx + mi.dirS * c.lx;
        double vz = mi.dirT * c.dz + mi.dirS * c.lz;
        dest.setYaw((float) Math.toDegrees(Math.atan2(-vx, vz)) + spec.yawOffset());
        dest.setPitch(0f);
        teleportInternal(ent, dest);
        return false;
    }

    private void processPlayer(Course c, Player p) {
        UUID id = p.getUniqueId();
        Location loc = p.getLocation();
        double t = c.t(loc);

        if (t >= 0) startTimes.putIfAbsent(id, System.currentTimeMillis());

        // 頂上キープ判定（頂上の台に一定時間とどまれば勝ち）
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
                    p.sendActionBar(Component.text("頂上キープ中！ あと " + remain + " 秒", NamedTextColor.GOLD));
                    p.playSound(p.getLocation(), Sound.BLOCK_NOTE_BLOCK_HAT, 0.8f, 1.5f);
                }
            }
        } else {
            if (holdSince.remove(id) != null) {
                p.sendActionBar(Component.text("頂上から離れた！ カウントリセット", NamedTextColor.RED));
            }
            if (t < c.length - 1) finished.remove(id);
        }

        // 押し出し中
        Push push = pushes.get(id);
        if (push != null) {
            stepPush(c, p, push);
            return;
        }

        if (tick < immuneUntil.getOrDefault(id, 0L)) return;

        // 接触判定
        double range = cfg().getDouble("contact-range", 0.25);
        BoundingBox pb = p.getBoundingBox().clone().expand(range);
        List<Entity> touching = new ArrayList<>();
        int enemyPower = -1, allyPower = -1;
        double enemySec = 0, allySec = 0;
        for (Entity ent : p.getNearbyEntities(6, 6, 6)) {
            MobInfo mi = mobs.get(ent.getUniqueId());
            if (mi == null || mi.riding) continue;
            boolean hit;
            double cr = mi.spec().contactRadius();
            if (cr > 0) {
                Location el = ent.getLocation();
                double ddx = el.getX() - loc.getX(), ddz = el.getZ() - loc.getZ();
                hit = ddx * ddx + ddz * ddz <= cr * cr && Math.abs(el.getY() - loc.getY()) <= 6;
            } else {
                hit = pb.overlaps(ent.getBoundingBox());
            }
            if (!hit) continue;
            touching.add(ent);
            if (mi.role() == Role.ENEMY) {
                if (mi.power() > enemyPower) { enemyPower = mi.power(); enemySec = mi.seconds(); }
            } else {
                if (mi.power() > allyPower) { allyPower = mi.power(); allySec = mi.seconds(); }
            }
        }
        if (touching.isEmpty()) return;

        boolean back;
        double sec;
        if (enemyPower > allyPower) {
            back = true;
            sec = enemySec;
        } else if (allyPower > enemyPower) {
            back = false;
            sec = allySec;
        } else {
            // 力が同じ → 押し合い互角（誰も動かず、mob も消えない。そのまま直進を続ける）
            p.sendActionBar(Component.text("押し合い互角！", NamedTextColor.YELLOW));
            immuneUntil.put(id, tick + 20);
            return;
        }

        // 触れた mob は全員（勝った側も負けた側も）、押されている間プレイヤーと一緒に動く。
        // 壁で動けなくなっても、押す距離の分だけ押し続けるまで消えない。
        List<UUID> riders = new ArrayList<>();
        for (Entity ent : touching) {
            MobInfo mi = mobs.get(ent.getUniqueId());
            if (mi != null) {
                mi.riding = true;
                riders.add(ent.getUniqueId());
            }
        }
        // 押す時間(秒) × push-speed(1tickあたりのマス数) × 20tick = 押される総距離
        double speed = cfg().getDouble("push-speed", 0.25);
        double total = Math.max(speed, sec * 20.0 * speed);
        pushes.put(id, new Push(back ? -1 : 1, total, speed, back, riders));
        if (back) {
            p.sendActionBar(Component.text("敵に " + fmtSec(sec) + " 秒押し戻される！", NamedTextColor.RED));
        } else {
            p.sendActionBar(Component.text("味方が " + fmtSec(sec) + " 秒押してくれる！", NamedTextColor.GREEN));
        }
    }

    /** 接触した mob を消す */
    private void consume(List<Entity> list) {
        for (Entity e : list) {
            mobs.remove(e.getUniqueId());
            Location l = e.getLocation().add(0, e.getHeight() / 2, 0);
            e.getWorld().spawnParticle(Particle.POOF, l, 12, 0.3, 0.3, 0.3, 0.02);
            e.remove();
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
            else mobs.remove(u);
        }
        consume(list);
    }

    /** 押されている最中の mob をプレイヤーの両脇に付き添わせる（敵=坂の上側、味方=坂の下側） */
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
            dest.add(0, mi.spec().hover(), 0);
            int sign = enemy ? -1 : 1;   // 敵は坂の下向き、味方は上向きにプレイヤーを見る
            dest.setDirection(new Vector(sign * c.dx, 0, sign * c.dz));
            dest.setYaw(dest.getYaw() + mi.spec().yawOffset());
            teleportInternal(le, dest);
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

    /** ゲージを減らす。尽きたら true */
    private boolean damageGauge(Player p) {
        UUID id = p.getUniqueId();
        double max = cfg().getDouble("wall-damage.gauge-max", 100.0);
        double per = cfg().getDouble("wall-damage.per-block", 10.0);
        double v = Math.max(0, gauge.getOrDefault(id, max) - per);
        gauge.put(id, v);

        net.kyori.adventure.bossbar.BossBar bar = gaugeBars.get(id);
        if (bar == null) {
            bar = net.kyori.adventure.bossbar.BossBar.bossBar(
                    Component.text("ダメージゲージ", NamedTextColor.RED), 1f,
                    net.kyori.adventure.bossbar.BossBar.Color.RED,
                    net.kyori.adventure.bossbar.BossBar.Overlay.NOTCHED_10);
            gaugeBars.put(id, bar);
            p.showBossBar(bar);
        }
        bar.progress((float) clamp(v / max, 0, 1));
        p.playSound(p.getLocation(), Sound.ENTITY_PLAYER_HURT, 1f, 1f);

        if (v > 0) return false;

        // ゲージが尽きた → ダウン
        Bukkit.broadcast(Component.text("✖ " + p.getName() + " は壁に押しつぶされてダウン！", NamedTextColor.DARK_RED));
        p.showTitle(Title.title(Component.text("ダウン！", NamedTextColor.DARK_RED),
                Component.text("ダメージゲージが尽きた", NamedTextColor.GRAY)));
        gauge.put(id, max);
        bar.progress(1f);
        if (cfg().getBoolean("wall-damage.kill-on-zero", false)) p.setHealth(0.0);
        return true;
    }

    private void tickGauges() {
        double regen = cfg().getDouble("wall-damage.regen-per-second", 0.0);
        if (regen <= 0 || tick % 20 != 0 || gauge.isEmpty()) return;
        double max = cfg().getDouble("wall-damage.gauge-max", 100.0);
        for (Map.Entry<UUID, Double> e : gauge.entrySet()) {
            if (e.getValue() >= max || pushes.containsKey(e.getKey())) continue;
            double v = Math.min(max, e.getValue() + regen);
            e.setValue(v);
            net.kyori.adventure.bossbar.BossBar bar = gaugeBars.get(e.getKey());
            if (bar != null) bar.progress((float) clamp(v / max, 0, 1));
        }
    }

    private void hideGauge(Player p) {
        net.kyori.adventure.bossbar.BossBar bar = gaugeBars.remove(p.getUniqueId());
        if (bar != null) p.hideBossBar(bar);
        gauge.remove(p.getUniqueId());
    }

    private void hideAllGauges() {
        for (UUID id : new ArrayList<>(gaugeBars.keySet())) {
            Player p = Bukkit.getPlayer(id);
            if (p != null) hideGauge(p);
        }
        gaugeBars.clear();
        gauge.clear();
    }

    private void win(Course c, Player p) {
        UUID id = p.getUniqueId();
        Long start = startTimes.get(id);
        String time = "";
        if (start != null) {
            double sec = (System.currentTimeMillis() - start) / 1000.0;
            time = String.format(Locale.ROOT, "（タイム %.1f 秒）", sec);
        }
        Bukkit.broadcast(Component.text("★ " + p.getName() + " が頂上を守りきって勝利！" + time, NamedTextColor.GOLD));
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

        // 一番下（スタート台）へテレポート
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
