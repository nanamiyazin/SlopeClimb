package com.example.slopeclimb;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.data.BlockData;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 幅6マスの坂コース（両脇にバリアブロックの壁つき）。
 * 水平2マス（階段 + ブロック）で1段上がる。
 * コース座標: t = 進行方向の距離(坂の入口が0)、s = 横方向のずれ(中心線が0、-3〜3が坂の上)。
 */
public final class Course {

    public static final int PAD = 3;          // スタート台の長さ
    public static final int PLATFORM = 3;     // 頂上台の長さ
    public static final int WALL_HEIGHT = 4;  // バリアの高さ
    public static final int WIDTH = 6;        // 坂の幅
    public static final double HALF_W = WIDTH / 2.0;
    public static final double MOB_S = HALF_W - 0.6;     // mob が動ける横幅の限界
    public static final double PLAYER_S = HALF_W - 0.1;  // 押し出し中にプレイヤーが寄れる横幅の限界

    public record Backup(int x, int y, int z, BlockData data) {}

    public final World world;
    public final int baseY;
    public final int length;   // 水平方向の長さ（偶数）
    public final int height;   // 登る高さ = length / 2
    public final BlockFace dir;
    public final int dx, dz;   // 進行方向
    public final int lx, lz;   // 横方向
    public final double ox, oz; // 坂入口の原点（横方向はブロックの境目＝中心線）
    public boolean ready = false;

    private final Map<Long, Backup> backups = new LinkedHashMap<>();
    private static final BlockData AIR = Bukkit.createBlockData(Material.AIR);
    private static final BlockData BARRIER = Bukkit.createBlockData(Material.BARRIER);

    public Course(World world, int x0, int baseY, int z0, BlockFace dir, int length) {
        this.world = world;
        this.baseY = baseY;
        this.length = length;
        this.height = length / 2;
        this.dir = dir;
        this.dx = dir.getModX();
        this.dz = dir.getModZ();
        this.lx = -dz;
        this.lz = dx;
        this.ox = dx > 0 ? x0 : (dx < 0 ? x0 + 1 : x0);
        this.oz = dz > 0 ? z0 : (dz < 0 ? z0 + 1 : z0);
    }

    /** プレイヤーが下の壁に当たる位置 / 上の壁に当たる位置 */
    public double minT() { return -PAD + 0.45; }
    public double maxT() { return length + PLATFORM - 0.45; }

    // ---------- 座標変換 ----------

    public double t(Location l) {
        return (l.getX() - ox) * dx + (l.getZ() - oz) * dz;
    }

    public double s(Location l) {
        return (l.getX() - ox) * lx + (l.getZ() - oz) * lz;
    }

    /** 坂の表面の高さ(足を置ける高さ)。階段(2マス1段): 階段の前半=+0.5、後半とブロック=+1.0 */
    public double surfaceY(double t) {
        if (t < 0) return baseY;
        if (t >= length) return baseY + height;
        int k = (int) Math.floor(t / 2.0);
        double f = t - 2.0 * k;
        return baseY + k + (f < 0.5 ? 0.5 : 1.0);
    }

    public Location at(double t, double s) {
        return new Location(world, ox + dx * t + lx * s, surfaceY(t) + 0.05, oz + dz * t + lz * s);
    }

    public boolean contains(Location l) {
        if (l.getWorld() != world) return false;
        double t = t(l);
        double s = s(l);
        if (t < -PAD - 2 || t > length + PLATFORM + 1 || Math.abs(s) > HALF_W + 1.0) return false;
        double sy = surfaceY(t);
        return l.getY() >= sy - 4 && l.getY() <= sy + 6;
    }

    public boolean onSummit(Location l) {
        double t = t(l);
        return t >= length && t < length + PLATFORM
                && Math.abs(s(l)) <= HALF_W
                && l.getY() >= baseY + height - 0.6;
    }

    // ---------- 生成 / 復元 ----------

    private static long pack(int x, int y, int z) {
        return ((long) x & 0x3FFFFFFL) << 38 | ((long) z & 0x3FFFFFFL) << 12 | ((long) (y + 64) & 0xFFFL);
    }

    private void set(int x, int y, int z, BlockData data) {
        if (y < world.getMinHeight() || y >= world.getMaxHeight()) return;
        Block b = world.getBlockAt(x, y, z);
        backups.computeIfAbsent(pack(x, y, z), key -> new Backup(x, y, z, b.getBlockData()));
        b.setBlockData(data, false);
    }

    /** 列 k の足場ブロックを置く高さ */
    private int layerY(int k) {
        if (k < 0) return baseY - 1;
        if (k < length) return baseY + k / 2;
        return baseY + height - 1;
    }

    /** jj = -4..3（-4 と 3 が両脇のバリア、-3..2 が坂の6列） */
    private void buildColumn(int k, int jj, BlockData stair, BlockData block, BlockData fill,
                             BlockData pad, BlockData goal, BlockData wall) {
        double off = jj + 0.5;
        double cx = ox + dx * (k + 0.5) + lx * off;
        double cz = oz + dz * (k + 0.5) + lz * off;
        int x = (int) Math.floor(cx);
        int z = (int) Math.floor(cz);

        // 一番下の壁（敵に押し付けられる壁）
        if (k == -PAD - 1) {
            for (int i = -1; i < WALL_HEIGHT; i++) set(x, baseY + i, z, wall);
            for (int y = baseY - 2; y > baseY - 66 && y >= world.getMinHeight(); y--) {
                if (world.getBlockAt(x, y, z).getType().isSolid()) break;
                set(x, y, z, fill);
            }
            return;
        }

        // 頂上台の先の行き止まりの壁
        if (k == length + PLATFORM) {
            for (int i = 0; i < WALL_HEIGHT; i++) set(x, baseY + height + i, z, BARRIER);
            return;
        }

        // 両脇のバリア
        if (Math.abs(off) > HALF_W) {
            int start = layerY(k) + ((k >= 0 && k < length) ? 0 : 1);
            for (int i = 0; i < WALL_HEIGHT; i++) set(x, start + i, z, BARRIER);
            return;
        }

        int topY = layerY(k);
        BlockData top;
        if (k < 0) {
            top = pad;
        } else if (k < length) {
            top = (k % 2 == 0) ? stair : block;   // 偶数列=階段、奇数列=ブロック
        } else {
            top = goal;
        }

        set(x, topY, z, top);

        // 下を地面まで埋める
        for (int y = topY - 1; y > topY - 65 && y >= world.getMinHeight(); y--) {
            Block b = world.getBlockAt(x, y, z);
            if (b.getType().isSolid()) break;
            set(x, y, z, fill);
        }
        // 上の空間を空ける
        for (int y = topY + 1; y <= topY + 4; y++) {
            if (!world.getBlockAt(x, y, z).isEmpty()) {
                set(x, y, z, AIR);
            }
        }
    }

    public List<Runnable> buildJobs(BlockData stair, BlockData block, BlockData fill,
                                    BlockData pad, BlockData goal, BlockData wall) {
        List<Runnable> jobs = new ArrayList<>();
        for (int k = -PAD - 1; k <= length + PLATFORM; k++) {
            for (int jj = -4; jj <= 3; jj++) {
                final int fk = k, fj = jj;
                jobs.add(() -> buildColumn(fk, fj, stair, block, fill, pad, goal, wall));
            }
        }
        return jobs;
    }

    public List<Runnable> restoreJobs() {
        List<Runnable> jobs = new ArrayList<>(backups.size());
        for (Backup b : backups.values()) {
            jobs.add(() -> world.getBlockAt(b.x(), b.y(), b.z()).setBlockData(b.data(), false));
        }
        return jobs;
    }

    public void clearBackups() {
        backups.clear();
    }
}
