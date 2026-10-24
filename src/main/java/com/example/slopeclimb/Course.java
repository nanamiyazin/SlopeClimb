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
 * 幅3マスの坂コース。
 * コース座標: t = 進行方向の距離(坂の入口が0)、s = 横方向のずれ(中央が0、-1.5〜1.5が坂の上)。
 */
public final class Course {

    public static final int PAD = 3;       // スタート台の長さ
    public static final int PLATFORM = 3;  // 頂上台の長さ

    public record Backup(int x, int y, int z, BlockData data) {}

    public final World world;
    public final int baseY;
    public final int length;
    public final BlockFace dir;
    public final int dx, dz;   // 進行方向
    public final int lx, lz;   // 横方向
    public final double ox, oz; // 坂入口の原点
    public boolean ready = false;

    private final Map<Long, Backup> backups = new LinkedHashMap<>();
    private static final BlockData AIR = Bukkit.createBlockData(Material.AIR);

    public Course(World world, int x0, int baseY, int z0, BlockFace dir, int length) {
        this.world = world;
        this.baseY = baseY;
        this.length = length;
        this.dir = dir;
        this.dx = dir.getModX();
        this.dz = dir.getModZ();
        this.lx = -dz;
        this.lz = dx;
        this.ox = dx > 0 ? x0 : (dx < 0 ? x0 + 1 : x0 + 0.5);
        this.oz = dz > 0 ? z0 : (dz < 0 ? z0 + 1 : z0 + 0.5);
    }

    // ---------- 座標変換 ----------

    public double t(Location l) {
        return (l.getX() - ox) * dx + (l.getZ() - oz) * dz;
    }

    public double s(Location l) {
        return (l.getX() - ox) * lx + (l.getZ() - oz) * lz;
    }

    /** 坂の表面の高さ(足を置ける高さ) */
    public double surfaceY(double t) {
        if (t < 0) return baseY;
        if (t >= length) return baseY + length;
        int k = (int) Math.floor(t);
        double f = t - k;
        return baseY + k + (f >= 0.5 ? 1.0 : 0.5);
    }

    public Location at(double t, double s) {
        return new Location(world, ox + dx * t + lx * s, surfaceY(t) + 0.05, oz + dz * t + lz * s);
    }

    public boolean contains(Location l) {
        if (l.getWorld() != world) return false;
        double t = t(l);
        double s = s(l);
        if (t < -PAD - 2 || t > length + PLATFORM + 1 || Math.abs(s) > 2.0) return false;
        double sy = surfaceY(t);
        return l.getY() >= sy - 4 && l.getY() <= sy + 6;
    }

    public boolean onSummit(Location l) {
        double t = t(l);
        return t >= length && t < length + PLATFORM
                && Math.abs(s(l)) <= 1.6
                && l.getY() >= baseY + length - 0.6;
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

    private void buildColumn(int k, int j, BlockData stair, BlockData fill, BlockData pad, BlockData goal) {
        double cx = ox + dx * (k + 0.5) + lx * j;
        double cz = oz + dz * (k + 0.5) + lz * j;
        int x = (int) Math.floor(cx);
        int z = (int) Math.floor(cz);

        int topY;
        BlockData top;
        if (k < 0) {
            topY = baseY - 1;
            top = pad;
        } else if (k < length) {
            topY = baseY + k;
            top = stair;
        } else {
            topY = baseY + length - 1;
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

    public List<Runnable> buildJobs(BlockData stair, BlockData fill, BlockData pad, BlockData goal) {
        List<Runnable> jobs = new ArrayList<>();
        for (int k = -PAD; k < length + PLATFORM; k++) {
            for (int j = -1; j <= 1; j++) {
                final int fk = k, fj = j;
                jobs.add(() -> buildColumn(fk, fj, stair, fill, pad, goal));
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
