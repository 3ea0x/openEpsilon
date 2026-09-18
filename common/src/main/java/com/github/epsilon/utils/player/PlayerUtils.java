package com.github.epsilon.utils.player;

import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.util.Mth;
import net.minecraft.world.level.block.WebBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.Shapes;

import static com.github.epsilon.Constants.mc;

public class PlayerUtils {

    /**
     * 判断本地玩家是否正在使用食物。
     *
     * @return 判断结果
     */
    public static boolean isEating() {
        return (mc.player.getMainHandItem().getComponents().has(DataComponents.FOOD) || mc.player.getOffhandItem().getComponents().has(DataComponents.FOOD)) && mc.player.isUsingItem();
    }

    /**
     * 判断本地玩家的包围盒是否与蜘蛛网相交。
     *
     * @return 判断结果
     */
    public static boolean isInWeb() {
        AABB box = mc.player.getBoundingBox().deflate(1.0E-6);

        int minX = Mth.floor(box.minX);
        int minY = Mth.floor(box.minY);
        int minZ = Mth.floor(box.minZ);
        int maxX = Mth.floor(box.maxX);
        int maxY = Mth.floor(box.maxY);
        int maxZ = Mth.floor(box.maxZ);

        BlockPos.MutableBlockPos mutablePos = new BlockPos.MutableBlockPos();
        for (int x = minX; x <= maxX; x++) {
            for (int y = minY; y <= maxY; y++) {
                for (int z = minZ; z <= maxZ; z++) {
                    mutablePos.set(x, y, z);
                    if (mc.level.getBlockState(mutablePos).getBlock() instanceof WebBlock) {
                        return true;
                    }
                }
            }
        }

        return false;
    }

    /**
     * 判断本地玩家的包围盒是否与实体方块相交。
     *
     * @return 判断结果
     */
    public static boolean isInBlock() {
        AABB box = mc.player.getBoundingBox().deflate(1.0E-6);

        int minX = Mth.floor(box.minX);
        int minY = Mth.floor(box.minY);
        int minZ = Mth.floor(box.minZ);
        int maxX = Mth.floor(box.maxX);
        int maxY = Mth.floor(box.maxY);
        int maxZ = Mth.floor(box.maxZ);

        BlockPos.MutableBlockPos mutablePos = new BlockPos.MutableBlockPos();
        for (int x = minX; x <= maxX; x++) {
            for (int y = minY; y <= maxY; y++) {
                for (int z = minZ; z <= maxZ; z++) {
                    mutablePos.set(x, y, z);
                    if (mc.level.getBlockState(mutablePos).isSolidRender()) {
                        return true;
                    }
                }
            }
        }

        return false;
    }

    /**
     * 判断指定位置是否是“可破坏的实体方块”：非空气、非负数硬度（非创造模式下），且碰撞形状非空。
     *
     * @param pos 待判断的方块坐标
     * @return 可以破坏时返回 true
     */
    public static boolean isValidBlock(BlockPos pos) {
        BlockState state = mc.level.getBlockState(pos);
        if (state.isAir()) return false;
        if (!mc.player.isCreative() && state.getDestroySpeed(mc.level, pos) < 0) return false;
        return state.getCollisionShape(mc.level, pos) != Shapes.empty();
    }

    /**
     * 按当前按键输入计算水平速度分量，用于把移动方向从按键输入转换成世界坐标速度。
     *
     * @param hSpeed 期望的水平速度（格/秒）
     * @return 世界坐标下的水平速度向量；没有方向输入时返回零向量
     */
    public static Vec3 getHorizontalVelocity(double hSpeed) {
        float yaw = mc.player.getYHeadRot();
        double rad = Math.toRadians(yaw + 90);
        float forward = 0, sideways = 0;
        if (mc.options.keyUp.isDown()) forward += 1;
        if (mc.options.keyDown.isDown()) forward -= 1;
        if (mc.options.keyLeft.isDown()) sideways += 1;
        if (mc.options.keyRight.isDown()) sideways -= 1;
        if (forward == 0 && sideways == 0) return Vec3.ZERO;
        double h = hSpeed / 20.0;
        double f = forward, s = sideways;
        double len = Math.sqrt(f * f + s * s);
        f /= len;
        s /= len;
        double sin = Math.sin(rad);
        double cos = Math.cos(rad);
        return new Vec3((f * cos + s * sin) * h, 0, (f * sin - s * cos) * h);
    }

}
