package com.github.epsilon.modules.impl.combat.elytra_combat.flight;

import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.VoxelShape;

import java.util.List;

/**
 * SlimefunHelper {@code ElytraBot} 的反应式机动移植版。
 *
 * <p>Slimefun 侧没有图搜索：它每 tick 生成一个几何方向（直冲目标、拉升、绕目标圆柱求切线），
 * 再用原版碰撞预演这一步会不会被削掉，被挡就换下一个机动，全部被挡时依然照直冲
 * （{@code ElytraBot} 只在目标丢失时把方向清零），所以本类不返回 null。</p>
 *
 * <p>移植时保持了两处原始判定：单步碰撞预演的 1e-4 平方距离容差，以及
 * {@code MathUtils.getTangentWithSameXZ} 的切线公式与「只取朝上切线再加 1e-2 抬升」的用法。
 * 绕飞半径用 {@code Stop Distance + 1} 代替 Slimefun 的 {@code 交战距离 + extra}，
 * 因为交战距离在本项目里属于行为层。</p>
 */
public final class SlimefunFlightNavigator {

    /** 预演距离与直飞探测同量级，保证机动筛选看得足够远。 */
    private static final double MIN_PROBE_DISTANCE = 4.0;
    private static final double MAX_PROBE_DISTANCE = 6.0;
    /** 预演位移被削掉超过该平方距离即视为被挡，与 ElytraBot 的判定阈值一致。 */
    private static final double CLIP_EPSILON_SQR = 1.0E-4;
    /** 绕飞半径在 Stop Distance 之上额外增加的格数，对应 combat-smooth-flight-circle-extra-range。 */
    private static final double CIRCLE_EXTRA_RANGE = 1.0;
    /** 绕飞外抛比例，对应 combat-smooth-flight-out-pull-ratio。 */
    private static final double OUT_PULL_RATIO = 1.0;
    /** 拉升机动瞄准目标上方的高度（格），对应 pull-up 方向生成。 */
    private static final double[] CLIMB_HEIGHTS = {4.0, 8.0, 14.0};
    /** 侧移机动的水平偏角（度）。 */
    private static final float[] SIDE_YAW_OFFSETS = {30.0f, -30.0f, 60.0f, -60.0f, 90.0f, -90.0f};

    private SlimefunFlightNavigator() {
    }

    /**
     * 生成当 tick 的飞行方向；永远返回一个有效方向。
     *
     * @param desiredVelocity 行为层给出的期望速度（长度会被保留）
     * @param targetPoint     目标预测位置，同时作为绕飞圆柱的中心
     * @param stopDistance    停止距离，用于推算绕飞半径
     */
    public static Vec3 navigate(LocalPlayer player, Vec3 desiredVelocity, Vec3 targetPoint, double stopDistance) {
        double length = desiredVelocity.length();
        if (length < 1.0E-8) {
            return desiredVelocity;
        }

        Vec3 desiredDirection = desiredVelocity.normalize();
        double probe = Math.clamp(length * 4.0, MIN_PROBE_DISTANCE, MAX_PROBE_DISTANCE);

        // 1) 直冲：原版单步碰撞预演没有被削就直接使用。
        if (isStepClear(player, desiredDirection, probe)) {
            return desiredVelocity;
        }

        // 2) 切线绕飞：沿目标圆柱的切线绕圈，对应 Slimefun 的 combat-smooth-flight 分支。
        for (Vec3 tangent : tangentDirections(player, targetPoint, stopDistance + CIRCLE_EXTRA_RANGE)) {
            if (isStepClear(player, tangent, probe)) {
                return tangent.scale(length);
            }
        }

        // 3) 拉升：瞄准目标上方，从障碍上方越过。
        Vec3 playerPos = player.position();
        for (double height : CLIMB_HEIGHTS) {
            Vec3 climb = targetPoint.add(0.0, height, 0.0).subtract(playerPos);
            if (climb.lengthSqr() < 1.0E-8) {
                continue;
            }
            Vec3 climbDirection = climb.normalize();
            if (isStepClear(player, climbDirection, probe)) {
                return climbDirection.scale(length);
            }
        }

        // 4) 侧移：绕开正面的障碍。
        for (float yawOffset : SIDE_YAW_OFFSETS) {
            Vec3 side = rotateYaw(desiredDirection, yawOffset);
            if (isStepClear(player, side, probe)) {
                return side.scale(length);
            }
        }

        // 5) 全部被挡：Slimefun 依然照直冲，受阻处理留给行为层状态机。
        return desiredVelocity;
    }

    /**
     * 单步原版碰撞预演：把候选方向按探测距离交给原版碰撞器，位移被削掉即视为被挡。
     */
    private static boolean isStepClear(LocalPlayer player, Vec3 direction, double probeDistance) {
        Vec3 movement = direction.normalize().scale(probeDistance);
        AABB box = player.getBoundingBox();
        List<VoxelShape> entityColliders = player.level().getEntityCollisions(player, box.expandTowards(movement));
        Vec3 resolved = Entity.collideBoundingBox(player, movement, box, player.level(), entityColliders);
        return resolved.distanceToSqr(movement) <= CLIP_EPSILON_SQR;
    }

    /**
     * 切线绕飞方向（复用 {@link SlimefunGeometry} 的 Slimefun 切线公式与筛选规则）。
     */
    private static Vec3[] tangentDirections(LocalPlayer player, Vec3 targetPoint, double radius) {
        return SlimefunGeometry.orbitDirections(targetPoint, player.getEyePosition(), radius, OUT_PULL_RATIO);
    }

    private static Vec3 rotateYaw(Vec3 direction, float degrees) {
        double radians = Math.toRadians(degrees);
        double cos = Math.cos(radians);
        double sin = Math.sin(radians);
        return new Vec3(
                direction.x * cos - direction.z * sin,
                direction.y,
                direction.x * sin + direction.z * cos
        ).normalize();
    }
}
