package com.github.epsilon.modules.impl.combat;

import com.github.epsilon.events.bus.EventHandler;
import com.github.epsilon.events.bus.EventPriority;
import com.github.epsilon.events.impl.AttackEntityEvent;
import com.github.epsilon.events.impl.ClientTickEvent;
import com.github.epsilon.events.impl.KeyboardInputEvent;
import com.github.epsilon.events.impl.PacketEvent;
import com.github.epsilon.events.impl.PlayerTickEvent;
import com.github.epsilon.events.impl.SendPositionEvent;
import com.github.epsilon.modules.Category;
import com.github.epsilon.modules.Module;
import com.github.epsilon.modules.impl.movement.Velocity;
import com.github.epsilon.settings.impl.BoolSetting;
import com.github.epsilon.settings.impl.DoubleSetting;
import com.github.epsilon.settings.impl.EnumSetting;
import com.github.epsilon.settings.impl.IntSetting;
import com.github.epsilon.utils.network.NetworkUtils;
import com.github.epsilon.utils.player.FindItemResult;
import com.github.epsilon.utils.player.InvUtils;
import com.github.epsilon.utils.player.PlayerUtils;
import com.github.epsilon.utils.timer.TimerUtils;
import net.minecraft.core.BlockPos;
import net.minecraft.network.protocol.game.ClientboundPlayerPositionPacket;
import net.minecraft.network.protocol.game.ServerboundMovePlayerPacket;
import net.minecraft.network.protocol.game.ServerboundPlayerCommandPacket;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.AABB;

/**
 * Criticals 模块，移植自 SlimefunHelper 的 {@code Criticals}。
 *
 * <p>除了保留原有的“下落途中停疾跑”暴击方式外，还提供几种基于发包的假暴击：
 * <ul>
 *     <li>{@link Mode#Packet}：攻击时停疾跑并连续发送两次微抬坐标包；服务端会算出一次向下的位移，从而判定为下落暴击。</li>
 *     <li>{@link Mode#Freeze}：冻结模式，持续让服务端认为玩家在空中（受移动管理器限制，这里只做近似实现）。</li>
 *     <li>{@link Mode#GrimGroundSimulation}：发送伪造位移包逼服务端拉回，拉回后再补刀，使服务端在拉回过程中判定暴击。</li>
 *     <li>{@link Mode#GrimWall}：贴墙或顶头时发送两次微抬包，然后重新同步坐标。</li>
 * </ul>
 *
 * <p>与原版模块的差异：Epsilon 没有 SlimefunHelper 的 {@code LegalMovementManager} /
 * {@code FloatingUtils} 移动流水线，因此 {@link Mode#Freeze} 只能通过改写即将发出的
 * 位置包来近似；{@code ModulePreset} 配置预设在本仓库也不存在，故未移植。
 */
public class Criticals extends Module {

    public static final Criticals INSTANCE = new Criticals();

    /** Packet 模式下两次微抬高度，制造服务端视角下的一次下落位移。 */
    private static final double PACKET_CRIT_FIRST_HEIGHT = 5.0E-4;
    private static final double PACKET_CRIT_SECOND_HEIGHT = 1.0E-4;

    /** Grim Ground Simulation 使用的坐标微调常量。 */
    private static final double MIN_HEIGHT_THRESHOLD = 1.0E-4;
    private static final double MIN_HEIGHT_DELTA = 1.0E-5;

    /** 触发拉回后超过该 tick 数仍未收到拉回时强制补刀，避免攻击永久丢失。 */
    private static final int RE_ATTACK_TIMEOUT_TICKS = 20;

    public enum Mode {
        Packet,
        Freeze,
        GrimGroundSimulation,
        GrimWall,
        Test
    }

    public enum SetBackTrigger {
        Simulation,
        CrashPackets
    }

    private final EnumSetting<Mode> mode = enumSetting("Mode", Mode.Packet);

    private final BoolSetting groundOnly = boolSetting("Ground Only", false, this::usesGroundOnly);
    private final BoolSetting targetOnly = boolSetting("Target Only", false, this::usesGroundOnly);
    private final BoolSetting movementOkFreeze = boolSetting("Movement Ok Freeze", false, this::isFreeze);
    private final BoolSetting movementOkGround = boolSetting("Movement Ok Ground", false, this::isGrimGroundSimulation);
    private final BoolSetting autoFakeGround = boolSetting("Auto Fake Ground", true, this::isGrimGroundSimulation);
    private final BoolSetting autoWalk = boolSetting("Auto Walk", true, this::isGrimGroundSimulation);
    private final EnumSetting<SetBackTrigger> setBackType =
            enumSetting("Set Back Mode", SetBackTrigger.Simulation, this::isGrimGroundSimulation);
    private final BoolSetting delaySwap = boolSetting("Delay Swap", false, this::isGrimGroundSimulation);
    private final BoolSetting inWallPacket = boolSetting("In Wall Packet", false, this::usesInWallPacket);
    private final BoolSetting inAirFreeze = boolSetting("In Air Freeze", true, this::isFreeze);
    private final BoolSetting inWallFreeze = boolSetting("In Wall Freeze", true, this::isFreeze);
    private final DoubleSetting customInWallHeightFreeze =
            doubleSetting("Custom In Wall Height", 0.05, 0.0, 1.0, 0.001, this::isFreeze);
    private final DoubleSetting customInWallHeightPacket =
            doubleSetting("Custom In Wall Packet Height", 0.05, 0.0, 1.0, 0.001, this::usesInWallPacket);
    private final IntSetting criticalCooldown = intSetting("Critical Cooldown", 10, 0, 100, 1);

    private final TimerUtils cooldownTimer = new TimerUtils();

    /** 原版下落 tick 统计，供 KillAura 等模块判断暴击时机。 */
    public int fallTicks;
    private boolean stopSprinting;

    /** 客户端已落地、但即将发出的移动包仍标记为空中时的瞬间。 */
    private boolean lastOnGroundT;

    /** 缓存的攻击目标；Grim Ground Simulation 收到拉回后补刀。 */
    private volatile LivingEntity cachedTarget;
    private volatile int cachedAtTick;
    private volatile boolean pendingReAttack = false;
    private ItemStack cachedHandStack;
    private boolean reAttacking;
    private boolean fakeMovementThisTick;
    private int walkCount;

    private Criticals() {
        super("Criticals", Category.COMBAT);
    }

    private boolean isFreeze() {
        return mode.is(Mode.Freeze);
    }

    private boolean isGrimGroundSimulation() {
        return mode.is(Mode.GrimGroundSimulation);
    }

    private boolean usesGroundOnly() {
        return isFreeze() || isGrimGroundSimulation();
    }

    private boolean usesInWallPacket() {
        return isGrimGroundSimulation() || mode.is(Mode.GrimWall) || mode.is(Mode.Test);
    }

    @Override
    protected void onEnable() {
        cooldownTimer.reset();
        clearCache();
    }

    @Override
    protected void onDisable() {
        fallTicks = 0;
        stopSprinting = false;
        lastOnGroundT = false;
        fakeMovementThisTick = false;
        clearCache();
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    private void onClientTick(ClientTickEvent.Pre event) {
        if (nullCheck() || !canCrit() || mc.player.fallDistance >= 1.0f) {
            fallTicks = 0;
        } else {
            fallTicks++;
        }
    }

    @EventHandler(priority = EventPriority.LOWEST)
    private void prepareSprintStop(ClientTickEvent.Pre event) {
        stopSprinting = !nullCheck()
                && fallTicks > 0
                && fallTicks < 3
                && mc.player.isSprinting()
                && KillAura.INSTANCE.target != null
                && Velocity.INSTANCE.attackQueue == 0;
    }

    @EventHandler(priority = EventPriority.LOWEST)
    private void onKeyboardInput(KeyboardInputEvent event) {
        // 等待服务端拉回期间压制输入，避免真实移动包与伪造包冲突
        if (cachedTarget != null) {
            if (mc.player.tickCount - cachedAtTick <= 3) {
                event.setSprint(false);
                event.setForward(0.0f);
            }
            if (autoWalk.getValue() && !hasMovementInput()) {
                walkCount++;
                event.setStrafe(walkCount % 2 == 0 ? 1.0f : -1.0f);
            }
        }

        if (!stopSprinting) return;
        stopSprinting = false;

        if (fallTicks > 0
                && fallTicks < 3
                && canCrit()
                && mc.player.isSprinting()
                && KillAura.INSTANCE.target != null
                && Velocity.INSTANCE.attackQueue == 0
                && event.getForward() > 0.0f) {
            event.setSprint(false);
            mc.player.setSprinting(false);
            mc.options.keySprint.setDown(false);
        }
    }

    private boolean canCrit() {
        return mc.player.fallDistance > 0.0 && !mc.player.onGround() && !mc.player.onClimbable() && !mc.player.isInWater() && !mc.player.isMobilityRestricted()
                && !mc.player.isPassenger();
    }

    // ==================== 发包暴击 ====================

    /**
     * 攻击时按当前模式伪造暴击；真正需要延迟的攻击会被取消并在收到拉回后补发。
     */
    @EventHandler
    private void onAttack(AttackEntityEvent event) {
        if (reAttacking || event.isCancelled() || nullCheck()) return;

        // 已有一发攻击在等服务端拉回，延后本次攻击避免打乱补刀
        if (cachedTarget != null) {
            event.cancel();
            return;
        }

        if (!(event.getEntity() instanceof LivingEntity target)) return;
        if (cannotCrit()) return;
        // 已经在真实下落中，无需伪造
        if (mc.player.fallDistance > 1.0E-6) return;

        boolean canRun = cooldownTimer.passedMillise(criticalCooldown.getValue() * 50L);
        cooldownTimer.reset();
        if (!canRun) return;

        handleCritical(event, target);
    }

    private void handleCritical(AttackEntityEvent event, LivingEntity target) {
        if (inWallPacket.getValue()
                && !isFreeze()
                && !mode.is(Mode.Packet)
                && (isInWall() || isUnderBlock())) {
            handleCriticalWall();
            return;
        }

        double x = mc.player.getX();
        double y = mc.player.getY();
        double z = mc.player.getZ();

        switch (mode.getValue()) {
            case Packet -> handlePacketCritical(x, y, z);
            case Freeze -> handleFreezeCritical();
            case GrimGroundSimulation -> handleGrimGroundSimulation(event, target, x, y, z);
            case GrimWall -> {
                if (mc.player.onGround() && (isInWall() || isUnderBlock())) {
                    handleCriticalWall();
                }
            }
            default -> {
            }
        }
    }

    /** Packet 模式：停疾跑后发送两次微抬包，服务端据此得到一段向下的位移。 */
    private void handlePacketCritical(double x, double y, double z) {
        stopSprintingForCritical();
        NetworkUtils.sendPacketNoEvent(new ServerboundMovePlayerPacket.Pos(
                x, y + PACKET_CRIT_FIRST_HEIGHT, z, false, false));
        NetworkUtils.sendPacketNoEvent(new ServerboundMovePlayerPacket.Pos(
                x, y + PACKET_CRIT_SECOND_HEIGHT, z, false, false));
    }

    /** 贴墙暴击：向上发两包后重新同步真实坐标。 */
    private void handleCriticalWall() {
        double x = mc.player.getX();
        double y = mc.player.getY();
        double z = mc.player.getZ();
        double height = customInWallHeightPacket.getValue();
        NetworkUtils.sendPacketNoEvent(new ServerboundMovePlayerPacket.Pos(x, y + height, z, false, false));
        NetworkUtils.sendPacketNoEvent(new ServerboundMovePlayerPacket.Pos(x, y + height * 0.75, z, false, false));
        resyncPos();
    }

    /**
     * 冻结模式：贴墙时先在客户端抬高一格边缘，再额外发送一包“离地”状态。
     * <p>缺少移动流水线时只能做到这一步，完整的悬浮由服务端自行判定。
     */
    private void handleFreezeCritical() {
        if (inWallFreeze.getValue() && mc.player.onGround() && (isInWall() || isUnderBlock())) {
            double jumpHeight = customInWallHeightFreeze.getValue() / 4.0;
            mc.player.setPos(mc.player.getX(), mc.player.getY() + jumpHeight * 4.0, mc.player.getZ());
            mc.player.setOnGround(false);
        }
        if (lastOnGroundT || (inAirFreeze.getValue() && shouldApplyCriticalConditionCheck())) {
            NetworkUtils.sendPacketNoEvent(new ServerboundMovePlayerPacket.Rot(
                    mc.player.getYRot(), mc.player.getXRot(), false, mc.player.horizontalCollision));
        }
    }

    /**
     * Grim 地面模拟：伪造一次位移逼服务端拉回，取消本次攻击并在拉回后补刀。
     */
    private void handleGrimGroundSimulation(AttackEntityEvent event, LivingEntity target, double x, double y, double z) {
        if (!mc.player.onGround()) return;
        stopSprintingForCritical();

        if (!shouldApplyGrimGroundSimulationAutoFakeGround()) {
            NetworkUtils.sendPacketNoEvent(new ServerboundMovePlayerPacket.Pos(x, y + MIN_HEIGHT_DELTA, z, true, false));
        }

        switch (setBackType.getValue()) {
            case Simulation -> NetworkUtils.sendPacketNoEvent(new ServerboundMovePlayerPacket.Pos(
                    x, y + 1.0, z, false, false));
            case CrashPackets -> NetworkUtils.sendPacketNoEvent(new ServerboundMovePlayerPacket.Pos(
                    x, Double.POSITIVE_INFINITY, z, false, false));
        }

        cachedTarget = target;
        cachedHandStack = mc.player.getMainHandItem().copy();
        cachedAtTick = mc.player.tickCount;
        pendingReAttack = false;
        fakeMovementThisTick = true;
        event.cancel();
    }

    /** 收到服务端拉回（传送）后标记待补刀；事件在 Netty 线程触发，只写标志位。 */
    @EventHandler
    private void onPacketReceive(PacketEvent.Receive event) {
        if (cachedTarget == null) return;
        if (event.getPacket() instanceof ClientboundPlayerPositionPacket) {
            pendingReAttack = true;
        }
    }

    /**
     * 在客户端线程补刀，避免在 Netty 线程直接操作游戏。
     * <p>使用不可取消的 {@link ClientTickEvent}，避免被其它模块取消 {@link PlayerTickEvent} 时漏掉补刀。
     */
    @EventHandler
    private void onClientTickReAttack(ClientTickEvent.Pre event) {
        if (cachedTarget == null) return;
        if (nullCheck()) {
            clearCache();
            return;
        }
        if (!pendingReAttack && mc.player.tickCount - cachedAtTick <= RE_ATTACK_TIMEOUT_TICKS) return;

        LivingEntity target = cachedTarget;
        ItemStack handStack = cachedHandStack;
        clearCache();
        reAttack(target, handStack);
    }

    private void reAttack(LivingEntity target, ItemStack handStack) {
        if (target == null || !target.isAlive() || nullCheck()) return;

        // 延迟期间被切走的武器切回主手再补刀（对应 SlimefunHelper 的 delay-swap）
        if (delaySwap.getValue()
                && handStack != null
                && !handStack.isEmpty()
                && !ItemStack.isSameItemSameComponents(mc.player.getMainHandItem(), handStack)) {
            FindItemResult result = InvUtils.find(
                    stack -> ItemStack.isSameItemSameComponents(stack, handStack), 0, 8);
            if (result.found()) {
                InvUtils.swap(result.slot(), false);
            }
        }

        reAttacking = true;
        try {
            mc.gameMode.attack(mc.player, target);
            PlayerUtils.swingHand(InteractionHand.MAIN_HAND);
        } finally {
            reAttacking = false;
        }
    }

    private void clearCache() {
        cachedTarget = null;
        cachedHandStack = null;
        cachedAtTick = 0;
        pendingReAttack = false;
    }

    /**
     * 改写即将发出的位置包：
     * <ul>
     *     <li>记录“刚落地”的瞬间，供冻结模式使用；</li>
     *     <li>伪造暴击的当 tick 取消位置包；</li>
     *     <li>冻结模式下强制服务端认为玩家离地。</li>
     * </ul>
     */
    @EventHandler(priority = EventPriority.HIGH)
    private void onSendPosition(SendPositionEvent event) {
        if (nullCheck()) return;

        lastOnGroundT = mc.player.onGround() && !event.isOnGround();

        if (fakeMovementThisTick) {
            fakeMovementThisTick = false;
            event.cancel();
            return;
        }

        if (isFreeze()
                && shouldApplyCriticalConditionCheck()
                && (!groundOnly.getValue() || mc.player.onGround())
                && (inAirFreeze.getValue() || inWallFreeze.getValue())) {
            event.setOnGround(false);
        }
    }

    // ==================== 条件判断 ====================

    private boolean cannotCrit() {
        return mc.player.isInWater() || mc.player.isPassenger() || PlayerUtils.isInWeb();
    }

    /** 无移动输入且附近有目标时才值得伪造暴击。 */
    private boolean shouldApplyCriticalConditionCheck() {
        return hasNoMovement() && hasTargetNear();
    }

    private boolean shouldApplyGrimGroundSimulationAutoFakeGround() {
        return autoFakeGround.getValue() && mc.player.onGround() && shouldApplyCriticalConditionCheck();
    }

    private boolean hasTargetNear() {
        if (!targetOnly.getValue()) return true;
        LivingEntity target = KillAura.INSTANCE.target;
        return target != null && target.isAlive();
    }

    private boolean movementOk() {
        if (isFreeze()) return movementOkFreeze.getValue();
        if (isGrimGroundSimulation()) return movementOkGround.getValue();
        return false;
    }

    private boolean hasNoMovement() {
        boolean jump = mc.options.keyJump.isDown();
        boolean sneak = mc.options.keyShift.isDown();
        if (movementOk()) {
            return !jump && !sneak;
        }
        return !hasMovementInput() && !sneak;
    }

    private boolean hasMovementInput() {
        return mc.options.keyUp.isDown()
                || mc.options.keyDown.isDown()
                || mc.options.keyLeft.isDown()
                || mc.options.keyRight.isDown();
    }

    private boolean isInWall() {
        return PlayerUtils.isInBlock();
    }

    private boolean isUnderBlock() {
        AABB box = mc.player.getBoundingBox();
        BlockPos pos = BlockPos.containing(mc.player.getX(), box.maxY + 0.1, mc.player.getZ());
        return mc.level.getBlockState(pos).isSolidRender();
    }

    // ==================== 发包辅助 ====================

    /** 停疾跑并同步客户端状态；与 SlimefunHelper 一样通过正常连接发送，便于其他模块感知。 */
    private void stopSprintingForCritical() {
        if (!mc.player.isSprinting()) return;
        if (mc.getConnection() != null) {
            mc.getConnection().send(new ServerboundPlayerCommandPacket(
                    mc.player, ServerboundPlayerCommandPacket.Action.STOP_SPRINTING));
        }
        mc.player.setSprinting(false);
    }

    /** 把客户端当前坐标重新同步给服务端。 */
    private void resyncPos() {
        NetworkUtils.sendPacketNoEvent(new ServerboundMovePlayerPacket.Pos(
                mc.player.getX(), mc.player.getY(), mc.player.getZ(),
                mc.player.onGround(), mc.player.horizontalCollision));
    }

}
