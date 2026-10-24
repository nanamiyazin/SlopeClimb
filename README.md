# SlopeClimb / KOTH (Paper 26.1.2 / Java 25)

幅3マスの坂を登頂するミニゲーム。コマンドは `/koth`。

## ビルド
JDK 25 と Gradle 9.1 以上が必要です。 `gradle build` → `build/libs/SlopeClimb-1.5.0.jar` を `plugins/` へ。

## コマンド (権限: slopeclimb.admin)
- `/koth create <長さ>`
- `/koth enemy <mob> <押し戻す距離> [押す力] [数] [プレイヤー]`
- `/koth ally  <mob> <押す距離>    [押す力] [数] [プレイヤー]`
- `/koth clear [enemy|ally]` / `/koth remove` / `/koth info` / `/koth reload`

## 追加 mob
- 敵: warden, ender_dragon, wither, zombie, blaze, creeper, enderman, ravager, phantom
- 味方: horse, villager, giant_cat(巨大ねこ), giant_axolotl(巨大ウーパールーパー), cow, allay, sniffer
- 巨大系の大きさは config.yml の giant-scale（既定 3.0）

## ルール (v1.5.0)
- 坂は「階段 + ブロック」の水平2マスで1段上がる。両脇にバリアブロック（透明な壁）があり落ちない。
- 頂上の台に 15 秒（config の summit-hold-seconds）とどまれば勝ち。離れるとリセット。
- mob はプレイヤーに一度接触すると消滅する。
- 押されるときは 1 マスごとに効果音が鳴り、ゆっくり押される（push-speed）。

## v1.5.0 の変更
- 坂の幅を6マスに変更（両脇のバリアも外側へ）。
- mob は経路探索をせず、直線にだけ動く（`mob-homing` で常時追尾に切り替え可）。
- `/koth enemy|ally <mob> <距離> [押す力] [数] [方向] [プレイヤー]` — 方向: front/back/left/right/random。
- 出現位置はプレイヤーから10マス（`spawn-distance`）離れたランダムな場所。
- 接触した mob は、押している間プレイヤーについていき、押し終わると消える。
- 頂上で勝利したらスタート台（一番下）へテレポート。
- ウォーデン・エンダードラゴン・ウィザーは遠くから現れ、カメラが追う乱入演出つき（`intro`）。

## v1.5.0 の変更
- 坂の素材を変更可能: `/koth create <長さ> [階段素材] [ブロック素材]`、または `/koth material <stair|block|fill|pad|goal|wall> <素材>`（config.yml に保存）。
- 触れた mob は、勝った側も負けた側もプレイヤーと一緒に押され、押す距離の分だけ押し終わったら消える。
- 一番下に壁（`materials.wall`）を追加。敵に壁へ押し付けられると、1マスごとにダメージゲージ（ボスバー）が減る。
- 壁や頂上の壁で動けなくなっても、押す距離の分だけ押し続けるまで mob は消えない。

## v1.5.0 の変更
- 押し合いが互角のとき、mob は消えない（誰も動かず、mob はそのまま直進を続ける）。
- mob の「押す距離（マス）」を「押す時間（秒）」に変更。`/koth enemy|ally <mob> <秒数> ...`（小数可、0.5〜120）。
  押される距離 = 秒数 × 20 × push-speed。
