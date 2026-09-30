package com.github.epsilon.modules.impl.combat;

import com.github.epsilon.events.bus.EventHandler;
import com.github.epsilon.events.bus.EventPriority;
import com.github.epsilon.events.impl.ClientTickEvent;
import com.github.epsilon.events.impl.KeyboardInputEvent;
import com.github.epsilon.events.impl.PacketEvent;
import com.github.epsilon.events.impl.PlayerTickEvent;
import com.github.epsilon.interfaces.ClientboundEntityEventPacketAccessor;
import com.github.epsilon.managers.rotation.RotationManager;
import com.github.epsilon.managers.target.TargetManager;
import com.github.epsilon.managers.target.TargetRequest;
import com.github.epsilon.modules.Category;
import com.github.epsilon.modules.Module;
import com.github.epsilon.modules.impl.ClientSetting;
import com.github.epsilon.settings.impl.BoolSetting;
import com.github.epsilon.settings.impl.DoubleSetting;
import com.github.epsilon.settings.impl.EnumSetting;
import com.github.epsilon.settings.impl.IntSetting;
import com.github.epsilon.utils.player.FindItemResult;
import com.github.epsilon.utils.player.InvUtils;
import com.github.epsilon.utils.player.PlayerUtils;
import com.github.epsilon.utils.rotation.Priority;
import com.github.epsilon.utils.rotation.RaytraceUtils;
import com.github.epsilon.utils.rotation.Rot2f;
import com.github.epsilon.utils.rotation.RotationUtils;
import net.minecraft.network.protocol.game.ClientboundEntityEventPacket;
import net.minecraft.tags.ItemTags;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityEvent;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.Items;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

import java.util.List;

/**
 * 长矛光环：自动蓄力长矛，静默瞄准目标并让移动按瞄准方向结算；只有与目标的相对速度达到长矛 kinetic 伤害
 * 门限（4.6 m/s）时才转头和移动，kinetic 命中后延迟 1 tick 补一记重锤，然后暂停瞄准/移动等待目标无敌帧结束。
 */
public class SpearAura extends Module {

    public static final SpearAura INSTANCE = new SpearAura();

    /** 与目标小于 3 格时不再转头/移动，避免贴脸转头。 */
    private static final double SPEAR_MIN_TRACK_DISTANCE = 3.0;

    /** 重锤补刀只在摔落高度大于 3 格时触发。 */
    private static final double MACE_MIN_FALL_DISTANCE = 3.0;

    /** 命中后停止瞄准/移动的 tick 数，覆盖目标无敌帧。 */
    private static final int HIT_PAUSE_TICKS = 10;

    /**
     * 长矛 kinetic 伤害要求的最小相对速度，单位米/秒。
     * <p>
     * 原版全部长矛的 {@code damage_conditions.min_relative_speed} 都是 4.6（见 {@code Item.Properties#spear}
     * 的最后一个参数），低于它时 {@code KineticWeapon#damageEntities} 不会结算伤害，也就不会触发 kinetic 命中。
     */
    private static final double SPEAR_MIN_RELATIVE_SPEED = 4.6;

    private static final int MAX_TARGETS = 64;

    private SpearAura() {
        super("Spear Aura", Category.COMBAT);
    }

    private enum MaceSwapMode {
        Normal,
        Silent,
        InvSwitch
    }

    private final DoubleSetting range = doubleSetting("Range", 4.0, 0.0, 6.0, 0.1);
    private final IntSetting fov = intSetting("FOV", 360, 10, 360, 1);
    private final BoolSetting autoCharge = boolSetting("Auto Charge", true);
    private final BoolSetting move = boolSetting("Move", true);
    private final IntSetting rotationSpeed = intSetting("Rotation Speed", 180, 10, 180, 10);
    private final EnumSetting<Priority> rotationPriority = enumSetting("Rotation Priority", Priority.High);
    /** 模块级转头方式；仅在 ClientSetting 的 Rotation Scope 为 Custom 时生效。 */
    private final EnumSetting<RotationManager.RotationOption> rotationType = enumSetting("Rotation Type", RotationManager.RotationOption.Silent, ClientSetting.INSTANCE::isCustomRotationScope);

    private final BoolSetting players = boolSetting("Players", true);
    private final BoolSetting mobs = boolSetting("Mobs", true);
    private final BoolSetting animals = boolSetting("Animals", true);
    private final BoolSetting villagers = boolSetting("Villagers", false);
    private final BoolSetting invisible = boolSetting("Invisible", true);

    private final BoolSetting mace = boolSetting("Mace", true);
    private final EnumSetting<MaceSwapMode> maceSwapMode = enumSetting("Mace Swap Mode", MaceSwapMode.Silent, mace::getValue);

    public LivingEntity target;
    private List<LivingEntity> targets;

    /** 本 tick 生效的静默瞄准角度；非 null 表示正在瞄准，移动按它结算。 */
    private Rot2f aimRotations;
    /** 本 tick 是否允许在没有输入时模拟按 W 沿瞄准方向前进。 */
    private boolean forceForward;
    /** 模块当前是否在用长矛瞄准；瞄准且开启 Move 时接管移动输入。 */
    private boolean aimActive;

    /** 长矛 kinetic 命中包来自网络线程，先转成客户端 tick 消费的状态。 */
    private volatile boolean spearHitPending;
    private volatile int localPlayerId = -1;
    /** 长矛命中后延迟 1 tick 再补重锤，避免和 kinetic 命中同一 tick 处理。 */
    private int spearMaceDelay;
    /** 命中后暂停瞄准/移动，等待目标无敌帧结束。 */
    private int hitPauseTicks;

    /** 本 tick 是否需要保持长矛蓄力，由 {@link #onClientTick} 的状态机决定。 */
    private boolean autoChargeWanted;
    /** 本模块是否替玩家按住了右键；只有本字段为 true 时才允许松开按键。 */
    private boolean autoChargeKeyHeld;

    @Override
    public String getInfo() {
        return target == null ? null : target.getName().getString();
    }

    @Override
    protected void onEnable() {
        resetState();
    }

    @Override
    protected void onDisable() {
        resetState();
    }

    @EventHandler
    private void onClientTick(ClientTickEvent.Pre event) {
        if (nullCheck()) return;

        localPlayerId = mc.player.getId();
        forceForward = false;
        autoChargeWanted = false;

        targets = TargetManager.INSTANCE.acquireTargets(TargetRequest.of(
                range.getValue(),
                fov.getValue().floatValue(),
                players.getValue(),
                mobs.getValue(),
                animals.getValue(),
                villagers.getValue(),
                false,
                false,
                false,
                invisible.getValue(),
                MAX_TARGETS
        ));

        if (targets.isEmpty()) {
            target = null;
            aimRotations = null;
            aimActive = false;
            return;
        }

        target = targets.getFirst();
        aimActive = isHoldingSpear() || isUsingSpear();
        if (!aimActive) {
            // 没拿长矛时不请求静默旋转，也不接管移动，避免干扰玩家正常移动。
            aimRotations = null;
            return;
        }

        // 命中后的无敌帧窗口内只暂停，不再瞄准/移动/蓄力；重锤补刀窗口例外。
        if (hitPauseTicks > 0 && spearMaceDelay <= 0) {
            hitPauseTicks--;
            aimRotations = null;
            return;
        }

        // 目标贴脸时只自动蓄力，不转头、不移动。
        if (RotationUtils.getEyeDistanceToEntity(target) <= SPEAR_MIN_TRACK_DISTANCE) {
            aimRotations = null;
            autoChargeWanted = true;
            return;
        }

        // 相对速度不够时 kinetic 命中不结算伤害：保持蓄力，但不转头也不接管移动。
        if (getRelativeSpeedTo(target) <= SPEAR_MIN_RELATIVE_SPEED) {
            aimRotations = null;
            autoChargeWanted = true;
            return;
        }

        // 已经确认在瞄准，Move 打开就允许本 tick 在没有输入时自动沿瞄准方向前进。
        forceForward = move.getValue();
        // 已锁定目标且不在暂停/补刀窗口内就保持蓄力；视线被挡只影响转头，不打断蓄力。
        // 按键由 priority 更低的 onClientTickAutoCharge 同步，实际起手发生在同一 tick 稍后的
        // Minecraft#handleKeybinds，那时下面的静默旋转已经提交，UseItem 包会带上瞄准朝向。
        autoChargeWanted = true;

        // 静默瞄准；UseItem 包会由 SilentRotationManager 改写成这个朝向。
        aimRotations = RotationUtils.calculate(target, true, range.getValue());
        if (RaytraceUtils.raytrace(aimRotations, range.getValue()).getType() == HitResult.Type.BLOCK) {
            aimRotations = null;
            return;
        }
        RotationManager.request(
                rotationType.getValue(),
                aimRotations,
                rotationSpeed.getValue(),
                rotation -> RaytraceUtils.raytrace(rotation, range.getValue()) instanceof EntityHitResult hitResult && hitResult.getEntity() == target,
                rotationPriority.getValue()
        );
    }

    /**
     * 接管移动输入，让玩家沿瞄准方向前进。
     * <p>
     * 静默瞄准生效时 {@code Entity#moveRelative} 已经用托管旋转（即瞄准角度）结算方向：
     * {@code SilentRotationManager.onStrafe} 会把 {@code StrafeEvent} 的 yaw 换成托管旋转。因此这里只要把
     * 玩家原始 WASD 原样写回，就能覆盖 {@code MovementFix} 的镜头系重映射，等价于“按瞄准方向移动”。
     * 若再按 aimRotations 旋转一次输入，实际方向会变成 2 * 瞄准角度 - 镜头角度而偏出去。
     */
    @EventHandler(priority = EventPriority.LOWEST)
    private void onKeyboardInput(KeyboardInputEvent event) {
        // 未在瞄准（贴脸/暂停/遮挡）或未开启 Move 时不碰输入，保留 MovementFix 的镜头系移动。
        if (!aimActive || !move.getValue() || aimRotations == null) return;

        // 读取玩家原始 WASD，自己处理，避免 MovementFix 的反向重映射。
        float forward = (mc.options.keyUp.isDown() ? 1.0f : 0.0f) - (mc.options.keyDown.isDown() ? 1.0f : 0.0f);
        float strafe = (mc.options.keyLeft.isDown() ? 1.0f : 0.0f) - (mc.options.keyRight.isDown() ? 1.0f : 0.0f);

        // 玩家没有输入时，如果允许才模拟正常按 W 前进；重锤补刀 tick 由 forceForward 抑制。
        if (forward == 0.0f && strafe == 0.0f) {
            if (!forceForward) return;
            forward = 1.0f;
        }

        event.setForward(forward);
        event.setStrafe(strafe);
        event.setSprint(true);
    }

    @EventHandler
    private void onPacketReceive(PacketEvent.Receive event) {
        // 长矛 kinetic 命中的实体事件包在 netty 线程触发，只记录状态，主线程再补重锤。
        if (!isEnabled() || !(event.getPacket() instanceof ClientboundEntityEventPacket packet)) {
            return;
        }
        if (packet.getEventId() != EntityEvent.KINETIC_HIT) return;
        if (packet instanceof ClientboundEntityEventPacketAccessor accessor
                && accessor.epsilon$getEntityId() == localPlayerId) {
            spearHitPending = true;
        }
    }

    @EventHandler
    private void onPlayerTick(PlayerTickEvent.Pre event) {
        if (spearHitPending) {
            spearHitPending = false;
            // 先结束长矛蓄力，并延迟 1 tick 再补重锤，避免和 kinetic 命中同一 tick 处理。
            if (isUsingSpear()) {
                mc.gameMode.releaseUsingItem(mc.player);
            }
            spearMaceDelay = 1;
            forceForward = false;
            // 命中包可能在 ClientTickEvent.Pre 之后才到达，这里再松一次按键，防止下一 tick 原版重新起手。
            stopAutoCharge();
            return;
        }

        if (spearMaceDelay > 0) {
            if (--spearMaceDelay == 0) {
                // 攻击前再确认一次已松开长矛，避免按住右键重新蓄力时打断补刀。
                if (isUsingSpear()) {
                    mc.gameMode.releaseUsingItem(mc.player);
                }
                // 命中后先补重锤，然后暂停瞄准/移动等待无敌帧。
                if (target != null && target.isAlive()) {
                    attackWithMace(target);
                }
                hitPauseTicks = HIT_PAUSE_TICKS;
                forceForward = false;
                stopAutoCharge();
            }
        }
    }

    /**
     * 自动蓄力：代替玩家按住右键。
     * <p>
     * 原版 {@code Minecraft#handleKeybinds} 每 tick 都用 {@code keyUse.isDown()} 判断还要不要继续使用物品，
     * 键不是按下状态就立刻 {@code releaseUsingItem}；而客户端 {@code isUsingItem()} 读的是实体标记，
     * 只有服务端 {@code startUsingItem} 才会设置并同步回来。因此直接调用 {@code gameMode.useItem} 起手，
     * 等服务端把标记同步回来之后就会被原版当场松开，蓄力永远保持不住。这里改为替玩家按住右键，让原版
     * 自己完成起手与保持；不需要蓄力时再松开按键，由原版释放。
     * <p>
     * 必须晚于 {@link #onClientTick} 执行（priority 更低），否则读到的是上一 tick 的瞄准状态。
     */
    @EventHandler(priority = EventPriority.LOWEST)
    private void onClientTickAutoCharge(ClientTickEvent.Pre event) {
        if (nullCheck() || !autoCharge.getValue() || !autoChargeWanted
                || spearHitPending || spearMaceDelay > 0 || !isHoldingSpear()) {
            stopAutoCharge();
            return;
        }

        // 玩家自己按着右键时不接管，也不需要记录归属，松开时自然不碰玩家按住的键。
        if (!mc.options.keyUse.isDown()) {
            mc.options.keyUse.setDown(true);
            autoChargeKeyHeld = true;
        }
    }

    /**
     * 松开自动蓄力按住的右键；玩家自己按住的键不处理。
     */
    private void stopAutoCharge() {
        if (!autoChargeKeyHeld) return;
        autoChargeKeyHeld = false;
        // 配置恢复可能早于 Minecraft 构造完成，此时还没有 Options。
        if (mc.options != null) {
            mc.options.keyUse.setDown(false);
        }
    }

    /**
     * 执行一次主手攻击；重锤补刀复用该逻辑，保证挥手行为一致。
     */
    private void attackEntity(Entity entity) {
        mc.gameMode.attack(mc.player, entity);
        PlayerUtils.swingHand(InteractionHand.MAIN_HAND);
    }

    /**
     * 长矛 kinetic 命中后立刻切换重锤再补一次攻击。
     * <p>
     * Normal 保留切换结果，Silent 只静默切换快捷栏，InvSwitch 通过容器交换从背包取物。
     */
    private void attackWithMace(Entity entity) {
        if (!mace.getValue() || mc.player.fallDistance <= MACE_MIN_FALL_DISTANCE || entity == null || !entity.isAlive()) return;

        FindItemResult maceResult = findMace();
        if (!maceResult.found()) return;

        int selectedSlot = mc.player.getInventory().getSelectedSlot();
        if (maceResult.slot() == selectedSlot) {
            attackEntity(entity);
            return;
        }

        switch (maceSwapMode.getValue()) {
            case Normal, Silent -> mc.player.getInventory().setSelectedSlot(maceResult.slot());
            case InvSwitch -> InvUtils.invSwap(maceResult.slot());
        }

        attackEntity(entity);

        switch (maceSwapMode.getValue()) {
            case Normal -> {
                // Normal 保留重锤切换结果，不进行回切。
            }
            case Silent -> mc.player.getInventory().setSelectedSlot(selectedSlot);
            case InvSwitch -> InvUtils.invSwapBack();
        }
    }

    private FindItemResult findMace() {
        // 攻击只结算主手，因此排除副手槽位 40；Silent 只能操作快捷栏，InvSwitch 才搜索整个主背包。
        if (maceSwapMode.is(MaceSwapMode.InvSwitch)) {
            return InvUtils.find(stack -> stack.is(Items.MACE), 0, 35);
        }
        return InvUtils.find(stack -> stack.is(Items.MACE), 0, 8);
    }

    /**
     * 玩家是否手持长矛。
     */
    private boolean isHoldingSpear() {
        return mc.player != null && mc.player.getMainHandItem().is(ItemTags.SPEARS);
    }

    /**
     * 玩家是否正在长按蓄力长矛。
     */
    private boolean isUsingSpear() {
        return mc.player != null && mc.player.isUsingItem() && mc.player.getUseItem().is(ItemTags.SPEARS);
    }

    /**
     * 攻击者与目标沿“冲向目标”方向的相对速度，单位米/秒。
     * <p>
     * 口径与原版 {@code KineticWeapon#damageEntities} 一致：{@code max(0, 朝向 · (本方速度 - 目标速度))}，
     * 速度取 {@code Entity#getKnownSpeed()}（本 tick 实际位移）再乘 20 换算成 m/s。原版结算用的是服务端朝向，
     * 这里固定用“眼睛指向目标”的方向：还没转头时若用当前朝向，目标在身后会让点积为负，门限永远打不开。
     */
    private double getRelativeSpeedTo(Entity entity) {
        Rot2f look = RotationUtils.getRotationsToEntity(entity);
        Vec3 direction = Vec3.directionFromRotation(look.getPitch(), look.getYaw());
        Vec3 relativeMotion = mc.player.getKnownSpeed().subtract(entity.getKnownSpeed()).scale(20.0);
        return Math.max(0.0, direction.dot(relativeMotion));
    }

    private void resetState() {
        target = null;
        targets = null;
        aimRotations = null;
        forceForward = false;
        aimActive = false;
        spearHitPending = false;
        spearMaceDelay = 0;
        hitPauseTicks = 0;
        autoChargeWanted = false;
        // 模块关闭时必须把替玩家按住的右键松开，否则会一直保持蓄力。
        stopAutoCharge();
        localPlayerId = -1;
    }
}
