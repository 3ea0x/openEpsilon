package com.github.epsilon.modules.impl.movement;

import com.github.epsilon.assets.i18n.EpsilonTranslations;
import com.github.epsilon.events.bus.EventHandler;
import com.github.epsilon.events.impl.PacketEvent;
import com.github.epsilon.events.impl.PlayerTickEvent;
import com.github.epsilon.events.impl.SendPositionEvent;
import com.github.epsilon.events.impl.SlowdownEvent;
import com.github.epsilon.managers.NotificationManager;
import com.github.epsilon.managers.rotation.RotationManager;
import com.github.epsilon.modules.Category;
import com.github.epsilon.modules.Module;
import com.github.epsilon.settings.impl.BoolSetting;
import com.github.epsilon.settings.impl.EnumSetting;
import com.github.epsilon.utils.network.NetworkUtils;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.PacketFlow;
import net.minecraft.network.protocol.common.ClientboundPingPacket;
import net.minecraft.network.protocol.common.ServerboundPongPacket;
import net.minecraft.network.protocol.game.*;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.ItemUseAnimation;
import net.minecraft.world.item.Items;

import java.util.Queue;
import java.util.concurrent.LinkedBlockingQueue;

public class NoSlowdown extends Module {

    public static final NoSlowdown INSTANCE = new NoSlowdown();

    private NoSlowdown() {
        super("No Slowdown", Category.MOVEMENT);
    }

    private enum Mode {
        Vanilla,
        GrimBlink,
        GrimC0F,
        Grim1_2,
        Grim1_3
    }

    /** GrimC0F 的换手时序：取消 C0F → 换手 → 进食 → 结束后回补被扣下的包。 */
    private enum C0FStep {
        NONE,
        CANCEL_C0F,
        SWAP_HANDS,
        EATING
    }

    private final EnumSetting<Mode> mode = enumSetting("Mode", Mode.Vanilla);
    private final BoolSetting food = boolSetting("Food", true);
    private final BoolSetting bow = boolSetting("Bow", true, () -> !mode.is(Mode.GrimBlink));
    private final BoolSetting crossbow = boolSetting("Crossbow", true, () -> !mode.is(Mode.GrimBlink));
    private final BoolSetting cobweb = boolSetting("Cobweb", true, () -> mode.is(Mode.Vanilla));

    private int ticks;
    private boolean eating;
    private int useDuration = 32;

    private final Queue<Packet<?>> packets = new LinkedBlockingQueue<>();

    // GrimC0F 单独持有一份被扣下的包：它的回补走 send()，与 GrimBlink 的 handle() 语义不同，不能共用队列。
    private C0FStep c0fStep = C0FStep.NONE;
    private int noUsingItemTicks = 0;
    private final Queue<Packet<?>> c0fPackets = new LinkedBlockingQueue<>();

    public boolean isWorking() {
        return isEnabled() && mode.is(Mode.GrimBlink) && eating;
    }

    public void stop() {
        mc.options.keyUse.setDown(false);
        mc.gameMode.releaseUsingItem(mc.player);
    }

    @Override
    protected void onDisable() {
        flush();
        eating = false;
        ticks = 0;
        useDuration = 32;
        c0fStep = C0FStep.NONE;
        noUsingItemTicks = 0;
        releaseC0FPackets();
    }

    @EventHandler
    private void onSendPosition(SendPositionEvent event) {
        if (!mode.is(Mode.GrimBlink)) {
            return;
        }

        if (eating) {
            ticks++;
            if (Math.toIntExact(packets.stream().filter(packet -> packet instanceof ClientboundPingPacket).count()) > 150 && ticks > 1) {
                NotificationManager.INSTANCE.error(this.getTranslatedName(), EpsilonTranslations.Notifications.TRANSACTION_COUNT_TOO_HIGH.getTranslatedName());
                mc.options.keyUse.setDown(false);
                onDisable();
            }
        }

        if (mc.player.isUsingItem() && isFoodOrDrink(mc.player.getUseItem()) && !eating) {
            eating = true;
            ticks = 0;
            ItemStack useItem = mc.player.getUseItem();
            useDuration = useItem.getItem().getUseDuration(useItem, mc.player);
        }

        if (eating) {
            if (ticks == 1) {
                NetworkUtils.sendPacketNoEvent(new ServerboundPlayerActionPacket(ServerboundPlayerActionPacket.Action.SWAP_ITEM_WITH_OFFHAND, BlockPos.ZERO, Direction.DOWN));
                NetworkUtils.sendPacketNoEvent(new ServerboundPlayerActionPacket(ServerboundPlayerActionPacket.Action.RELEASE_USE_ITEM, BlockPos.ZERO, Direction.DOWN));
            }
            if (ticks == 2) {
                InteractionHand hand = mc.player.getUsedItemHand() == InteractionHand.OFF_HAND ? InteractionHand.MAIN_HAND : InteractionHand.OFF_HAND;
                mc.getConnection().send(new ServerboundUseItemPacket(hand, mc.level.getBlockStatePredictionHandler().startPredicting().currentSequence(), RotationManager.INSTANCE.getYaw(), RotationManager.INSTANCE.getPitch()));
            }
            if (ticks > useDuration + 3) {
                mc.getConnection().send(new ServerboundPlayerActionPacket(ServerboundPlayerActionPacket.Action.RELEASE_USE_ITEM, BlockPos.ZERO, Direction.DOWN));
                mc.options.keyUse.setDown(false);
                ticks = 0;
                eating = false;
            }
        }
    }

    @EventHandler
    private void onPacketReceive(PacketEvent.Receive event) {
        if (nullCheck() || mc.player.tickCount < 30 || !eating || !mode.is(Mode.GrimBlink)) return;

        Packet<?> packet = event.getPacket();

        if (packet instanceof ClientboundPlayerPositionPacket) {
            flush();
            return;
        }

        if (packet instanceof ClientboundSetHealthPacket
                || packet instanceof ClientboundSystemChatPacket
                || packet instanceof ClientboundMoveEntityPacket
                || packet instanceof ClientboundTeleportEntityPacket
                || packet instanceof ClientboundEntityEventPacket
                || packet instanceof ClientboundAddEntityPacket
                || packet instanceof ClientboundBlockUpdatePacket
                || packet instanceof ClientboundBlockEventPacket) {
            return;
        }

        if (packet.type().flow() == PacketFlow.CLIENTBOUND) {
            event.cancel();
            packets.add(packet);
        }
    }

    @EventHandler
    private void onPacketSend(PacketEvent.Send event) {
        if (mode.is(Mode.GrimBlink) && eating && event.getPacket() instanceof ServerboundPlayerActionPacket packet && packet.getAction() == ServerboundPlayerActionPacket.Action.RELEASE_USE_ITEM) {
            eating = false;
            ticks = 0;
            flush();
            NetworkUtils.sendPacketNoEvent(new ServerboundPlayerActionPacket(ServerboundPlayerActionPacket.Action.SWAP_ITEM_WITH_OFFHAND, BlockPos.ZERO, Direction.DOWN));
        }
    }

    @EventHandler
    private void onSlowdown(SlowdownEvent event) {
        if (!food.getValue() && mc.player.getUseItem().has(DataComponents.FOOD)) return;
        if ((!bow.getValue() || mode.is(Mode.GrimBlink)) && mc.player.getUseItem().is(Items.BOW)) return;
        if ((!crossbow.getValue() || mode.is(Mode.GrimBlink)) && mc.player.getUseItem().is(Items.CROSSBOW)) return;

        switch (mode.getValue()) {
            case Vanilla -> cancel(event);
            case GrimBlink -> grimBlink(event);
            case GrimC0F -> grimC0F(event);
            case Grim1_2 -> grim50(event);
            case Grim1_3 -> grim33(event);
        }
    }

    @EventHandler
    private void onPlayerTick(PlayerTickEvent.Pre event) {
        if (nullCheck() || !mode.is(Mode.GrimC0F)) return;

        if (c0fStep != C0FStep.EATING) {
            noUsingItemTicks = 0;
            return;
        }

        if (mc.player.isUsingItem()) {
            noUsingItemTicks = 0;
            return;
        }

        // 进食被服务端中断后，等 5 tick 再回补被扣下的包并换回主手。
        noUsingItemTicks++;
        if (noUsingItemTicks >= 5) {
            releaseC0FPackets();
            sendSwapOffhand();
        }
    }

    @EventHandler
    private void onC0FPacketSend(PacketEvent.Send event) {
        if (!mode.is(Mode.GrimC0F)) return;

        Packet<?> packet = event.getPacket();

        if (packet instanceof ServerboundPongPacket && c0fStep != C0FStep.NONE) {
            event.cancel();
            c0fPackets.add(packet);

            if (c0fStep == C0FStep.CANCEL_C0F) {
                c0fStep = C0FStep.SWAP_HANDS;
                sendSwapOffhand();
            }
        }

        if (packet instanceof ServerboundPlayerActionPacket actionPacket
                && actionPacket.getAction() == ServerboundPlayerActionPacket.Action.RELEASE_USE_ITEM
                && c0fStep == C0FStep.EATING) {
            releaseC0FPackets();
            sendSwapOffhand();
        }
    }

    @EventHandler
    private void onC0FPacketReceive(PacketEvent.Receive event) {
        if (!mode.is(Mode.GrimC0F)) return;
        if (event.getPacket() instanceof ClientboundContainerSetSlotPacket && c0fStep == C0FStep.SWAP_HANDS) {
            mc.options.keyUse.setDown(true);
            c0fStep = C0FStep.EATING;
        }
    }

    private void cancel(SlowdownEvent event) {
        event.setSlowdown(false);
    }

    private void grimBlink(SlowdownEvent event) {
        if (mc.player.isUsingItem() && mc.player.getUseItemRemainingTicks() < 30 && !(isFoodOrDrink(mc.player.getMainHandItem()) && isFoodOrDrink(mc.player.getOffhandItem())) && isFoodOrDrink(mc.player.getUseItem())) {
            event.setSlowdown(false);
            mc.player.setSprinting(true);
        }
    }

    private void grim50(SlowdownEvent event) {
        if (mc.player.getUseItemRemainingTicks() % 2 == 0 && mc.player.getUseItemRemainingTicks() <= 30) {
            event.setSlowdown(false);
        }
    }

    private void grim33(SlowdownEvent event) {
        if (mc.player.getUseItemRemainingTicks() % 3 == 0 && mc.player.getUseItemRemainingTicks() <= 30) {
            event.setSlowdown(false);
        }
    }

    /**
     * GrimC0F：先松手并换手，等服务端下发槽位更新后再由本体继续进食，从而避开 C0F 减速。
     * 对手持双份食物的情况直接放弃，避免换手后仍然触发减速。
     */
    private void grimC0F(SlowdownEvent event) {
        if (mc.player.getUseItemRemainingTicks() <= 0 || !isFoodOrDrink(mc.player.getUseItem())) {
            return;
        }

        InteractionHand oppositeHand = mc.player.getUsedItemHand() == InteractionHand.MAIN_HAND ? InteractionHand.OFF_HAND : InteractionHand.MAIN_HAND;
        if (isFoodOrDrink(mc.player.getItemInHand(oppositeHand))) {
            return;
        }

        if (c0fStep != C0FStep.EATING) {
            mc.options.keyUse.setDown(false);
        }

        if (c0fStep == C0FStep.NONE) {
            c0fStep = C0FStep.CANCEL_C0F;

            if (mc.getConnection() != null && mc.player.containerMenu != mc.player.inventoryMenu) {
                mc.getConnection().send(new ServerboundContainerClosePacket(mc.player.containerMenu.containerId));
            }
        } else if (c0fStep == C0FStep.EATING) {
            mc.player.setSprinting(true);
            event.setSlowdown(false);
        }
    }

    /** 回补 GrimC0F 扣下的包；回补后时序回到初始状态。 */
    private void releaseC0FPackets() {
        c0fStep = C0FStep.NONE;

        if (mc.getConnection() == null) {
            c0fPackets.clear();
            return;
        }
        Packet<?> packet;
        while ((packet = c0fPackets.poll()) != null) {
            mc.getConnection().send(packet);
        }
    }

    private void sendSwapOffhand() {
        if (mc.getConnection() != null) {
            mc.getConnection().send(new ServerboundPlayerActionPacket(
                    ServerboundPlayerActionPacket.Action.SWAP_ITEM_WITH_OFFHAND,
                    BlockPos.ZERO,
                    Direction.DOWN
            ));
        }
    }

    private boolean isFoodOrDrink(ItemStack stack) {
        ItemUseAnimation anim = stack.getUseAnimation();
        return anim == ItemUseAnimation.EAT || anim == ItemUseAnimation.DRINK;
    }

    private void flush() {
        if (mc.getConnection() == null) {
            packets.clear();
        } else {
            Packet packet;
            while ((packet = packets.poll()) != null) {
                packet.handle(mc.getConnection());
            }
        }
    }

    public boolean noWeb() {
        return isEnabled() && mode.is(Mode.Vanilla) && cobweb.getValue();
    }

}
