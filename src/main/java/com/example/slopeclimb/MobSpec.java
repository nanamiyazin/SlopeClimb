package com.example.slopeclimb;

import org.bukkit.Sound;

/**
 * mob の種類ごとの設定。
 * PATH/GLIDE : 坂の表面に沿って手動で滑らせる
 * FLY        : 坂の上を hover 分浮かせて手動で飛ばす
 * （全 mob とも AI は切って、直線でのみ動かす）
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
            case "giant_cat"     -> new MobSpec(key, "cat", giantScale, Move.GLIDE, 0, 0f, 0, false);
            case "giant_axolotl" -> new MobSpec(key, "axolotl", giantScale, Move.GLIDE, 0, 0f, 0, false);
            default              -> new MobSpec(key, key, 1.0, Move.GLIDE, 0, 0f, 0, false);
        };
    }

    /** 遠くから現れる乱入演出の対象か */
    public boolean intro() {
        return key.equals("warden") || key.equals("ender_dragon") || key.equals("wither");
    }

    public String jpName() {
        return switch (key) {
            case "warden" -> "ウォーデン";
            case "ender_dragon" -> "エンダードラゴン";
            case "wither" -> "ウィザー";
            default -> key;
        };
    }

    public Sound introSound() {
        return switch (key) {
            case "warden" -> Sound.ENTITY_WARDEN_ROAR;
            case "ender_dragon" -> Sound.ENTITY_ENDER_DRAGON_GROWL;
            default -> Sound.ENTITY_WITHER_SPAWN;
        };
    }
}
