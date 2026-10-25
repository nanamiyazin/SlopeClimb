# SlopeClimb / KOTH (Paper 26.1.2 / Java 25)

幅3マスの坂を登頂するミニゲーム。コマンドは `/koth`。

## ビルド
JDK 25 と Gradle 9.1 以上が必要です。 `gradle build` → `build/libs/SlopeClimb-1.10.1.jar` を `plugins/` へ。

## コマンド (権限: slopeclimb.admin)
- `/koth create <長さ>`
- `/koth enemy <mob> <押し戻す距離> [押す力] [数] [プレイヤー]`
- `/koth ally  <mob> <押す距離>    [押す力] [数] [プレイヤー]`
- `/koth clear [enemy|ally]` / `/koth remove` / `/koth info` / `/koth reload`

## 追加 mob
- 敵: warden, ender_dragon, wither, zombie, blaze, creeper, enderman, ravager, phantom
- 味方: horse, villager, giant_cat(巨大ねこ), giant_axolotl(巨大ウーパールーパー), cow, allay, sniffer
- 巨大系の大きさは config.yml の giant-scale（既定 3.0）

## ルール (v1.10.1)
- 坂は「階段 + ブロック」の水平2マスで1段上がる。両脇にバリアブロック（透明な壁）があり落ちない。
- 頂上の台に 15 秒（config の summit-hold-seconds）とどまれば勝ち。離れるとリセット。
- mob はプレイヤーに一度接触すると消滅する。
- 押されるときは 1 マスごとに効果音が鳴り、ゆっくり押される（push-speed）。

## v1.10.1 の変更
- 坂の幅を6マスに変更（両脇のバリアも外側へ）。
- mob は経路探索をせず、直線にだけ動く（`mob-homing` で常時追尾に切り替え可）。
- `/koth enemy|ally <mob> <距離> [押す力] [数] [方向] [プレイヤー]` — 方向: front/back/left/right/random。
- 出現位置はプレイヤーから10マス（`spawn-distance`）離れたランダムな場所。
- 接触した mob は、押している間プレイヤーについていき、押し終わると消える。
- 頂上で勝利したらスタート台（一番下）へテレポート。
- ウォーデン・エンダードラゴン・ウィザーは遠くから現れ、カメラが追う乱入演出つき（`intro`）。

## v1.10.1 の変更
- 坂の素材を変更可能: `/koth create <長さ> [階段素材] [ブロック素材]`、または `/koth material <stair|block|fill|pad|goal|wall> <素材>`（config.yml に保存）。
- 触れた mob は、勝った側も負けた側もプレイヤーと一緒に押され、押す距離の分だけ押し終わったら消える。
- 一番下に壁（`materials.wall`）を追加。敵に壁へ押し付けられると、1マスごとにダメージゲージ（ボスバー）が減る。
- 壁や頂上の壁で動けなくなっても、押す距離の分だけ押し続けるまで mob は消えない。

## v1.10.1 の変更
- 押し合いが互角のとき、mob は消えない（誰も動かず、mob はそのまま直進を続ける）。
- mob の「押す距離（マス）」を「押す時間（秒）」に変更。`/koth enemy|ally <mob> <秒数> ...`（小数可、0.5〜120）。
  押される距離 = 秒数 × 20 × push-speed。

## v1.10.1 の変更
- mob は縦一列にまっすぐ進まず、斜めに進んで左右の壁で跳ね返る（`mob-min-angle`）。
- 必中の切り替え: `hit=true|false`（初期値は全 mob 必中。`sure-hit.default` / `sure-hit.<mob>`）。
- mob の速さ: `speed=マス/秒`（初期値は `mob-speed`）。
- 近づく側（方向）の指定を廃止。敵は坂の上側、味方は坂の下側から近づく。
- 押されている最中でも新しく触れた mob が押しに加わる。強い側が押し返し、同じ力なら互角で止まる。
- コマンド形式: `/koth enemy|ally <mob> <秒数> [押す力] [数] [speed=..] [hit=..] [player=..]`

## v1.10.1 の変更
- 押す力の合計: 同じ種類の mob どうしは足し算しない（種類ごとに最大の力を1つだけ数える）。違う種類は足し算する。
  例: ゾンビ5 + ゾンビ5 = 5 / ゾンビ5 + ゾンビ5 + ウィザー10 = 15。敵側・味方側それぞれで合計して比べる。

## v1.10.1 の変更
- ゲーム内コマンドで指定できるのは mob の数だけ: `/koth enemy <mob> [数]` / `/koth ally <mob> [数]`。
  押す時間・押す力・速さ・必中は config.yml の `enemy-mobs` / `ally-mobs`（mob ごと）と `mob-defaults` で設定する。
- 強化: `/koth buff <enemy|ally|off>`。味方の強化中は坂の左半分（進行方向に向かって左）のブロックが氷に、
  敵の強化中は右半分がソウルサンドに変わる。強化中はその側の押す力・押す時間・速度が倍率分上がる（`buff` 設定）。

## v1.10.1 の変更
- 坂をやめて、平らでまっすぐな幅6マスの道に変更（両脇バリア、スタート側に壁、奥にゴールの台）。
- `/koth create <長さ> [床素材]`、素材の種類は floor / fill / pad / goal / wall（stair・block は廃止）。

## v1.10.1 の変更
- mob はトロッコに乗り、出現したレーンをまっすぐにしか進まない（斜め移動と壁での跳ね返りは廃止、`mob-min-angle` も廃止）。
- ゴールまでの距離を画面上部のボスバーに表示（`goal-distance.enabled`）。
- ダメージゲージは数値表示（例: 70 / 100）。0 になると「-1win」と表示し、`reset-delay-ticks` 後に満タンへ戻る。

## v1.10.1 の変更
- 勝ち数・負け数を記録し、画面右のスコアボードに「名前  勝 N / 負 M」と表示（`scoreboard` 設定）。
  勝ち = ゴールを守りきったとき、負け = ダメージゲージが 0（-1win）になったとき。
- 記録はサーバーのスコアボード `koth_wins` / `koth_losses` に保存（再起動しても残る）。
- `/koth score` で戦績を表示、`/koth score reset [プレイヤー名]` でリセット。

## v1.10.1 の変更
- スコアボードにプレイヤー名を載せない。コースの上にいる間、各プレイヤーに自分の「勝ち N」「負け M」だけを表示する
  （そのプレイヤー専用のスコアボード。コースを離れると元のスコアボードに戻る）。
