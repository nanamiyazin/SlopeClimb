# SlopeClimb / KOTH (Paper 26.1.2 / Java 25)

幅3マスの坂を登頂するミニゲーム。コマンドは `/koth`。

## ビルド
JDK 25 と Gradle 9.1 以上が必要です。 `gradle build` → `build/libs/SlopeClimb-1.1.1.jar` を `plugins/` へ。

## コマンド (権限: slopeclimb.admin)
- `/koth create <長さ>`
- `/koth enemy <mob> <押し戻す距離> [押す力] [数] [プレイヤー]`
- `/koth ally  <mob> <押す距離>    [押す力] [数] [プレイヤー]`
- `/koth clear [enemy|ally]` / `/koth remove` / `/koth info` / `/koth reload`

## 追加 mob
- 敵: warden, ender_dragon, wither, zombie, blaze, creeper, enderman, ravager, phantom
- 味方: horse, villager, giant_cat(巨大ねこ), giant_axolotl(巨大ウーパールーパー), cow, allay, sniffer
- 巨大系の大きさは config.yml の giant-scale（既定 3.0）
