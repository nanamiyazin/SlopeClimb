package com.example.slopeclimb;

import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityChangeBlockEvent;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.event.entity.EntityExplodeEvent;
import org.bukkit.event.entity.EntityTargetLivingEntityEvent;
import org.bukkit.event.entity.EntityTeleportEvent;
import org.bukkit.event.entity.ExplosionPrimeEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerVelocityEvent;
import org.bukkit.event.vehicle.VehicleDamageEvent;
import org.bukkit.event.vehicle.VehicleDestroyEvent;

public final class MobListener implements Listener {

    private final GameManager game;

    public MobListener(GameManager game) {
        this.game = game;
    }

    /** 本人、または本人が撃った飛び道具なら true */
    private boolean ownedBySlopeMob(Entity e) {
        if (e instanceof Projectile pr && pr.getShooter() instanceof Entity shooter) {
            e = shooter;
        }
        return game.isSlopeMob(e);
    }

    /** コース用 mob は一切ダメージを与えない（押すだけ） */
    @EventHandler(ignoreCancelled = true)
    public void onDamage(EntityDamageByEntityEvent e) {
        if (ownedBySlopeMob(e.getDamager())) e.setCancelled(true);
    }

    /** エンダードラゴンの翼で吹き飛ばされないようにする（近くにコース用ドラゴンがいる間だけ） */
    @EventHandler(ignoreCancelled = true)
    public void onVelocity(PlayerVelocityEvent e) {
        for (Entity n : e.getPlayer().getNearbyEntities(25, 25, 25)) {
            if (n instanceof org.bukkit.entity.EnderDragon && game.isSlopeMob(n)) {
                e.setCancelled(true);
                return;
            }
        }
    }

    /** 味方 mob はプレイヤーを攻撃目標にしない */
    @EventHandler(ignoreCancelled = true)
    public void onTarget(EntityTargetLivingEntityEvent e) {
        if (e.getTarget() instanceof Player && game.roleOf(e.getEntity()) == GameManager.Role.ALLY) {
            e.setCancelled(true);
        }
    }

    /** クリーパー等の爆発でコースを壊さない */
    @EventHandler(ignoreCancelled = true)
    public void onPrime(ExplosionPrimeEvent e) {
        if (ownedBySlopeMob(e.getEntity())) e.setCancelled(true);
    }

    @EventHandler(ignoreCancelled = true)
    public void onExplode(EntityExplodeEvent e) {
        if (ownedBySlopeMob(e.getEntity())) e.setCancelled(true);
    }

    /** エンダーマン・ウィザー・ドラゴン・ラヴェジャー等のブロック破壊/持ち上げ防止 */
    @EventHandler(ignoreCancelled = true)
    public void onChangeBlock(EntityChangeBlockEvent e) {
        if (ownedBySlopeMob(e.getEntity())) e.setCancelled(true);
    }

    /** エンダーマンが勝手にテレポートしてコース外へ行かないようにする */
    @EventHandler(ignoreCancelled = true)
    public void onTeleport(EntityTeleportEvent e) {
        if (game.isSlopeMob(e.getEntity()) && !game.isInternalMove()) e.setCancelled(true);
    }

    /** 乱入演出中に抜けたプレイヤーのゲームモードと位置を戻す */
    @EventHandler
    public void onQuit(PlayerQuitEvent e) {
        game.endCinematicFor(e.getPlayer());
        game.records().forget(e.getPlayer());
    }

    /** トロッコは壊せない */
    @EventHandler(ignoreCancelled = true)
    public void onCartDamage(VehicleDamageEvent e) {
        if (game.isCart(e.getVehicle())) e.setCancelled(true);
    }

    @EventHandler(ignoreCancelled = true)
    public void onCartDestroy(VehicleDestroyEvent e) {
        if (game.isCart(e.getVehicle())) e.setCancelled(true);
    }

    /** 倒された mob はアイテムも経験値も落とさない */
    @EventHandler
    public void onDeath(EntityDeathEvent e) {
        if (game.isDying(e.getEntity())) {
            e.getDrops().clear();
            e.setDroppedExp(0);
        }
    }
}
