package com.github.epsilon.modules.impl.combat.elytra_combat.flight;

import net.minecraft.world.phys.Vec3;

import java.util.Arrays;

/**
 * SlimefunHelper 侧共用的几何工具移植。
 *
 * <p>来源：{@code MathUtils.getTangentWithSameXZ} / {@code getVerticalWithSameY}，以及
 * {@code ElytraBot} 里对切线「抬升 1e-2、只取朝上、按 out-pull 叠加背离分量」的用法。</p>
 */
public final class SlimefunGeometry {

    /** 切线抬升量，对应 ElytraBot 里 {@code vec3d3.add(0, 1E-2, 0)}。 */
    private static final double TANGENT_LIFT = 1.0E-2;

    private SlimefunGeometry() {
    }

    /**
     * 眼睛到「以 center 为轴的竖直圆柱」的两条水平切线方向（未抬升、未过滤）。
     * <p>点已经在圆柱内时退化为水平垂直方向的两个反向。</p>
     */
    public static Vec3[] tangentVectors(Vec3 center, Vec3 point, double radius) {
        Vec3 relative = point.subtract(center);
        double relativeLength = relative.length();
        if (relativeLength < 1.0E-8) {
            return new Vec3[0];
        }

        double radiusSqr = radius * radius;
        Vec3 perpendicular = horizontalPerpendicular(relative);
        if (relativeLength * relativeLength <= radiusSqr) {
            return new Vec3[]{perpendicular, perpendicular.reverse()};
        }

        double cutLine = radiusSqr / relativeLength;
        double cutLength = Math.sqrt(Math.max(0.0, radiusSqr - cutLine * cutLine));
        Vec3 cutPoint = relative.normalize().scale(cutLine);
        return new Vec3[]{
                cutPoint.add(perpendicular.scale(cutLength)).subtract(relative),
                cutPoint.subtract(perpendicular.scale(cutLength)).subtract(relative)
        };
    }

    /**
     * 与 {@code MathUtils.getVerticalWithSameY} 一致：取同一水平面上的垂直方向。
     */
    public static Vec3 horizontalPerpendicular(Vec3 vector) {
        Vec3 direction = vector.normalize();
        if (Math.abs(direction.x) < 1.0E-8 && Math.abs(direction.z) < 1.0E-8) {
            return new Vec3(1.0, 0.0, 0.0);
        }
        return new Vec3(direction.z, 0.0, -direction.x).normalize();
    }

    /**
     * 把一条原始切线整理成可用方向：先抬升 1e-2，只保留朝上的切线（否则返回 null），
     * 再按 out-pull 比例叠加背离目标中心的水平分量。
     *
     * @param tangent           原始切线方向
     * @param outwardHorizontal 从目标中心指向自己的水平分量（可为零向量）
     * @param outPullRatio      外抛比例，0 表示不叠加
     */
    public static Vec3 finishTangent(Vec3 tangent, Vec3 outwardHorizontal, double outPullRatio) {
        Vec3 candidate = tangent.add(0.0, TANGENT_LIFT, 0.0);
        if (candidate.y <= 0.0) {
            return null;
        }
        if (Math.abs(outPullRatio) > 1.0E-6 && outwardHorizontal.lengthSqr() > 1.0E-8) {
            candidate = candidate.add(outwardHorizontal.normalize().scale(outPullRatio));
        }
        return candidate.lengthSqr() > 1.0E-8 ? candidate.normalize() : null;
    }

    /**
     * 切线方向集合（已抬升 + 外抛过滤），保持 Slimefun 的 first / second 顺序。
     */
    public static Vec3[] orbitDirections(Vec3 center, Vec3 point, double radius, double outPullRatio) {
        Vec3 outward = point.subtract(center).multiply(1.0, 0.0, 1.0);
        Vec3[] tangents = tangentVectors(center, point, radius);
        Vec3[] finished = new Vec3[tangents.length];
        int count = 0;
        for (Vec3 tangent : tangents) {
            Vec3 direction = finishTangent(tangent, outward, outPullRatio);
            if (direction != null) {
                finished[count++] = direction;
            }
        }
        return Arrays.copyOf(finished, count);
    }
}
