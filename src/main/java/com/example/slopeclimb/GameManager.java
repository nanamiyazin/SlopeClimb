package com.example.slopeclimb;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.Color;
import org.bukkit.FireworkEffect;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Particle;
import org.bukkit.NamespacedKey;
import org.bukkit.Registry;
import org.bukkit.Sound;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.block.BlockFace;
import org.bukkit.block.data.Bisected;
import org.bukkit.block.data.BlockData;
import org.bukkit.block.data.Directional;
import org.bukkit.boss.BossBar;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.Ageable;
import org.bukkit.entity.Boss;
import org.bukkit.entity.Entity;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.Firework;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Mob;
import org.bukkit.entity.Player;
import org.bukkit.inventory.meta.FireworkMeta;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.util.BoundingBox;
import org.bukkit.util.Vector;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

public final class GameManager {

    public enum Role { ENEMY, ALLY }

    /** distance = 押す距離(マス)、power = 押す力(競合時に強い方が勝つ) */
    public record MobInfo(Role role, int distance, int power, MobSpec spec) {}

    private static final class Push {
        final double targetT;
        final double speed;
        final boolean back;
        final int totalBlocks;
        double moved = 0;
        int played = 0;
        Push(double targetT, double speed, boolean back, int totalBlocks) {
            this.targetT = targetT;
            this.speed = speed;
            this.back = back;
            this.totalBlocks = totalBlocks;
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
    private final Map<UUID, Long> immuneUntil = new HashMap<>();
    private final Map<UUID, Long> startTimes = new HashMap<>();
    private final Map<UUID, Long> holdSince = new HashMap<>();
    private final Set<UUID> finished = new HashSet<>();

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

    // ---------- コース生成 / 削除 ----------

    private Material material(String key, Material def) {
        Material m = Material.matchMaterial(cfg().getString("materials." + key, def.name()));
        return (m != null && m.isBlock()) ? m : def;
    }

    /** @return エラーメッセージ。成功時は null */
    public String create(Player creator, int requested) {
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

        BlockData stair = material("stair", Material.STONE_BRICK_STAIRS).createBlockData();
        if (stair instanceof Directional d) d.setFacing(dir);
        if (stair instanceof Bisected b) b.setHalf(Bisected.Half.BOTTOM);
        BlockData block = material("block", Material.STONE_BRICKS).createBlockData();
        BlockData fill = material("fill", Material.STONE_BRICKS).createBlockData();
        BlockData pad = material("pad", Material.SMOOTH_STONE).createBlockData();
        BlockData goal = material("goal", Material.GOLD_BLOCK).createBlockData();

        course = c;
        busy = true;
        creator.sendMessage(Component.text("コースを生成中...", NamedTextColor.YELLOW));
        runBatched(c.buildJobs(stair, block, fill, pad, goal).iterator(), () -> {
            c.ready = true;
            busy = false;
            if (creator.isOnline()) {
                creator.sendMessage(Component.text("コース完成！ 方向=" + dir + " / 長さ=" + length
                        + "マス（" + height + "段）/ 頂上の高さ Y=" + (c.baseY + height)
                        + (requested != length ? " ※奇数のため偶数に切り上げました" : ""), NamedTextColor.GREEN));
            }
        });
        return null;
    }

    public String remove() {
        if (busy) return "処理中です。しばらく待ってください。";
        if (course == null) return "コースがありません。";
        Course c = course;
        clearMobs(null);
        pushes.clear();
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

    /** @return メッセージ */
    public String spawn(Role role, MobSpec spec, int distance, int power, int count, Player anchor) {
        Course c = course;
        if (c == null || !c.ready) return "コースがまだありません（生成中の可能性もあります）。";
        EntityType type = entityType(spec);
        if (type == null) return "この mob は使用できません。";

        double anchorT = 0;
        if (anchor != null && c.contains(anchor.getLocation())) {
            anchorT = c.t(anchor.getLocation());
        } else {
            List<Player> ps = participants(c);
            if (!ps.isEmpty()) anchorT = c.t(ps.get(0).getLocation());
        }

        double spawnDist = cfg().getDouble("spawn-distance", 10.0);
        int spawned = 0;
        for (int i = 0; i < count; i++) {
            double row = (i / 3) * 1.5;
            double t;
            if (role == Role.ENEMY) {
                t = Math.max(0.5, Math.min(c.length - 0.5, anchorT + spawnDist + row));
            } else {
                t = Math.max(-2.0, Math.min(c.length - 0.5, anchorT - spawnDist - row));
            }
            Location loc = c.at(t, LANES[i % 3]);
            loc.add(0, spec.hover(), 0);
            int sign = role == Role.ENEMY ? -1 : 1;
            loc.setDirection(new Vector(sign * c.dx, 0, sign * c.dz));
            loc.setYaw(loc.getYaw() + spec.yawOffset());

            Entity ent = c.world.spawnEntity(loc, type);
            if (!(ent instanceof LivingEntity le)) {
                ent.remove();
                continue;
            }
            le.setInvulnerable(true);
            le.setPersistent(false);
            le.setRemoveWhenFarAway(false);
            le.setCanPickupItems(false);
            if (le instanceof Ageable a) a.setAdult();
            if (spec.scale() != 1.0) {
                AttributeInstance ai = le.getAttribute(Attribute.SCALE);
                if (ai != null) ai.setBaseValue(spec.scale());
            }
            if (spec.move() != MobSpec.Move.PATH) {
                le.setAI(false);
                le.setGravity(false);
            }
            if (spec.silent()) le.setSilent(true);
            if (le instanceof Boss boss) {
                BossBar bar = boss.getBossBar();
                if (bar != null) bar.setVisible(false);
            }
            le.customName(Component.text(
                    (role == Role.ENEMY ? "敵" : "味方") + " 力" + power + " / " + distance + "マス",
                    role == Role.ENEMY ? NamedTextColor.RED : NamedTextColor.GREEN));
            le.setCustomNameVisible(true);
            mobs.put(le.getUniqueId(), new MobInfo(role, distance, power, spec));
            spawned++;
        }
        return (role == Role.ENEMY ? "敵" : "味方") + " " + spec.key() + " を " + spawned
                + " 体召喚しました（押す距離 " + distance + " / 押す力 " + power + "）";
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
        Course c = course;
        if (c == null || !c.ready) return;

        List<Player> ps = participants(c);

        // mob の管理と移動
        boolean pathTick = tick % 10 == 0;
        double speed = cfg().getDouble("mob-speed", 1.0);
        Iterator<Map.Entry<UUID, MobInfo>> it = mobs.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<UUID, MobInfo> e = it.next();
            Entity ent = Bukkit.getEntity(e.getKey());
            if (!(ent instanceof LivingEntity le) || !le.isValid()) {
                it.remove();
                continue;
            }
            if (le.getLocation().getY() < c.baseY - 8) {
                le.remove();
                it.remove();
                continue;
            }
            if (ps.isEmpty()) continue;
            MobSpec spec = e.getValue().spec();
            if (spec.move() == MobSpec.Move.PATH) {
                if (pathTick && le instanceof Mob mob) {
                    Player target = nearest(ps, mob.getLocation());
                    if (target != null) {
                        mob.getPathfinder().moveTo(target.getLocation(), speed);
                        if (e.getValue().role() == Role.ENEMY) mob.setTarget(target);
                    }
                }
            } else {
                moveManual(c, le, spec, ps);
            }
        }

        // プレイヤー処理
        Set<UUID> active = new HashSet<>();
        for (Player p : ps) {
            active.add(p.getUniqueId());
            processPlayer(c, p);
        }
        // コース外に出たプレイヤーの記録を消す
        for (Player p : Bukkit.getOnlinePlayers()) {
            if (!active.contains(p.getUniqueId())) {
                UUID id = p.getUniqueId();
                startTimes.remove(id);
                finished.remove(id);
                holdSince.remove(id);
                pushes.remove(id);
            }
        }
    }

    /** AIを切った mob を、最も近いプレイヤーに向けて坂の上で手動移動させる */
    private void moveManual(Course c, LivingEntity ent, MobSpec spec, List<Player> ps) {
        Location ml = ent.getLocation();
        Player target = nearest(ps, ml);
        if (target == null) return;

        double t = c.t(ml), s = c.s(ml);
        Location pl = target.getLocation();
        double tt = c.t(pl);
        double ts = Math.max(-1.4, Math.min(1.4, c.s(pl)));
        double dt = tt - t, ds = ts - s;
        double dist = Math.hypot(dt, ds);
        if (dist < 0.05) return;

        double speed = cfg().getDouble("manual-speed", 0.2) * cfg().getDouble("mob-speed", 1.0);
        double step = Math.min(speed, dist);
        double nt = Math.max(-2.0, Math.min(c.length + 1.5, t + dt / dist * step));
        double ns = Math.max(-1.4, Math.min(1.4, s + ds / dist * step));

        Location dest = c.at(nt, ns);
        dest.add(0, spec.hover(), 0);
        double vx = dt * c.dx + ds * c.lx;
        double vz = dt * c.dz + ds * c.lz;
        dest.setYaw((float) Math.toDegrees(Math.atan2(-vx, vz)) + spec.yawOffset());
        dest.setPitch(0f);

        internalMove = true;
        try {
            ent.teleport(dest);
        } finally {
            internalMove = false;
        }
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
        int enemyPower = -1, enemyDist = 0, allyPower = -1, allyDist = 0;
        for (Entity ent : p.getNearbyEntities(6, 6, 6)) {
            MobInfo mi = mobs.get(ent.getUniqueId());
            if (mi == null) continue;
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
                if (mi.power() > enemyPower) { enemyPower = mi.power(); enemyDist = mi.distance(); }
            } else {
                if (mi.power() > allyPower) { allyPower = mi.power(); allyDist = mi.distance(); }
            }
        }
        if (touching.isEmpty()) return;

        boolean back;
        int dist;
        if (enemyPower > allyPower) {
            back = true;
            dist = enemyDist;
        } else if (allyPower > enemyPower) {
            back = false;
            dist = allyDist;
        } else {
            // 力が同じ → 押し合い互角（接触した mob は消える）
            p.sendActionBar(Component.text("押し合い互角！", NamedTextColor.YELLOW));
            consume(touching);
            immuneUntil.put(id, tick + 20);
            return;
        }

        double target = back
                ? Math.max(-0.5, t - dist)
                : Math.min(c.length + 1.5, t + dist);
        consume(touching);   // 接触した mob は一度で消滅
        if (Math.abs(target - t) < 0.01) {
            immuneUntil.put(id, tick + 20);
            return;
        }

        int blocks = Math.max(1, (int) Math.round(Math.abs(target - t)));
        pushes.put(id, new Push(target, cfg().getDouble("push-speed", 0.25), back, blocks));
        if (back) {
            p.sendActionBar(Component.text("敵に " + blocks + " マス押し戻される！", NamedTextColor.RED));
        } else {
            p.sendActionBar(Component.text("味方が " + blocks + " マス押してくれる！", NamedTextColor.GREEN));
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
        double s = Math.max(-1.4, Math.min(1.4, c.s(loc)));

        double diff = push.targetT - t;
        double step = Math.min(Math.abs(diff), push.speed);
        double nt = t + Math.signum(diff) * step;

        Location dest = c.at(nt, s);
        dest.setYaw(loc.getYaw());
        dest.setPitch(loc.getPitch());
        p.teleport(dest);
        p.setFallDistance(0f);

        // 1マス進むごとに効果音
        push.moved += step;
        int should = (int) Math.floor(push.moved + 1e-6);
        while (push.played < should && push.played < push.totalBlocks) {
            push.played++;
            pushSound(p, push);
        }

        if (Math.abs(push.targetT - nt) < 0.01) {
            while (push.played < push.totalBlocks) {
                push.played++;
                pushSound(p, push);
            }
            pushes.remove(id);
            immuneUntil.put(id, tick + cfg().getLong("push-cooldown-ticks", 30L));
        }
    }

    private void win(Course c, Player p) {
        Long start = startTimes.get(p.getUniqueId());
        String time = "";
        if (start != null) {
            double sec = (System.currentTimeMillis() - start) / 1000.0;
            time = String.format(Locale.ROOT, "（タイム %.1f 秒）", sec);
        }
        Bukkit.broadcast(Component.text("★ " + p.getName() + " が頂上に到達しました！" + time, NamedTextColor.GOLD));
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
    }
}
