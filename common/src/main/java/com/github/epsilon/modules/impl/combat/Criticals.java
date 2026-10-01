package com.github.epsilon.modules.impl.combat;

import com.github.epsilon.events.bus.EventHandler;
import com.github.epsilon.events.bus.EventPriority;
import com.github.epsilon.events.impl.AfterSendPositionEvent;
import com.github.epsilon.events.impl.AttackEntityEvent;
import com.github.epsilon.events.impl.ClientTickEvent;
import com.github.epsilon.events.impl.KeyboardInputEvent;
import com.github.epsilon.events.impl.PacketEvent;
import com.github.epsilon.events.impl.SendPositionEvent;
import com.github.epsilon.managers.rotation.RotationManager;
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
import net.minecraft.network.protocol.game.ServerboundAcceptTeleportationPacket;
import net.minecraft.network.protocol.game.ServerboundMovePlayerPacket;
import net.minecraft.network.protocol.game.ServerboundPlayerCommandPacket;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.PositionMoveRotation;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/**
 * Criticals 模块，移植自 SlimefunHelper 的 {@code Criticals}。
 *
 * <p>服务端判定暴击只认 {@code Player#canCriticalAttack}：{@code fallDistance > 0 && !onGround}。
 * 而 {@code ServerGamePacketListenerImpl#handlePlayerPositionChange} 只在「包内坐标相对服务端当前位置
 * 向下移动且包内 onGround 为 false」时通过 {@code doCheckFallDamage} 累积 fallDistance。
 *
 * <p>可用的刀爆都有一个共同点：**服务端视角里玩家在 onGround 上反复处于「在地上」和「不在地上」两种状态**。
 * 因此 Grim 模式按这个来组织：
 * <ol>
 *     <li>空闲时假地面：每 tick 把客户端自己的 Y 抬到 {@code floor(y*1e4)*1e-4 + 1e-5}（物理会落回地面再被抬起，
 *     所以你能看到 y / y+1e-5 来回切换），并且只让客户端本来就要发出的位置包带上这个坐标
 *     （onGround 保持客户端真实值 true）—— 这就制造了服务端「在地上」的一相；不额外强发包，避免 BadPacketsV。</li>
 *     <li>攻击时触发包：在 tick 末尾用 {@code Pos(假地面坐标 + 1, onGround=false)} 顶替客户端自己的位置包 ——
 *     服务端进入「不在地上」的一相并触发 Grim 的 Simulation 拉回。</li>
 *     <li>收到拉回：在客户端处理拉回包之前（也就是客户端回应第二个 transaction 之前）发出一个精确到拉回坐标、
 *     带微旋转变化、onGround=false 的 PosRot，Grim 会把它当作拉回确认；随后丢掉客户端的 TeleportConfirm 和
 *     本 tick 的位置包，保证我们的移动包之后到 ClientTickEnd 没有别的包（避开 Post / PacketOrderO）。</li>
 *     <li>下一个 tick 开头补刀：排在当 tick 移动包之前，同时服务端的 fallDistance / onGround 不会被自己的 tick 改写
 *     （1.21.2+ 服务端不模拟客户端权威玩家的移动）。</li>
 * </ol>
 *
 * <p>{@link Mode#Freeze} 与上述流程完全隔离：它只改写客户端自己位置包的 onGround，不参与任何缓存 / 延迟 / 补刀逻辑。
 */
public class Criticals extends Module {

    public static final Criticals INSTANCE = new Criticals();

    /** Packet 模式：假地面抬 5e-4，攻击时下落到 y+1e-4，制造 4e-4 的下落位移。 */
    private static final double PACKET_CRIT_FIRST_HEIGHT = 5.0E-4;
    private static final double PACKET_CRIT_SECOND_HEIGHT = 1.0E-4;

    /** 假地面：坐标吸附到 1e-4 网格后再抬高 1e-5（与原版 SlimefunHelper 一致）。 */
    private static final double MIN_HEIGHT_THRESHOLD = 1.0E-4;
    private static final double GRIM_FAKE_GROUND_HEIGHT = 1.0E-5;

    /** 触发拉回后超过该 tick 数仍未收到拉回时直接补刀。 */
    private static final int RE_ATTACK_TIMEOUT_TICKS = 10;

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

    /** 本 tick 要顶替客户端位置包的伪造动作。 */
    private enum PendingAction {
        None,
        PacketCrit,
        WallCrit,
        GrimTrigger,
        GrimSync
    }

    private final EnumSetting<Mode> mode = enumSetting("Mode", Mode.Packet, newMode -> clearCache());

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

    /** 客户端已落地、但即将发出的移动包仍标记为空中的瞬间。 */
    private boolean lastOnGroundT;

    /** 缓存的攻击目标；伪造流程完成后补刀。 */
    private volatile LivingEntity cachedTarget;
    private volatile int cachedAtTick;
    private ItemStack cachedHandStack;

    private PendingAction pendingAction = PendingAction.None;
    /** 触发包已发出，等待 Grim 拉回。 */
    private boolean awaitingSetBack;
    /** 拉回后的同步包已发出，等到指定 tick 补刀。 */
    private int attackAtTick;
    /** 已用自己的包确认拉回，需要丢掉客户端随后的 TeleportConfirm。 */
    private boolean skipNextTeleportConfirm;
    /** Grim 拉回的目标坐标。 */
    private Vec3 setBackTarget;

    private boolean reAttacking;
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
        clearCache();
    }
    // ==================== 原版下落暴击（停疾跑） ====================

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
        // 等待拉回期间压制输入，避免真实移动包与伪造包冲突
        if (cachedTarget != null) {
            if (mc.player.tickCount - cachedAtTick <= 3) {
                event.setSprint(false);
                event.setForward(0.0f);
            }
            if (autoWalk.getValue() && awaitingSetBack && !hasMovementInput()) {
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

        // 只有真正开始伪造时才重置冷却，否则高 CPS 下每次都刷新计时器会导致永远不触发
        if (!cooldownTimer.passedMillise(criticalCooldown.getValue() * 50L)) return;
        cooldownTimer.reset();

        handleCritical(event, target);
    }

    private void handleCritical(AttackEntityEvent event, LivingEntity target) {
        // Freeze 模式完全独立：缓存 / 延迟 / 补刀等流程一律不参与
        if (isFreeze()) {
            handleFreezeCritical();
            return;
        }

        if (inWallPacket.getValue()
                && !mode.is(Mode.Packet)
                && (isInWall() || isUnderBlock())) {
            cacheAttack(target);
            pendingAction = PendingAction.WallCrit;
            event.cancel();
            return;
        }

        switch (mode.getValue()) {
            case Packet -> {
                if (!mc.player.onGround()) return;
                cacheAttack(target);
                pendingAction = PendingAction.PacketCrit;
                event.cancel();
            }
            case GrimGroundSimulation -> {
                if (!mc.player.onGround()) return;
                cacheAttack(target);
                pendingAction = PendingAction.GrimTrigger;
                event.cancel();
            }
            case GrimWall -> {
                if (mc.player.onGround() && (isInWall() || isUnderBlock())) {
                    cacheAttack(target);
                    pendingAction = PendingAction.WallCrit;
                    event.cancel();
                }
            }
            default -> {
            }
        }
    }

    /**
     * 每个客户端 tick 开头：
     * <ol>
     *     <li>到点补刀（排在当 tick 移动包之前，避开 Post / PacketOrderO）；</li>
     *     <li>等待拉回超时则直接补刀；</li>
     *     <li>空闲时维持假地面：只改客户端自己的坐标，包交给客户端自然的发包时机带出去。</li>
     * </ol>
     */
    @EventHandler(priority = EventPriority.HIGH)
    private void onClientTickPending(ClientTickEvent.Pre event) {
        if (nullCheck()) {
            clearCache();
            return;
        }

        if (attackAtTick > 0 && mc.player.tickCount >= attackAtTick) {
            attackAtTick = 0;
            stopSprintingForCritical();
            reAttackAndClear();
            return;
        }

        if (awaitingSetBack && mc.player.tickCount - cachedAtTick > RE_ATTACK_TIMEOUT_TICKS) {
            awaitingSetBack = false;
            stopSprintingForCritical();
            reAttackAndClear();
            return;
        }

        if (cachedTarget == null
                && isGrimGroundSimulation()
                && autoFakeGround.getValue()
                && !awaitingSetBack
                && attackAtTick == 0
                && mc.player.onGround()
                && hasNoMovement()) {
            // 假地面：把客户端自己的 Y 抬高一点（物理每 tick 会落回地面，形成 y / y+1e-5 的来回切换）
            mc.player.setPos(mc.player.getX(), fakeGroundY(mc.player.getY()), mc.player.getZ());
        }
    }

    /**
     * 由 {@code MixinClientPacketListener} 在客户端处理拉回包之前调用。
     * <p>必须在客户端回应第二个 transaction 之前发出带位移 + 旋转变化的 PosRot 抢占拉回确认，
     * 并丢掉客户端的 TeleportConfirm 与本 tick 的位置包。
     */
    public void onSetBackPacket(ClientboundPlayerPositionPacket packet) {
        if (!awaitingSetBack || cachedTarget == null || nullCheck()) return;
        awaitingSetBack = false;

        setBackTarget = PositionMoveRotation.calculateAbsolute(
                PositionMoveRotation.of(mc.player), packet.change(), packet.relatives()).position();
        stopSprintingForCritical();
        sendFakeSyncPacket();
        skipNextTeleportConfirm = true;
        pendingAction = PendingAction.GrimSync;
        attackAtTick = mc.player.tickCount + 1;
    }
    /**
     * 改写即将发出的位置包。
     * <ul>
     *     <li>{@link Mode#Freeze} 完全独立：只改 onGround，绝不取消客户端的包；</li>
     *     <li>伪造动作所在 tick 取消客户端自己的包，改由 {@code onAfterSendPosition} 发我们的包；</li>
     *     <li>Grim 假地面：把包内 Y 换成假地面坐标（onGround 保持真实值），制造服务端「在地上」的一相。</li>
     * </ul>
     */
    @EventHandler(priority = EventPriority.HIGH)
    private void onSendPosition(SendPositionEvent event) {
        if (nullCheck()) return;

        lastOnGroundT = mc.player.onGround() && !event.isOnGround();

        if (isFreeze()) {
            if (shouldApplyCriticalConditionCheck()
                    && (!groundOnly.getValue() || mc.player.onGround())
                    && (inAirFreeze.getValue() || inWallFreeze.getValue())) {
                event.setOnGround(false);
            }
            return;
        }

        if (pendingAction != PendingAction.None) {
            event.cancel();
            return;
        }

        if (shouldApplyFakeGround()) {
            event.setY(currentHoverY());
            // 与 Freeze 一致：站立时让服务端处于「不在地上」的一相，移动时客户端自己的包仍是「在地上」，
            // 这样服务端视角就会在 onGround 上反复切换
            event.setOnGround(false);
        }
    }

    /** 位置包已经发出（或被取消），在这里发出真正想要的伪造包。 */
    @EventHandler
    private void onAfterSendPosition(AfterSendPositionEvent event) {
        if (pendingAction == PendingAction.None || nullCheck()) return;

        PendingAction action = pendingAction;
        pendingAction = PendingAction.None;

        switch (action) {
            case GrimTrigger -> {
                stopSprintingForCritical();
                if (setBackType.getValue() == SetBackTrigger.CrashPackets) {
                    sendFakePos(Double.POSITIVE_INFINITY);
                } else {
                    sendFakePos(fakeGroundY(mc.player.getY()) + 1.0);
                }
                awaitingSetBack = true;
                cachedAtTick = mc.player.tickCount;
            }
            case PacketCrit -> {
                stopSprintingForCritical();
                sendFakePos(mc.player.getY() + PACKET_CRIT_SECOND_HEIGHT);
                attackAtTick = mc.player.tickCount + 1;
            }
            case WallCrit -> {
                stopSprintingForCritical();
                sendFakePos(mc.player.getY() + customInWallHeightPacket.getValue() * 0.75);
                attackAtTick = mc.player.tickCount + 1;
            }
            default -> {
            }
        }
    }

    /** 自己的 PosRot 已经确认拉回，丢掉客户端随后的 TeleportConfirm，避免 Post / PacketOrderO。 */
    @EventHandler
    private void onPacketSend(PacketEvent.Send event) {
        if (!skipNextTeleportConfirm) return;
        if (event.getPacket() instanceof ServerboundAcceptTeleportationPacket) {
            skipNextTeleportConfirm = false;
            event.cancel();
        }
    }

    // ==================== 发包辅助 ====================

    /** 与 SlimefunHelper 一致的假地面坐标：吸附到 1e-4 网格后再抬高 1e-5。 */
    private double fakeGroundY(double y) {
        return Math.floor(y / MIN_HEIGHT_THRESHOLD) * MIN_HEIGHT_THRESHOLD + GRIM_FAKE_GROUND_HEIGHT;
    }

    /** 纯位移包：不带旋转，避免重复旋转被 AimDuplicateLook 记录。 */
    private void sendFakePos(double y) {
        NetworkUtils.sendPacketNoEvent(new ServerboundMovePlayerPacket.Pos(
                mc.player.getX(), y, mc.player.getZ(), false, mc.player.horizontalCollision));
    }

    /**
     * 拉回后的同步包：坐标必须和 Grim 发送的拉回坐标完全一致（绝对传送 1e-7 误差），
     * 同时带一点旋转变化（兼容需要「位移 + 旋转变化」才算拉回确认的版本，也避开 AimDuplicateLook）。
     */
    private void sendFakeSyncPacket() {
        Vec3 target = setBackTarget != null ? setBackTarget : mc.player.position();

        float yaw = mc.player.getYRot();
        float pitch = mc.player.getXRot();
        RotationManager manager = RotationManager.INSTANCE;
        if (manager != null && manager.isActive()) {
            yaw = manager.getRotation().getYaw();
            pitch = manager.getRotation().getPitch();
        }

        NetworkUtils.sendPacketNoEvent(new ServerboundMovePlayerPacket.PosRot(
                target.x, target.y, target.z, yaw + 1.0E-3f, pitch, false, mc.player.horizontalCollision));
    }

    /** 停疾跑并同步客户端状态；与 SlimefunHelper 一样通过正常连接发送，便于其他模块感知。 */
    private void stopSprintingForCritical() {
        if (!mc.player.isSprinting()) return;
        if (mc.getConnection() != null) {
            mc.getConnection().send(new ServerboundPlayerCommandPacket(
                    mc.player, ServerboundPlayerCommandPacket.Action.STOP_SPRINTING));
        }
        mc.player.setSprinting(false);
    }

    private void reAttackAndClear() {
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

    private void cacheAttack(LivingEntity target) {
        cachedTarget = target;
        cachedHandStack = mc.player.getMainHandItem().copy();
        cachedAtTick = mc.player.tickCount;
        awaitingSetBack = false;
        attackAtTick = 0;
        setBackTarget = null;
    }

    private void clearCache() {
        cachedTarget = null;
        cachedHandStack = null;
        cachedAtTick = 0;
        awaitingSetBack = false;
        attackAtTick = 0;
        skipNextTeleportConfirm = false;
        setBackTarget = null;
        pendingAction = PendingAction.None;
    }

    /**
     * 冻结模式：贴墙时先在客户端抬高一格边缘，再额外发送一包「离地」状态。
     * <p>与其他模式完全隔离，只让服务端在 onGround 上反复处于两种状态。
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

    // ==================== 假地面与条件 ====================

    /** 是否需要维持假地面（只影响客户端自己发出的包，不额外发包）。 */
    private boolean shouldApplyFakeGround() {
        if (cachedTarget != null || !mc.player.onGround()) return false;
        return switch (mode.getValue()) {
            case Packet -> true;
            case GrimWall -> isInWall() || isUnderBlock();
            case GrimGroundSimulation -> autoFakeGround.getValue() && hasNoMovement();
            default -> false;
        };
    }

    /** 假地面坐标：比真实脚底略高一点，服务端据此停留在「在地上」的一相。 */
    private double currentHoverY() {
        double y = mc.player.getY();
        return switch (mode.getValue()) {
            case GrimGroundSimulation -> fakeGroundY(y);
            case GrimWall -> y + customInWallHeightPacket.getValue();
            default -> y + PACKET_CRIT_FIRST_HEIGHT;
        };
    }

    private boolean cannotCrit() {
        return mc.player.isInWater() || mc.player.isPassenger() || PlayerUtils.isInWeb();
    }

    /** 无移动输入且附近有目标时才值得伪造暴击。 */
    private boolean shouldApplyCriticalConditionCheck() {
        return hasNoMovement() && hasTargetNear();
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

}
