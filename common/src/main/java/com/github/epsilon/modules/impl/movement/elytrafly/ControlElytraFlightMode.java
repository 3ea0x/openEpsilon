package com.github.epsilon.modules.impl.movement.elytrafly;

import com.github.epsilon.Constants;
import com.github.epsilon.events.impl.FallFlyingEvent;
import com.github.epsilon.events.impl.FireworkRotationEvent;
import com.github.epsilon.events.impl.KeyboardInputEvent;
import com.github.epsilon.events.impl.TravelEvent;
import com.github.epsilon.managers.rotation.RotationManager;
import com.github.epsilon.modules.impl.combat.elytra_combat.ElytraCombat;
import com.github.epsilon.modules.impl.combat.elytra_combat.ElytraCombatInput;
import com.github.epsilon.utils.player.FindItemResult;
import com.github.epsilon.utils.player.InvUtils;
import com.github.epsilon.utils.rotation.Priority;
import com.github.epsilon.utils.rotation.Rot2f;
import com.github.epsilon.utils.timer.TimerUtils;
import net.minecraft.core.component.DataComponents;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.phys.AABB;

public class ControlElytraFlightMode extends ElytraFlightMode {

    private static final double CEILING_PROBE_DISTANCE = 0.75;
    private static final double CEILING_PROBE_EPSILON = 1.0E-4;
    private static final float CEILING_ESCAPE_PITCH = 5.0f;
    /** 从进食开始算起，吃掉这么多 tick 后进入「真滑翔」收尾段。 */
    private static final int EAT_GLIDE_START_TICK = 30;
    /** InventoryMenu 的胸甲槽位下标（护甲槽为 5..8，顺序为头、胸、腿、脚）。 */
    private static final int CHEST_MENU_SLOT = 6;
    /** 剩余使用 tick 不大于该值时视为「这一份已经吃完」；留 3 tick 余量以容忍客户端与服务端的计数偏差。 */
    private static final int EAT_COMPLETE_REMAINING_TICKS = 3;
    /** 从进食开始算起，第多少 tick 模拟一次「按空格往上飞」。 */
    private static final int EAT_CLIMB_TICK = 20;
    /** 模拟按空格的持续 tick 数，相当于一次点按。 */
    private static final int EAT_CLIMB_TICKS = 5;
    /** 模拟按空格时的爬升俯仰；俯仰 -90 度时滑翔没有任何升力，必须保留水平分量。 */
    private static final float EAT_CLIMB_PITCH = -45f;

    private boolean hasFirstFirework;
    private boolean shouldJump;
    private boolean pendingFirework;
    /** 收尾段为真滑翔而穿在身上的鞘翅所在容器槽位；-1 表示本模式当前没有换上鞘翅。 */
    private int eatGlideElytraSlot = -1;
    /** 这一份进食已经吃到临界值（即将吃完），吃完后需要放掉一次右键。 */
    private boolean eatCompleted;
    /** 这一次进食是否已经模拟过按空格往上飞。 */
    private boolean eatClimbed;
    /** 还需要把「空格」算作按下的 tick 数。 */
    private int climbTicks;
    private final TimerUtils timer = new TimerUtils();

    public ControlElytraFlightMode(ElytraFly elytraFly) {
        super(elytraFly);
    }

    @Override
    public void onEnable() {
        hasFirstFirework = false;
        shouldJump = false;
        pendingFirework = false;
        timer.setMs(917813L);
        eatCompleted = false;
        eatClimbed = false;
        climbTicks = 0;
    }

    @Override
    public void onDisable() {
        shouldJump = false;
        pendingFirework = false;
        eatCompleted = false;
        eatClimbed = false;
        climbTicks = 0;
        releaseEatGlide();
    }

    /**
     * 收尾段靠真实鞘翅维持滑翔，再补发 START_FALL_FLYING 会让服务端丢掉滑翔状态，
     * 因此这段时间跳过 Unbreaking 的胸甲槽刷新。
     */
    @Override
    public void handleUnbreaking() {
        if (eatGlideElytraSlot >= 0) return;
        super.handleUnbreaking();
    }

    @Override
    public void onPlayerTick() {
        updateEatClimb();
        redirectRotation(); // 让你转你就受着
        updateEatKeyRelease();
        updateControl();
    }

    /**
     * 原版在 {@code Minecraft#handleKeybinds} 里按 {@code keyUse.isDown()} 决定要不要接着吃下一份，
     * 而该方法早于本模块的 tick 钩子执行。所以放键必须赶在那段逻辑之前，这里借 RightClickEvent
     * （注入点正好在 {@code handleKeybinds} 的右键处理之前）完成。
     */
    @Override
    public void onRightClick() {
        releaseUseKeyAfterEating();
    }

    @Override
    public void onTravel(TravelEvent event) {
        boolean avoidCeilingLift = shouldAvoidCeilingLift();

        if (avoidCeilingLift && mc.player.getDeltaMovement().y > 0.0) {
            mc.player.setDeltaMovement(mc.player.getDeltaMovement().multiply(1.0, 0.0, 1.0));
        }

        if (!avoidCeilingLift && !hasMoveInput() && (!elytraFly.useFireworks.getValue() || hasFirstFirework)) {
            mc.player.setDeltaMovement(0, 0.02, 0);
        }
    }

    @Override
    public void onKeyboardInput(KeyboardInputEvent event) {
        if (elytraFly.noSprint.getValue()) {
            event.setSprint(false);
            mc.player.setSprinting(false);
            mc.options.keySprint.setDown(false);
        }
        if (shouldJump) {
            event.setJump(true);
            shouldJump = false;
        }
    }

    @Override
    public void onFallFlying(FallFlyingEvent event) {
        event.setYaw(calcYaw());
        event.setPitch(calcPitch());
    }

    @Override
    public void onFireworkUpdate(FireworkRotationEvent event) {
        event.setYaw(calcYaw());
        event.setPitch(calcPitch());
    }

    private void updateControl() {
        if (elytraFly.noSprint.getValue() && mc.player.isSprinting()) return;

        boolean eating = isEating();

        // 进食期间不发射烟花；进食结束后补一次，避免延迟被吃掉导致中断加速。
        if (pendingFirework && !eating) {
            useTimedFirework();
        }

        FindItemResult elytra = InvUtils.find(Items.ELYTRA);

        // 进食收尾段（noEat 关闭 + armored）：不再做甲飞「换上再换回」的换甲，
        // 而是把鞘翅留在身上走真滑翔，避免穿甲进食沿用鞘翅速度被判 NoSlow。
        if (shouldEatGlide(eating)) {
            handleEatGlide(elytra);
            return;
        }
        // 进食结束、或条件不再成立：穿回胸甲，恢复甲飞时序。
        releaseEatGlide();

        if (!canGlide(elytra.found()) || mc.player.onGround()) {
            shouldJump = true;
            hasFirstFirework = false;
            pendingFirework = false;
            return;
        }

        if (elytraFly.armored.getValue()) {
            if (canStartFallFlying()) {
                jiaFei(elytra.slot());
            }
        } else {
            if (canStartFallFlying() && startFallFlying()) {
                shouldJump = true;
            }
            useTimedFirework();
        }
    }

    private void useTimedFirework() {
        if (ElytraCombat.INSTANCE.isEnabled() && !ElytraCombat.INSTANCE.shouldUseFirework()) {
            return;
        }
        if (!elytraFly.useFireworks.getValue()) return;
        if (isEating()) {
            // 进食期间无法使用烟花，先记下待发射，等进食结束再补。
            if (timer.hasDelayed(elytraFly.boostDelay.getValue())) {
                pendingFirework = true;
            }
            return;
        }
        if (!pendingFirework && !timer.hasDelayed(elytraFly.boostDelay.getValue())) return;
        pendingFirework = false;
        if (useFirework()) {
            hasFirstFirework = true;
            timer.reset();
        }
    }

    private boolean isEating() {
        return mc.player.isUsingItem() && mc.player.getUseItem().has(DataComponents.FOOD);
    }

    private void redirectRotation() {
        RotationManager.request(elytraFly.rotationType.getValue(), new Rot2f(calcYaw(), calcPitch()), 360, Priority.Highest);
    }

    private float calcYaw() {
        ElytraCombatInput combatInput = ElytraCombat.INSTANCE.getControlInput();
        if (combatInput != null) {
            return combatInput.yaw();
        }

        float yaw = mc.player.getYRot();

        boolean forward = mc.options.keyUp.isDown();
        boolean back = mc.options.keyDown.isDown();
        boolean left = mc.options.keyLeft.isDown();
        boolean right = mc.options.keyRight.isDown();

        if (forward && !back) {
            if (left && !right) {
                yaw -= 45f;
            } else if (right && !left) {
                yaw += 45f;
            }
        } else if (back && !forward) {
            yaw += 180f;
            if (left && !right) {
                yaw += 45f;
            } else if (right && !left) {
                yaw -= 45f;
            }
        } else if (left && !right) {
            yaw -= 90f;
        } else if (right && !left) {
            yaw += 90f;
        }
        return Mth.wrapDegrees(yaw);
    }

    private float calcPitch() {
        ElytraCombatInput combatInput = ElytraCombat.INSTANCE.getControlInput();
        if (combatInput != null) {
            return applyCeilingPitchGuard(combatInput.pitch());
        }

        float pitch = mc.player.getXRot();

        boolean climb = isClimbing();
        boolean jump = mc.options.keyJump.isDown();
        boolean sneak = mc.options.keyShift.isDown();
        boolean moving = mc.player.isMoving();

        if (climb) {
            // 模拟进食期间的爬升：固定 -45 度，效果等同于按住移动键时点一下空格。
            pitch = EAT_CLIMB_PITCH;
        } else if (sneak && jump) {
            pitch = -3f;
        } else if (jump) {
            pitch = moving ? -45f : -90f;
        } else if (sneak) {
            pitch = moving ? 45f : 90f;
        } else if (moving) {
            pitch = -1.9f;
        }
        return applyCeilingPitchGuard(pitch);
    }

    private float applyCeilingPitchGuard(float pitch) {
        if (shouldAvoidCeilingLift()) {
            return Math.max(pitch, CEILING_ESCAPE_PITCH);
        }
        return Mth.clamp(pitch, -90f, 90f);
    }

    private boolean shouldAvoidCeilingLift() {
        if (!mc.player.isFallFlying()) return false;

        AABB box = mc.player.getBoundingBox();
        double probeDistance = CEILING_PROBE_DISTANCE + Math.max(0.0, mc.player.getDeltaMovement().y);
        AABB ceilingProbe = new AABB(
                box.minX + CEILING_PROBE_EPSILON,
                box.maxY - CEILING_PROBE_EPSILON,
                box.minZ + CEILING_PROBE_EPSILON,
                box.maxX - CEILING_PROBE_EPSILON,
                box.maxY + probeDistance,
                box.maxZ - CEILING_PROBE_EPSILON
        );
        return !mc.level.noBlockCollision(mc.player, ceilingProbe);
    }

    private boolean hasMoveInput() {
        ElytraCombatInput combatInput = ElytraCombat.INSTANCE.getControlInput();
        if (combatInput != null) {
            return combatInput.hasMoveInput();
        }

        return mc.options.keyUp.isDown()
                || mc.options.keyDown.isDown()
                || mc.options.keyLeft.isDown()
                || mc.options.keyRight.isDown()
                || mc.options.keyJump.isDown()
                || mc.options.keyShift.isDown()
                || isClimbing();
    }

    /**
     * 是否进入进食收尾段的真滑翔：仅在 noEat 关闭（允许进食）且开启 armored 时生效，
     * 进食开始后的前 EAT_GLIDE_START_TICK tick 仍然沿用甲飞。
     */
    private boolean shouldEatGlide(boolean eating) {
        if (elytraFly.noEat.getValue() || !elytraFly.armored.getValue()) return false;
        if (mc.player.onGround() || !eating) return false;
        if (mc.player.getTicksUsingItem() < EAT_GLIDE_START_TICK) return false;
        // 容器界面下的槽位下标不属于 InventoryMenu，此时不做任何装备操作。
        return mc.player.containerMenu == mc.player.inventoryMenu;
    }

    /**
     * 跟踪进食收尾并放掉右键：一份食物吃到只剩 {@link #EAT_COMPLETE_REMAINING_TICKS} tick 时记为「这一份吃完」，
     * 待进食真正结束后清掉右键按下状态，玩家必须重新点击才能吃下一份。
     *
     * <p>只有吃到临界值的那一份才放键，中途被打断（松开右键、落地、被攻击）的进食不会抢掉玩家下一次主动点击。
     */
    private void updateEatKeyRelease() {
        if (elytraFly.noEat.getValue()) {
            eatCompleted = false;
            return;
        }

        if (isEating() && mc.player.getUseItemRemainingTicks() <= EAT_COMPLETE_REMAINING_TICKS) {
            eatCompleted = true;
        }

        releaseUseKeyAfterEating();
    }

    /**
     * 放掉一次右键。{@code isUsingItem()} 期间不能放，否则原版会在下一 tick 立刻
     * {@code releaseUsingItem} 把这次进食打断（食物不会生效）。
     */
    private void releaseUseKeyAfterEating() {
        if (!eatCompleted || mc.player.isUsingItem()) return;

        mc.options.keyUse.setDown(false);
        eatCompleted = false;
    }

    /**
     * 进食到 {@link #EAT_CLIMB_TICK} tick 时模拟一次「按空格往上飞」，用来绕过进食期间的 NoSlow 判定。
     *
     * <p>本模式下空格的作用就是抬头爬升（见 {@link #calcPitch()}：按空格取 -45/-90 度俯仰），
     * 所以这里不去动全局按键状态，只在 {@link #EAT_CLIMB_TICKS} 个 tick 内把空格算作按下，
     * 效果与玩家自己点一下空格一致，也不会干扰玩家自己按住的空格。
     */
    private void updateEatClimb() {
        if (climbTicks > 0) {
            climbTicks--;
        }

        if (!isEating()) {
            eatClimbed = false;
            return;
        }
        if (elytraFly.noEat.getValue() || !elytraFly.armored.getValue()) return;
        if (eatClimbed || mc.player.onGround()) return;
        if (mc.player.getTicksUsingItem() < EAT_CLIMB_TICK) return;

        eatClimbed = true;
        climbTicks = EAT_CLIMB_TICKS;
    }

    /** 当前是否处于模拟按空格的爬升段。 */
    private boolean isClimbing() {
        return climbTicks > 0;
    }

    /** 把鞘翅穿到身上并保持，让服务端也处于真实滑翔；鞘翅只在还在背包里时才动手，避免点到别的槽位。 */
    private void handleEatGlide(FindItemResult elytra) {
        if (eatGlideElytraSlot < 0) {
            if (!elytra.found()) return;
            eatGlideElytraSlot = toContainerSlot(elytra.slot());
            swapArmor(eatGlideElytraSlot);
        }

        if (canStartFallFlying() && startFallFlying()) {
            shouldJump = true;
        }
    }

    /**
     * 收尾段结束后把鞘翅换回原槽位并穿回胸甲。
     * 槽位状态被其它来源改动过时只记录日志并放弃点击，避免把无关物品点到装备槽；
     * 容器界面下先保留状态，等回到背包界面再还原（此间鞘翅仍穿在身上，状态自洽）。
     */
    private void releaseEatGlide() {
        int slot = eatGlideElytraSlot;
        if (slot < 0) return;

        if (mc.player == null || mc.level == null) {
            eatGlideElytraSlot = -1;
            return;
        }

        if (mc.player.containerMenu != mc.player.inventoryMenu) return;

        ItemStack chestStack = mc.player.containerMenu.getSlot(CHEST_MENU_SLOT).getItem();
        if (!LivingEntity.canGlideUsing(chestStack, EquipmentSlot.CHEST) || !isChestEquippable(mc.player.containerMenu.getSlot(slot).getItem())) {
            eatGlideElytraSlot = -1;
            Constants.LOGGER.warn("Elytra Fly: 胸甲或鞘翅槽位已被改动，跳过装备还原（记录槽位 {}，当前胸甲槽物品 {}）", slot, chestStack);
            return;
        }

        swapArmor(slot);
        eatGlideElytraSlot = -1;
    }

    /** 原槽位里放着的应该就是被换下来的胸甲，也可能是空槽（原本没有穿胸甲）。 */
    private boolean isChestEquippable(ItemStack stack) {
        if (stack.isEmpty()) return true;
        var equippable = stack.get(DataComponents.EQUIPPABLE);
        return equippable != null && equippable.slot() == EquipmentSlot.CHEST;
    }

    /** 背包下标（0..8 为快捷栏）换算为 InventoryMenu 的容器槽位。 */
    private int toContainerSlot(int inventorySlot) {
        return inventorySlot < 9 ? inventorySlot + 36 : inventorySlot;
    }

    private void jiaFei(int elytraSlot) {
        int elytra = elytraSlot < 9 ? elytraSlot + 36 : elytraSlot;

        swapArmor(elytra);
        if (startFallFlying()) {
            shouldJump = true;
        }
        useTimedFirework();
        swapArmor(elytra);
    }

}
