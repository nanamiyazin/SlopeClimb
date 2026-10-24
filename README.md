# SlopeClimb / KOTH (Paper 26.1.2 / Java 25)

幅3マスの坂を登頂するミニゲーム。コマンドは `/koth`。

## ビルド
JDK 25 と Gradle 9.1 以上が必要です。 `gradle build` → `build/libs/SlopeClimb-1.2.0.jar` を `plugins/` へ。

## コマンド (権限: slopeclimb.admin)
- `/koth create <長さ>`
- `/koth enemy <mob> <押し戻す距離> [押す力] [数] [プレイヤー]`
- `/koth ally  <mob> <押す距離>    [押す力] [数] [プレイヤー]`
- `/koth clear [enemy|ally]` / `/koth remove` / `/koth info` / `/koth reload`

## 追加 mob
- 敵: warden, ender_dragon, wither, zombie, blaze, creeper, enderman, ravager, phantom
- 味方: horse, villager, giant_cat(巨大ねこ), giant_axolotl(巨大ウーパールーパー), cow, allay, sniffer
- 巨大系の大きさは config.yml の giant-scale（既定 3.0）

## ルール (v1.2.0)
- 坂は「階段 + ブロック」の水平2マスで1段上がる。両脇にバリアブロック（透明な壁）があり落ちない。
- 頂上の台に 15 秒（config の summit-hold-seconds）とどまれば勝ち。離れるとリセット。
- mob はプレイヤーに一度接触すると消滅する。
- 押されるときは 1 マスごとに効果音が鳴り、ゆっくり押される（push-speed）。
