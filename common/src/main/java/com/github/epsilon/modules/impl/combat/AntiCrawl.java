package com.github.epsilon.modules.impl.combat;

import com.github.epsilon.events.bus.EventHandler;
import com.github.epsilon.events.impl.PlayerTickEvent;
import com.github.epsilon.modules.Category;
import com.github.epsilon.settings.impl.BoolSetting;
import com.github.epsilon.settings.impl.EnumSetting;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.shapes.Shapes;
import com.github.epsilon.modules.Module;

public class AntiCrawl extends Module {

    public static final AntiCrawl INSTANCE = new AntiCrawl();

    final double[] xzOffset = new double[]{0.0, 0.3, -0.3};

    private final EnumSetting<While> whileSetting = enumSetting("While", While.Crawling);
    private final BoolSetting web = boolSetting("Web", true);
    public boolean work = false;

    private AntiCrawl() {
        super("Anti Crawl", Category.COMBAT);
    }

    @EventHandler
    public void onPlayerTick(PlayerTickEvent.Pre event) {
        this.work = false;
        if (mc.player.isFallFlying()) {
            return;
        }
        if (this.whileSetting.is(While.Always) && mc.level.getBlockState(mc.player.blockPosition()).getBlock() != Blocks.BEDROCK
                || mc.player.isVisuallyCrawling()
                || this.whileSetting.is(While.Mining) && isPlayerMining()) {
            for (double offset : this.xzOffset) {
                for (double offset2 : this.xzOffset) {
                    BlockPos pos = BlockPos.containing(mc.player.getX() + offset, mc.player.getY() + 1.2, mc.player.getZ() + offset2);
                    if (this.canBreak(pos)) {
                        PacketMine.INSTANCE.mine(pos);
                        this.work = true;
                        return;
                    }
                    if (this.web.getValue()) {
                        BlockPos webPos = BlockPos.containing(mc.player.getX() + offset, mc.player.getY(), mc.player.getZ() + offset2);
                        if (mc.level.getBlockState(webPos).getBlock() == Blocks.COBWEB && this.canBreak(webPos)) {
                            PacketMine.INSTANCE.mine(webPos);
                            this.work = true;
                            return;
                        }
                    }
                }
            }
        }
    }

    private boolean canBreak(BlockPos pos) {
        BlockState state = mc.level.getBlockState(pos);
        if (!mc.player.isCreative() && state.getDestroySpeed(mc.level, pos) < 0) return false;
        return state.getCollisionShape(mc.level, pos) != Shapes.empty();
    }

    private boolean isPlayerMining() {
        return PacketMine.INSTANCE.isEnabled() && PacketMine.targetPos!= null;
    }

    private static enum While {
        Crawling,
        Mining,
        Always;
    }
}
