package com.example.slopeclimb;

/**
 * mob の種類ごとの設定。
 * PATH  : 通常AI + Pathfinder で歩かせる
 * GLIDE : AIを切って、坂の表面に沿って手動で滑らせる（脳AIが邪魔になる mob 用）
 * FLY   : AIを切って、坂の上を hover 分浮かせて手動で飛ばす
 */
public record MobSpec(String key, String entityKey, double scale, Move move,
                      double hover, float yawOffset, double contactRadius, boolean silent) {

    public enum Move { PATH, GLIDE, FLY }

    public static MobSpec of(String key, double giantScale) {
        return switch (key) {
            case "warden"        -> new MobSpec(key, "warden", 1.0, Move.GLIDE, 0, 0f, 0, true);
            case "ender_dragon"  -> new MobSpec(key, "ender_dragon", 1.0, Move.FLY, 2.0, 180f, 4.5, true);
            case "wither"        -> new MobSpec(key, "wither", 1.0, Move.FLY, 1.5, 0f, 0, true);
            case "blaze"         -> new MobSpec(key, "blaze", 1.0, Move.FLY, 1.2, 0f, 0, false);
            case "phantom"       -> new MobSpec(key, "phantom", 1.0, Move.FLY, 1.0, 0f, 0, false);
            case "allay"         -> new MobSpec(key, "allay", 1.0, Move.FLY, 0.8, 0f, 0, false);
            case "sniffer"       -> new MobSpec(key, "sniffer", 1.0, Move.GLIDE, 0, 0f, 0, false);
            case "villager"      -> new MobSpec(key, "villager", 1.0, Move.GLIDE, 0, 0f, 0, false);
            case "giant_cat"     -> new MobSpec(key, "cat", giantScale, Move.PATH, 0, 0f, 0, false);
            case "giant_axolotl" -> new MobSpec(key, "axolotl", giantScale, Move.GLIDE, 0, 0f, 0, false);
            default              -> new MobSpec(key, key, 1.0, Move.PATH, 0, 0f, 0, false);
        };
    }
}
