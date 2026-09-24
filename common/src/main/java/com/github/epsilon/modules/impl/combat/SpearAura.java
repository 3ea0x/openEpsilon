package com.github.epsilon.modules.impl.combat;

import com.github.epsilon.events.bus.EventHandler;
import com.github.epsilon.events.impl.ClientTickEvent;
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
import net.minecraft.network.protocol.game.ServerboundMovePlayerPacket;
import net.minecraft.tags.ItemTags;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityEvent;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.Items;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.HitResult;

import java.util.List;

/**
 * 长矛光环：手持长矛蓄力时静默瞄准目标，并在 kinetic 命中后延迟 1 tick 补一记重锤。
 */
public class SpearAura extends Module {

    public static final SpearAura INSTANCE = new SpearAura();

    /** 目标 3 格以内不做静默追踪，避免贴脸转头。 */
    private static final double SPEAR_MIN_TRACK_DISTANCE = 3.0;

    /** 重锤补刀只在摔落高度大于 3 格时触发。 */
    private static final double MACE_MIN_FALL_DISTANCE = 3.0;

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

    /** 长矛 kinetic 命中包来自网络线程，先转成客户端 tick 消费的状态。 */
    private volatile boolean spearHitPending;
    private volatile int localPlayerId = -1;
    /** 长矛命中后延迟 1 tick 再补重锤，避免和 kinetic 命中同一 tick 处理。 */
    private int spearMaceDelay;

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
            return;
        }

        target = targets.getFirst();

        // 不在蓄力且不在重锤补刀窗口时不转头；目标贴脸时也不追踪。
        if (spearMaceDelay <= 0
                && (!isUsingSpear() || RotationUtils.getEyeDistanceToEntity(target) <= SPEAR_MIN_TRACK_DISTANCE)) {
            return;
        }

        Rot2f calculate = RotationUtils.calculate(target, true, range.getValue());
        if (RaytraceUtils.raytrace(calculate, range.getValue()).getType() == HitResult.Type.BLOCK) return;
        RotationManager.request(
                rotationType.getValue(),
                calculate,
                rotationSpeed.getValue(),
                rotation -> RaytraceUtils.raytrace(rotation, range.getValue()) instanceof EntityHitResult hitResult && hitResult.getEntity() == target,
                rotationPriority.getValue()
        );
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
        } else if (spearMaceDelay > 0 && --spearMaceDelay == 0) {
            // 攻击前再确认一次已松开长矛，避免按住右键重新蓄力时打断补刀。
            if (isUsingSpear()) {
                mc.gameMode.releaseUsingItem(mc.player);
            }
            if (target != null && target.isAlive()) {
                // 补刀前先把朝向对准目标，否则服务端会因朝向不对拒绝这次攻击。
                forceAimAtTarget(target);
                attackWithMace(target);
            }
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
     * 静默对准目标：只发服务端旋转包并让托管旋转/头部跟随，不移动客户端视角。
     */
    private void forceAimAtTarget(Entity aimTarget) {
        if (aimTarget == null) return;

        Rot2f rotations = RotationUtils.calculate(aimTarget, true, range.getValue());

        RotationManager manager = RotationManager.INSTANCE;
        if (manager != null && RotationManager.isRotationManaged(rotationType.getValue())) {
            manager.rotations = rotations;
            manager.setActive(true);
            manager.setSmoothed(true);
        }

        mc.getConnection().send(new ServerboundMovePlayerPacket.Rot(
                rotations.getYaw(),
                rotations.getPitch(),
                mc.player.onGround(),
                mc.player.horizontalCollision
        ));
        mc.player.setYHeadRot(rotations.getYaw());
    }

    /**
     * 玩家是否正在长按蓄力长矛。
     */
    private boolean isUsingSpear() {
        return mc.player != null && mc.player.isUsingItem() && mc.player.getUseItem().is(ItemTags.SPEARS);
    }

    private void resetState() {
        target = null;
        targets = null;
        spearHitPending = false;
        spearMaceDelay = 0;
        localPlayerId = -1;
    }
}
