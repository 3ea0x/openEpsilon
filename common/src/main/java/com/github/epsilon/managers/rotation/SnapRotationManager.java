package com.github.epsilon.managers.rotation;

import com.github.epsilon.events.impl.PlayerTickEvent;
import com.github.epsilon.events.impl.SendPositionEvent;
import com.github.epsilon.utils.rotation.Rot2f;
import net.minecraft.network.protocol.game.ServerboundMovePlayerPacket;

import static com.github.epsilon.Constants.mc;

public class SnapRotationManager extends RotationManager {

    private Rot2f snappedRot = null;

    public SnapRotationManager() {
        super(RotationMode.SNAP);
    }

    /**
     * 切走前把快照角度发回服务端，否则上一次快照的目标角度会留在服务端无人恢复。
     * <p>
     * 这里不结束旋转：切换模式可能只是同一 tick 内另一个模块接手，进行中的请求由新模式继续。
     */
    @Override
    protected void onModeSwitchAway() {
        restoreSnappedRotation(snappedRot, false);
    }

    @Override
    protected void onRotationsSet() {
        snapToCurrentRotation();
    }

    private void snapToCurrentRotation() {
        if (snappedRot != null) {
            restoreSnappedRotation(snappedRot, false);
        }

        snappedRot = new Rot2f(mc.player.getYRot(), mc.player.getXRot());
        sendRotationPacket(rotations);
    }

    private void restoreSnappedRotation(Rot2f expectedSnappedRot, boolean finishRotation) {
        if (expectedSnappedRot == null || snappedRot != expectedSnappedRot) return;

        sendRotationPacket(snappedRot);
        snappedRot = null;

        if (finishRotation) {
            active = false;
            priority = 0;
            targetRotations = new Rot2f(mc.player.getYRot(), mc.player.getXRot());
        }
    }

    private void sendRotationPacket(Rot2f rotation) {
        if (rotation == null) return;

        float yaw = rotation.getYaw();
        float pitch = rotation.getPitch();
        if (Float.isNaN(yaw) || Float.isNaN(pitch)) return;

        mc.getConnection().send(new ServerboundMovePlayerPacket.PosRot(
                mc.player.position(), yaw, pitch,
                mc.player.onGround(), mc.player.horizontalCollision)
        );
    }

    @Override
    protected void onPlayerTick(PlayerTickEvent.Pre event) {
        Rot2f tickSnappedRot = snappedRot;
        super.onPlayerTick(event);
        restoreSnappedRotation(tickSnappedRot, true);
    }

    @Override
    protected void handleSendPosition(SendPositionEvent event) {
    }

    @Override
    protected void resetModeState() {
        snappedRot = null;
    }

}
