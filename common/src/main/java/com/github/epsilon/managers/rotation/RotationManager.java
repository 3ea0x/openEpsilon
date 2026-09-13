package com.github.epsilon.managers.rotation;

import com.github.epsilon.events.bus.EventBus;
import com.github.epsilon.events.bus.EventHandler;
import com.github.epsilon.events.bus.EventPriority;
import com.github.epsilon.events.impl.*;
import com.github.epsilon.modules.impl.ClientSetting;
import com.github.epsilon.utils.rotation.Priority;
import com.github.epsilon.utils.rotation.Rot2f;
import com.github.epsilon.utils.rotation.RotationUtils;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.HitResult;

import java.util.EnumMap;
import java.util.Map;
import java.util.function.Function;

import static com.github.epsilon.Constants.mc;

public abstract class RotationManager {

    public static RotationManager INSTANCE;

    public enum RotationMode {
        SILENT,
        SNAP
    }

    /**
     * 模块级转头方式。
     * <p>
     * {@code Silent} 与 {@code Snap} 分别对应 {@link SilentRotationManager} 与 {@link SnapRotationManager}；
     * {@code None} 表示该模块不请求托管旋转，直接使用玩家真实视角。仅在
     * {@link ClientSetting.RotationScope#Custom} 下才会使用模块自身的取值，全局范围由
     * {@code ClientSetting.rotationMode} 统一决定。
     */
    public enum RotationOption {
        Silent,
        Snap,
        None;

        /**
         * 该方式对应的托管旋转模式；{@link #None} 不参与托管旋转，返回 {@code null}。
         */
        public RotationMode toMode() {
            return switch (this) {
                case Silent -> RotationMode.SILENT;
                case Snap -> RotationMode.SNAP;
                case None -> null;
            };
        }
    }

    /**
     * 按模式缓存的托管旋转实例。
     * <p>
     * 模块级转头方式会让同一 tick 内在两种模式之间来回切换，而 {@link EventBus} 的 listener 缓存以实例
     * 身份为 key 且不会回收，因此切换必须复用实例，不能每次新建。
     */
    private static final Map<RotationMode, RotationManager> CACHED_MANAGERS = new EnumMap<>(RotationMode.class);

    /** 本实例承载的模式，实例创建后不再改变。 */
    private final RotationMode mode;

    private final Rot2f offset = new Rot2f(0, 0);
    public Rot2f rotations = new Rot2f(0, 0);
    public Rot2f lastRotations = new Rot2f(0, 0);
    public Rot2f targetRotations;
    public Rot2f animationRotation = null;
    public Rot2f lastAnimationRotation = null;

    protected boolean active;
    protected boolean smoothed;
    protected double rotationSpeed;
    protected Function<Rot2f, Boolean> raytrace;
    private float randomAngle;
    private HitResult rotationHitResult;
    private boolean calculatingHitResult;

    protected int priority;

    protected RotationManager(RotationMode mode) {
        this.mode = mode;
    }

    /**
     * 按模块级转头方式提交一次托管旋转请求。
     * <p>
     * 全局范围下忽略 {@code option}，统一使用 {@code ClientSetting.rotationMode}；自定义范围下
     * {@link RotationOption#None} 表示该模块完全不参与托管旋转，此时不切换模式也不激活旋转。
     *
     * @param option         模块自身的转头方式
     * @param rotations      目标旋转
     * @param rotationSpeed  旋转速度
     */
    public static void request(RotationOption option, Rot2f rotations, double rotationSpeed) {
        request(option, rotations, rotationSpeed, null, Priority.Medium);
    }

    /**
     * 按模块级转头方式提交一次托管旋转请求。
     *
     * @param option        模块自身的转头方式
     * @param rotations     目标旋转
     * @param rotationSpeed 旋转速度
     * @param priority      旋转请求优先级
     */
    public static void request(RotationOption option, Rot2f rotations, double rotationSpeed, Priority priority) {
        request(option, rotations, rotationSpeed, null, priority);
    }

    /**
     * 按模块级转头方式提交一次托管旋转请求。
     *
     * @param option        模块自身的转头方式
     * @param rotations     目标旋转
     * @param rotationSpeed 旋转速度
     * @param raytrace      平滑随机偏移校验回调，必须无副作用
     * @param priority      旋转请求优先级
     */
    public static void request(RotationOption option, Rot2f rotations, double rotationSpeed,
                               Function<Rot2f, Boolean> raytrace, Priority priority) {
        if (rotations == null || option == null) return;

        RotationMode resolved = ClientSetting.INSTANCE.resolveRotationMode(option);
        if (resolved == null) return;

        RotationManager current = INSTANCE;
        if (current == null) {
            switchRotationManager(resolved);
        } else {
            // 会被优先级拒绝的请求不触发模式切换，否则两个模式不同的模块会在同一 tick 内反复切换
            if (current.rejectsPriority(priority)) return;
            if (current.mode != resolved) switchRotationManager(resolved);
        }

        INSTANCE.setRotations(rotations, rotationSpeed, raytrace, priority);
    }

    /**
     * 给定方式在当前设置下是否会参与托管旋转。
     * <p>
     * 全局范围下模块自身取值被忽略，只要全局模式有效就返回 {@code true}；自定义范围下
     * {@link RotationOption#None} 返回 {@code false}。
     *
     * @param option 模块自身的转头方式
     * @return 该模块是否会请求托管旋转
     */
    public static boolean isRotationManaged(RotationOption option) {
        return option != null && ClientSetting.INSTANCE.resolveRotationMode(option) != null;
    }

    public void setRotations(Rot2f rotations, double rotationSpeed) {
        setRotations(rotations, rotationSpeed, null, Priority.Medium);
    }

    public void setRotations(Rot2f rotations, double rotationSpeed, Priority priority) {
        setRotations(rotations, rotationSpeed, null, priority);
    }

    public void setRotations(Rot2f rotations, double rotationSpeed, Function<Rot2f, Boolean> raytrace) {
        setRotations(rotations, rotationSpeed, raytrace, Priority.Medium);
    }

    public void setRotations(Rot2f rotations, double rotationSpeed, Function<Rot2f, Boolean> raytrace, Priority priority) {
        if (rotations == null) return;

        if (rejectsPriority(priority)) {
            return;
        }

        this.targetRotations = rotations;
        this.rotationSpeed = rotationSpeed;
        this.raytrace = raytrace;
        this.priority = priority.priority;
        this.active = true;

        smooth();
        onRotationsSet();
    }

    /**
     * 当前活动旋转的优先级是否高于该请求；用于请求合并，也用于判断是否需要切换托管模式。
     *
     * @param priority 待提交的请求优先级
     * @return 该请求会被当前活动旋转拒绝时返回 {@code true}
     */
    private boolean rejectsPriority(Priority priority) {
        return active && priority.priority < this.priority;
    }

    protected void onRotationsSet() {
    }

    protected void resetModeState() {
    }

    /**
     * 切换到其他模式前调用，用于把本模式遗留的服务端旋转状态收尾。
     */
    protected void onModeSwitchAway() {
    }

    protected void smooth() {
        if (!smoothed) {
            float targetYaw = targetRotations.getYaw();
            float targetPitch = targetRotations.getPitch();

            if (raytrace != null && (Math.abs(targetYaw - rotations.getYaw()) > 5 || Math.abs(targetPitch - rotations.getPitch()) > 5)) {
                final Rot2f trueTargetRotations = new Rot2f(targetRotations.getYaw(), targetRotations.getPitch());

                double speed = (Math.random() * Math.random() * Math.random()) * 20;
                randomAngle += (float) ((20 + (float) (Math.random() - 0.5) * (Math.random() * Math.random() * Math.random() * 360)) * (mc.player.tickCount / 10 % 2 == 0 ? -1 : 1));

                offset.set(
                        (float) (offset.getYaw() + -Mth.sin((float) Math.toRadians(randomAngle)) * speed),
                        (float) (offset.getPitch() + Mth.cos((float) Math.toRadians(randomAngle)) * speed)
                );

                targetYaw += offset.getYaw();
                targetPitch += offset.getPitch();

                if (!raytrace.apply(new Rot2f(targetYaw, targetPitch))) {
                    randomAngle = (float) Math.toDegrees(Math.atan2(trueTargetRotations.getYaw() - targetYaw, targetPitch - trueTargetRotations.getPitch())) - 180;

                    targetYaw -= offset.getYaw();
                    targetPitch -= offset.getPitch();

                    offset.set(
                            (float) (offset.getYaw() + -Mth.sin((float) Math.toRadians(randomAngle)) * speed),
                            (float) (offset.getPitch() + Mth.cos((float) Math.toRadians(randomAngle)) * speed)
                    );

                    targetYaw = targetYaw + offset.getYaw();
                    targetPitch = targetPitch + offset.getPitch();
                }

                if (!raytrace.apply(new Rot2f(targetYaw, targetPitch))) {
                    offset.set(0, 0);

                    targetYaw = (float) (targetRotations.getYaw() + Math.random() * 2);
                    targetPitch = (float) (targetRotations.getPitch() + Math.random() * 2);
                }
            }

            rotations = RotationUtils.smooth(new Rot2f(targetYaw, targetPitch), rotationSpeed + Math.random());
        }

        smoothed = true;

        updateHitResult();
        if (shouldModifyCrosshair()) {
            mc.pick(1.0f);
        }
    }

    private void updateHitResult() {
        if (!hasActiveRotation() || mc.player == null || mc.level == null) {
            rotationHitResult = null;
            return;
        }

        calculatingHitResult = true;
        try {
            rotationHitResult = mc.player.raycastHitResult(1.0f, mc.player);
        } finally {
            calculatingHitResult = false;
        }
    }

    protected boolean shouldModifyCrosshair() {
        return false;
    }

    protected final boolean hasActiveRotation() {
        return active && rotations != null;
    }

    protected static float clampPitch(float pitch) {
        return Mth.clamp(pitch, -90.0F, 90.0F);
    }

    protected void correctDisabledRotations(LocalPlayer player) {
        Rot2f playerRotation = new Rot2f(player.getYRot(), player.getXRot());
        Rot2f fixedRotations = RotationUtils.applySensitivityPatch(playerRotation, lastRotations);
        float fixedYaw = fixedRotations.getYaw() + Mth.wrapDegrees(player.getYRot() - fixedRotations.getYaw());
        player.setYRot(fixedYaw);
    }

    public float getYaw() {
        return getRotation().getYaw();
    }

    public float getPitch() {
        return getRotation().getPitch();
    }

    public Rot2f getRotation() {
        return active ? rotations : new Rot2f(mc.player.getYRot(), mc.player.getXRot());
    }

    public Rot2f getLastRotation() {
        return lastRotations != null ? lastRotations : new Rot2f(mc.player.yRotO, mc.player.xRotO);
    }

    /**
     * 获取按当前托管旋转计算的逻辑命中结果。未启用托管旋转时返回原版准星结果。
     *
     * @return 当前逻辑命中结果
     */
    public HitResult getHitResult() {
        return hasActiveRotation() && rotationHitResult != null ? rotationHitResult : mc.hitResult;
    }

    public boolean isActive() {
        return active;
    }

    public void setActive(boolean active) {
        this.active = active;
    }

    public boolean isSmoothed() {
        return smoothed;
    }

    public void setSmoothed(boolean smoothed) {
        this.smoothed = smoothed;
    }

    public void copyStateFrom(RotationManager manager) {
        this.rotations = manager.rotations;
        this.lastRotations = manager.lastRotations;
        this.targetRotations = manager.targetRotations;
        this.animationRotation = manager.animationRotation;
        this.lastAnimationRotation = manager.lastAnimationRotation;
        this.active = manager.active;
        this.smoothed = manager.smoothed;
        this.rotationSpeed = manager.rotationSpeed;
        this.raytrace = manager.raytrace;
        this.priority = manager.priority;
        this.rotationHitResult = manager.rotationHitResult;
    }

    @EventHandler
    protected void onRespawn(RespawnEvent event) {
        offset.set(0, 0);
        rotations = new Rot2f(0, 0);
        lastRotations = new Rot2f(0, 0);
        targetRotations = null;
        animationRotation = null;
        lastAnimationRotation = null;
        active = false;
        priority = 0;
        smoothed = false;
        raytrace = null;
        randomAngle = 0;
        rotationHitResult = null;
        calculatingHitResult = false;
        resetModeState();
    }

    @EventHandler
    private void onRaytrace(RaytraceEvent event) {
        if (!hasActiveRotation() || (!calculatingHitResult && !shouldModifyCrosshair())) return;

        event.setYaw(rotations.getYaw());
        event.setPitch(clampPitch(rotations.getPitch()));
    }

    @EventHandler(priority = -1000)
    protected void onPlayerTick(PlayerTickEvent.Pre event) {
        if (!active || rotations == null || lastRotations == null || targetRotations == null) {
            rotations = lastRotations = targetRotations = new Rot2f(mc.player.getYRot(), mc.player.getXRot());
        }

        if (hasActiveRotation()) {
            smooth();
            afterPlayerTick();
        }
    }

    protected void afterPlayerTick() {
    }

    @EventHandler
    protected void onAnimation(RotationAnimationEvent event) {
        if (active && animationRotation != null && lastAnimationRotation != null) {
            event.setYaw(animationRotation.getYaw());
            event.setLastYaw(lastAnimationRotation.getYaw());
            event.setPitch(animationRotation.getPitch());
            event.setLastPitch(lastAnimationRotation.getPitch());
        }
    }

    @EventHandler(priority = EventPriority.LOWEST)
    protected void onSendPosition(SendPositionEvent event) {
        LocalPlayer player = mc.player;
        if (player == null) {
            active = false;
            priority = 0;
            targetRotations = null;
            raytrace = null;
            smoothed = false;
            rotationHitResult = null;
            calculatingHitResult = false;
            resetModeState();
            return;
        }

        if (active && rotations != null) {
            handleSendPosition(event);

            if (Math.abs((rotations.getYaw() - player.getYRot()) % 360) < 1 && Math.abs((rotations.getPitch() - player.getXRot())) < 1) {
                active = false;
                priority = 0;
                this.correctDisabledRotations(player);
            }

            lastRotations = rotations;
        } else {
            lastRotations = new Rot2f(player.getYRot(), player.getXRot());
        }

        lastAnimationRotation = animationRotation;
        animationRotation = new Rot2f(event.getYaw(), event.getPitch());
        targetRotations = new Rot2f(player.getYRot(), player.getXRot());
        raytrace = null;
        smoothed = false;
    }

    protected abstract void handleSendPosition(SendPositionEvent event);

    /**
     * 切换托管旋转模式。
     * <p>
     * 实例按模式复用：模块级转头方式会让同一 tick 内在 Silent 与 Snap 之间切换，每次新建实例都会在
     * {@link EventBus} 的 listener 缓存里留下一条不会回收的记录。切换前先让旧模式收尾（Snap 需要把
     * 快照角度发回服务端），切换后清空新模式遗留的临时状态，再迁移共享状态。
     *
     * @param mode 目标模式
     */
    public static void switchRotationManager(RotationManager.RotationMode mode) {
        RotationManager previous = INSTANCE;
        if (previous != null && previous.mode == mode) return;

        RotationManager next = CACHED_MANAGERS.computeIfAbsent(mode, m -> switch (m) {
            case SNAP -> new SnapRotationManager();
            case SILENT -> new SilentRotationManager();
        });

        if (previous != null) {
            previous.onModeSwitchAway();
            EventBus.INSTANCE.unsubscribe(previous);
        }

        next.resetModeState();
        if (previous != null) next.copyStateFrom(previous);

        INSTANCE = next;
        EventBus.INSTANCE.subscribe(next);
    }

}
