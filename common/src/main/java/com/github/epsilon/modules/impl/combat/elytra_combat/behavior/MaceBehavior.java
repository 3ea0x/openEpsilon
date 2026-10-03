package com.github.epsilon.modules.impl.combat.elytra_combat.behavior;

import com.github.epsilon.modules.impl.combat.elytra_combat.ElytraCombat;
import com.github.epsilon.modules.impl.combat.elytra_combat.combat.CombatHitTracker;
import com.github.epsilon.modules.impl.combat.elytra_combat.flight.ElytraDebug;
import com.github.epsilon.modules.impl.combat.elytra_combat.flight.FlightIntent;
import com.github.epsilon.modules.impl.combat.elytra_combat.flight.FlightIntentPlanner;
import com.github.epsilon.modules.impl.combat.elytra_combat.flight.FlightPlanConfig;
import com.github.epsilon.modules.impl.combat.elytra_combat.flight.LocalFlightAvoidance;
import com.github.epsilon.modules.impl.combat.elytra_combat.flight.SlimefunGeometry;
import com.github.epsilon.modules.impl.combat.elytra_combat.target.TargetSnapshot;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * 重锤空袭状态机。
 *
 * <p>状态流转为 NONE -> PULL_UP -> FOLLOW。攻击本身交给 KillAura，本状态机只负责占位与高度：
 * 拉升到目标上方配置高度后在目标上方跟随，等待 KillAura 出手。</p>
 */
public class MaceBehavior implements ElytraCombatBehavior {

    private enum State {
        /**
         * 初始状态：根据当前高度差决定直接跟随还是先拉升。
         */
        NONE,
        /**
         * 持续拉升到目标上方安全高度。
         */
        PULL_UP,
        /**
         * 空中跟随或搜索地面目标的落点。
         */
        FOLLOW
    }

    private State state = State.NONE;
    private int pullUpStartTick;
    /** 头顶受阻 / 脚下受阻的连续 tick 数，用于抑制状态在边界上逐 tick 互抢。 */
    private int headBlockedTicks;
    private int feetBlockedTicks;
    /** 已选中的地面落点；沿用它可以避免逐 tick 在相邻候选间跳点（低空绕圈）。 */
    private BlockPos lastGroundCandidate;
    /** 俯冲攻击后的改出剩余 tick 数。 */
    private int recoveryTicks;
    /** 本次俯冲进入攻击距离后还剩几个下压 tick；归零说明这一趟打空了。 */
    private int strikeTicks;

    /** 探测条件必须连续成立这么多 tick 才允许切换状态，避免拉升与下压互相抢转向。 */
    private static final int PROBE_LATCH_TICKS = 3;

    /**
     * 俯冲进攻击距离后保持下压的 tick 数。
     *
     * <p>进入攻击距离就立刻改判拉升的话，只要 KillAura 这一次冷却没赶上，意图就会在攻击距离边界上
     * 逐 tick 互切，表现为俯冲没打中就在目标周围绕圈。这里先留几个 tick 的下压窗口给它出手，
     * 窗口用完还没出手才算这一趟打空，拉起来重新俯冲。</p>
     */
    private static final int STRIKE_TICKS = 4;

    /**
     * "这一趟俯冲打空了"的高度带（格）。
     *
     * <p>已经俯冲到目标所在的高度带，说明这次下压的高度用光了，而这几 tick 里 KillAura 一次都没
     * 出手；此时若还在攻击距离外，继续压是压不进去的——滑翔的最小转弯半径 ≈ 速度 / 最大角速度
     * （Max Turn Speed=15°/tick、实际速度 1.5 格/tick 时约 6 格）比交互距离还大，结果就是贴着目标
     * 高度在攻击距离外画圈。所以到这条线还没出手就按打空处理，拉起来重新俯冲。</p>
     */
    private static final double MISS_ALTITUDE_BAND = 3.0;

    /**
     * 拉升到"目标上方配置高度"的到达容差（格）。
     *
     * <p>拉升的瞄准点就是这条高度线，滑翔物理靠近它时垂直分量会趋近 0（日志里 maneuver.pullup 的 y
     * 只剩 0.4~2 格），严格比较就永远差零点几格到不了阈值，只在阈值下方水平绕圈，一直等到"拉升
     * 超时"才改出——表现为拉升完在天上绕一两圈再俯冲。差得不多就按到达处理，直接进入俯冲。</p>
     */
    private static final double PULL_UP_ARRIVE_TOLERANCE = 2.0;

    @Override
    public void reset() {
        this.state = State.NONE;
        this.pullUpStartTick = 0;
        this.headBlockedTicks = 0;
        this.feetBlockedTicks = 0;
        this.lastGroundCandidate = null;
        this.recoveryTicks = 0;
        this.strikeTicks = 0;
    }

    @Override
    public FlightIntent tick(
            ElytraCombat bot,
            TargetSnapshot target,
            FlightIntentPlanner planner,
            FlightPlanConfig planConfig
    ) {
        if (target == null) {
            reset();
            return FlightIntent.idle(bot.playerLook());
        }

        int tick = bot.player().tickCount;
        Vec3 targetPos = bot.maceUsePredictor.getValue() ? target.predictedPosition() : target.position();
        Vec3 desired;

        switch (this.state) {
            case NONE -> {
                // 已经处于俯冲高度时无需再拉升，直接进入跟随段。
                if (bot.player().fallDistance > 4.0
                        && bot.player().getY() > target.entity().getY() + 4.0) {
                    this.state = State.FOLLOW;
                } else {
                    enterPullUp(tick);
                }
                desired = pullUp(bot, targetPos, target);
            }
            case PULL_UP -> {
                if (this.pullUpStartTick <= 0) {
                    this.pullUpStartTick = tick;
                    // 刚进入拉升（通常是俯冲攻击命中后）：先走改出阶段，避免立刻回头绕圈。
                    this.recoveryTicks = RECOVERY_TICKS;
                }
                // 头顶被挡时无法继续拉升，但必须连续受阻一会儿才放弃：单 tick 抖动会让
                // 「拉升（抬头）」和「跟随（朝目标下压）」逐 tick 互抢，表现为上升时转头抽风。
                if (headBlocked(bot)) {
                    this.headBlockedTicks++;
                } else {
                    this.headBlockedTicks = 0;
                }
                if (this.headBlockedTicks >= PROBE_LATCH_TICKS) {
                    this.state = State.FOLLOW;
                    desired = follow(bot, targetPos, target);
                    break;
                }

                // 高度达到配置值（留到达容差），或拉升超时且已高于目标时，开始接近。
                // 高度阈值与拉升方向使用同一个高度（地面目标用 Mace Ground Height），否则两个设置
                // 不一致时会在"拉升 / 跟随"之间反复切换，表现为低空来回。
                double followHeight = target.supported()
                        ? bot.maceGroundHeight.getValue()
                        : bot.maceHeight.getValue();
                double aimY = target.entity().getY() + followHeight;
                boolean arrived = bot.player().getY() > target.entity().getY()
                        && aimY - bot.player().getY() <= PULL_UP_ARRIVE_TOLERANCE;
                // 超时用纯 tick 数：原来把高度（格）当成 tick 加进去，Mace Ground Height=17 时实际超时
                // 被放大到 37 tick，绕圈时间也跟着翻倍。
                boolean mayFollow = arrived
                        || (bot.player().getY() > target.entity().getY()
                        && tick - this.pullUpStartTick > bot.macePullUpTicks.getValue());
                if (mayFollow) {
                    this.state = State.FOLLOW;
                    desired = target.supported() && !inAttackRange(bot, target)
                            ? groundApproach(bot, target)
                            : followOrHold(bot, targetPos, target);
                } else {
                    if (this.recoveryTicks > 0) {
                        this.recoveryTicks--;
                        desired = recover(bot, targetPos, target);
                    } else {
                        desired = pullUp(bot, targetPos, target);
                    }
                }
            }
            case FOLLOW -> {
                // 地面目标需要先找可攻击落点；空中目标直接追预测位置。
                if (target.supported()) {
                    desired = strikeOrReapproach(bot, targetPos, target, tick);
                } else if (bot.chaseMode.is(ChaseMode.Slimefun) && slimefunFeetBlocked(bot, true)) {
                    // Slimefun：脚下被挡说明高度不够，重新拉升（攻击交给 KillAura）。
                    // 同样加迟滞：脚下探测的抖动会让拉升与跟随互抢。
                    enterPullUp(tick);
                    desired = pullUp(bot, targetPos, target);
                } else if (bot.player().fallDistance < 1.0E-6 && bot.lastFallDistance > 1.0E-6) {
                    enterPullUp(tick);
                    desired = pullUp(bot, targetPos, target);
                } else {
                    // 攻击统一交给 KillAura；本状态机只负责占位与高度。
                    desired = follow(bot, targetPos, target);
                }
            }
            default -> throw new IllegalStateException("Unknown mace state " + this.state);
        }

        bot.lastFallDistance = bot.player().fallDistance;
        ElytraDebug.log(ElytraDebug.SLOT_MACE_STATE, "mace.state",
                this.state.name()
                        + " head=" + this.headBlockedTicks
                        + " feet=" + this.feetBlockedTicks
                        + " y=" + ElytraDebug.fmt(bot.player().getY())
                        + " ty=" + ElytraDebug.fmt(targetPos.y)
                        + " fall=" + ElytraDebug.fmt(bot.player().fallDistance));
        if (desired.lengthSqr() < 1.0E-8) {
            return FlightIntent.idle(bot.playerLook());
        }
        FlightIntent raw = new FlightIntent(
                desired,
                desired.normalize(),
                bot.controlMode.is(ElytraCombat.ControlMode.DirectVelocity),
                true
        );
        return planner.plan(bot.player(), raw, target.predictedPosition(), planConfig);
    }

    /**
     * KillAura 出手后立刻改出。
     *
     * <p>攻击交给 KillAura 之后，"攻击后拉升"只能由本地攻击事件触发，命中反馈两条路在滑翔时都不可靠：</p>
     * <ul>
     *   <li>滑翔时原版 {@code MaceItem.canSmashAttack} 要求 {@code !isFallFlying()}，本地根本算不出
     *       MACE_SMASH；服务端打出猛击时，{@code ClientboundDamageEventPacket} 也只在"未被无敌帧削伤"
     *       的那一次广播（目标 {@code damageCooldownTime > 10} 的补刀不广播），日志里连续出现的
     *       {@code fall=1.00}（{@code checkFallDistanceAccumulation} 把 fallDistance 压到 1.0）就是
     *       猛击已经发生、但状态机一次都没收到 MACE 命中的证据。</li>
     *   <li>{@code FOLLOW} 里靠 {@code fallDistance} 归零判断"刚打完"同样失效：滑翔中该值只会被压到
     *       1.0，不会回到 0。</li>
     * </ul>
     */
    @Override
    public void onAttack(LivingEntity target) {
        beginRecoveryPullUp("attack");
    }

    @Override
    public void onHit(CombatHitTracker.HitType hitType) {
        if (hitType == CombatHitTracker.HitType.MACE) {
            beginRecoveryPullUp("mace hit");
        }
    }

    /**
     * 进入带改出阶段的拉升：{@code pullUpStartTick} 置 -1，下一 tick 由 {@link #tick} 初始化，
     * 先沿当前航向爬升 {@link #RECOVERY_TICKS} 个 tick 再回头瞄准目标上方高度。
     */
    private void beginRecoveryPullUp(String reason) {
        this.state = State.PULL_UP;
        this.pullUpStartTick = -1;
        ElytraDebug.log(ElytraDebug.SLOT_MACE_STATE, "mace.trigger", reason + " -> PULL_UP");
    }

    @Override
    public String stateName() {
        return this.state.name();
    }

    private void enterPullUp(int tick) {
        this.state = State.PULL_UP;
        this.pullUpStartTick = tick;
    }

    private Vec3 pullUpDirection(ElytraCombat bot, Vec3 targetPos, TargetSnapshot target) {
        double height = target.supported() ? bot.maceGroundHeight.getValue() : bot.maceHeight.getValue();
        Vec3 movement = new Vec3(targetPos.x, targetPos.y + height, targetPos.z)
                .subtract(bot.player().position());
        return ensureMinimumLength(movement, 5.0);
    }

    private Vec3 followDirection(ElytraCombat bot, Vec3 targetPos, TargetSnapshot target) {
        Vec3 movement = targetPos.subtract(bot.player().position());
        if (bot.maceYBias.getValue() != 0.0 && !target.supported()) {
            movement = movement.add(0.0, bot.maceYBias.getValue(), 0.0);
        }
        if (bot.maceSmoothFlight.getValue()
                && movement.y < 0.0
                && bot.player().getY() > target.entity().getY() + bot.maceFollowMinHeight.getValue()) {
            double horizontal = Math.max(0.001, movement.horizontalDistance());
            double downAngle = bot.maceAngleOptimize.getValue() ? bot.maceDownAngle.getValue() : 30.5;
            movement = new Vec3(
                    movement.x,
                    -horizontal * Math.tan(Math.toRadians(downAngle)),
                    movement.z
            );
        }
        return ensureMinimumLength(movement, 5.0);
    }

    private boolean headBlocked(ElytraCombat bot) {
        Vec3 position = bot.player().position();
        boolean blocked = !LocalFlightAvoidance.isSegmentClear(bot.player(), position, position.add(0.0, 0.1, 0.0));
        if (ElytraDebug.enabled) {
            ElytraDebug.log(ElytraDebug.SLOT_PROBE, "probe.head",
                    blocked + " run=" + (blocked ? this.headBlockedTicks + 1 : 0));
        }
        return blocked;
    }

    /**
     * 已在攻击距离内时的行为：攻击由 KillAura 负责，这里只需要停在配置高度上方，
     * 不再去找地面落点——落点跳点会让玩家在目标周围低空绕圈。
     */
    private Vec3 followOrHold(ElytraCombat bot, Vec3 targetPos, TargetSnapshot target) {
        return pullUp(bot, targetPos, target);
    }

    /**
     * 地面目标的俯冲接触段：压住目标等下压窗口，打空才拉起来重新俯冲。
     *
     * <p>原来"进入攻击距离"就直接改判成拉升（爬回 Mace Ground Height）：只要 KillAura 这一次
     * 冷却没赶上，意图就会在攻击距离边界上逐 tick 互切，表现为俯冲下来没打中就在目标周围绕圈；
     * 而每次只爬高一点点又立刻俯冲，水平方向越绕越偏，最后带着残余速度飞离目标。</p>
     *
     * <p>命中由 {@code onAttack} 直接进入 PULL_UP，这里只负责"没命中的那趟"：先保持
     * {@link #STRIKE_TICKS} 个 tick 的下压给 KillAura 出手机会，窗口内没出手就按俯冲打空处理，
     * 沿当前航向改出（{@code recover}）再回到目标上方重新俯冲。</p>
     */
    private Vec3 strikeOrReapproach(ElytraCombat bot, Vec3 targetPos, TargetSnapshot target, int tick) {
        if (inAttackRange(bot, target)) {
            if (this.strikeTicks <= 0) {
                // 刚进入攻击距离：开一个下压窗口，让 KillAura 有机会出手。
                this.strikeTicks = STRIKE_TICKS;
                return groundApproach(bot, target);
            }
            if (--this.strikeTicks > 0) {
                return groundApproach(bot, target);
            }
            // 窗口用完还没出手：这一趟俯冲打空，沿当前航向改出后重新拉升。
            this.strikeTicks = 0;
            beginRecoveryPullUp(missReason(bot, target));
            return recover(bot, targetPos, target);
        }

        // 不在攻击距离：已经降到目标的高度带就是打空了。这里必须收手——继续 groundApproach 只会在
        // 攻击距离外贴着目标高度绕圈（日志里整局 d 都在 4.6~7.0、|Δy| 都在 3 以内），既打不到也
        // 落不下去，永远等不到下一次俯冲。
        if (Math.abs(bot.player().getY() - target.entity().getY()) < MISS_ALTITUDE_BAND) {
            this.strikeTicks = 0;
            beginRecoveryPullUp(missReason(bot, target));
            return recover(bot, targetPos, target);
        }

        // 还没降到目标高度带：继续俯冲接近。
        this.strikeTicks = 0;
        return groundApproach(bot, target);
    }

    /** 打空时的调试原因：带上距离与高度差，便于分辨是"够不着"还是"KillAura 没出手"。 */
    private static String missReason(ElytraCombat bot, TargetSnapshot target) {
        return "miss d=" + ElytraDebug.fmt(bot.player().distanceTo(target.entity()))
                + " dy=" + ElytraDebug.fmt(bot.player().getY() - target.entity().getY());
    }

    /** KillAura 是否已能打到该目标。 */
    private boolean inAttackRange(ElytraCombat bot, TargetSnapshot target) {
        return bot.player().isWithinEntityInteractionRange(target.entity().getBoundingBox(), 0.5);
    }

    /**
     * 俯冲攻击后的改出：保留当前水平航向、把速度转成高度。
     *
     * <p>攻击命中时玩家就在目标身边、速度是俯冲方向。若立刻瞄准"目标上方配置高度"，水平分量
     * 会指向身后，加上转向限速（Max Turn Speed）就会先绕一圈再爬升。先沿当前航向拉起来
     * （缩放式爬升），几 tick 后再回头，轨迹就是一条上升弧。</p>
     */
    private Vec3 recover(ElytraCombat bot, Vec3 targetPos, TargetSnapshot target) {
        LocalPlayer player = bot.player();
        Vec3 velocity = player.getDeltaMovement();
        Vec3 horizontal = new Vec3(velocity.x, 0.0, velocity.z);
        if (horizontal.lengthSqr() < 1.0E-4) {
            return pullUp(bot, targetPos, target);
        }
        double height = target.supported() ? bot.maceGroundHeight.getValue() : bot.maceHeight.getValue();
        double climb = targetPos.y + height - player.getY();
        return horizontal.normalize().scale(RECOVERY_HORIZONTAL_SPEED)
                .add(0.0, Math.max(RECOVERY_CLIMB, climb), 0.0);
    }

    /**
     * 地面目标被遮挡时，在射线命中点周围搜索满足攻击距离、视线和碰撞空间的候选落点。
     */
    private Vec3 groundApproach(ElytraCombat bot, TargetSnapshot target) {
        Vec3 playerPos = bot.player().position();
        Vec3 targetEye = target.entity().getEyePosition();
        BlockHitResult hit = bot.player().level().clip(new net.minecraft.world.level.ClipContext(
                bot.player().getEyePosition(),
                targetEye,
                net.minecraft.world.level.ClipContext.Block.COLLIDER,
                net.minecraft.world.level.ClipContext.Fluid.NONE,
                bot.player()
        ));
        if (hit.getType() == HitResult.Type.MISS) {
            return ensureMinimumLength(targetEye.subtract(playerPos), 5.0);
        }

        int radius = Math.min(4, Math.max(1, (int) Math.ceil(bot.maceEngageRange.getValue())));
        BlockPos hitPos = hit.getBlockPos();
        List<BlockPos> candidates = new ArrayList<>((radius * 2 + 1) * (radius * 2 + 1) * (radius * 2 + 1));
        for (int x = hitPos.getX() - radius; x <= hitPos.getX() + radius; x++) {
            for (int y = hitPos.getY() - radius; y <= hitPos.getY() + radius; y++) {
                for (int z = hitPos.getZ() - radius; z <= hitPos.getZ() + radius; z++) {
                    candidates.add(new BlockPos(x, y, z));
                }
            }
        }
        candidates.sort(Comparator.comparingDouble(pos -> Vec3.atCenterOf(pos).distanceToSqr(targetEye)));

        // 沿用上一次选中的落点：候选按到目标眼睛的距离排序，玩家一移动"第一个合格点"就会换成
        // 相邻格子，逐 tick 跳点表现为绕着目标低空转圈。只要旧落点仍可用就继续飞过去。
        if (this.lastGroundCandidate != null) {
            Vec3 center = Vec3.atCenterOf(this.lastGroundCandidate);
            if (playerPos.distanceToSqr(center) < 2.25 || !isGroundCandidateUsable(bot, target, center)) {
                this.lastGroundCandidate = null;
            } else {
                return ensureMinimumLength(center.subtract(playerPos), 5.0);
            }
        }

        for (BlockPos candidatePos : candidates) {
            Vec3 center = Vec3.atCenterOf(candidatePos);
            if (!isGroundCandidateUsable(bot, target, center)) {
                continue;
            }
            this.lastGroundCandidate = candidatePos;
            return ensureMinimumLength(center.subtract(playerPos), 5.0);
        }
        this.lastGroundCandidate = null;
        return ensureMinimumLength(targetEye.subtract(playerPos), 5.0);
    }

    /**
     * 落点是否仍然可用：满足攻击距离（或视线可达）且玩家碰撞箱能放下。
     */
    private boolean isGroundCandidateUsable(ElytraCombat bot, TargetSnapshot target, Vec3 center) {
        if (!bot.player().isWithinEntityInteractionRange(target.entity().getBoundingBox(), 0.5)
                && center.distanceToSqr(target.entity().getEyePosition()) > bot.maceEngageRange.getValue() * bot.maceEngageRange.getValue()) {
            return false;
        }
        if (bot.player().level().clip(new net.minecraft.world.level.ClipContext(
                bot.player().position(),
                center,
                net.minecraft.world.level.ClipContext.Block.COLLIDER,
                net.minecraft.world.level.ClipContext.Fluid.NONE,
                bot.player()
        )).getType() != HitResult.Type.MISS) {
            return false;
        }
        AABB box = bot.player().getDimensions(bot.player().getPose()).makeBoundingBox(center);
        return bot.player().level().noBlockCollision(bot.player(), box);
    }

    private static Vec3 ensureMinimumLength(Vec3 vector, double minimum) {
        if (vector.lengthSqr() < 1.0E-8) {
            return Vec3.ZERO;
        }
        return vector.length() < minimum ? vector.normalize().scale(minimum) : vector;
    }

    // ===== Slimefun 侧移植：SlimefunHelper ElytraBot 的 MaceArua 追击几何 =====

    /** 绕飞半径额外值（combat-smooth-flight-circle-extra-range）。 */
    private static final double SLIMEFUN_CIRCLE_EXTRA_RANGE = 1.0;
    /** 绕飞外抛比例（combat-smooth-flight-out-pull-ratio）。 */
    private static final double SLIMEFUN_OUT_PULL_RATIO = 1.0;
    /** 上抬攻击的水平距离上限 / 垂直分量 / 相对高度区间。 */
    private static final double SLIMEFUN_ATTACK_PULL_MAX_HORIZONTAL = 10.0;
    private static final double SLIMEFUN_ATTACK_PULL_UP = 10.0;
    private static final double SLIMEFUN_ATTACK_PULL_MIN_RELATIVE_Y = 0.0;
    private static final double SLIMEFUN_ATTACK_PULL_MAX_RELATIVE_Y = 10.0;
    /** 拉升方向保持的判定距离（pullup-persistent-direction）。 */
    private static final double SLIMEFUN_PULLUP_PERSISTENT_DISTANCE = 6.0;
    /** 跟随预测线的起始距离（start-predict-distance）。 */
    private static final double SLIMEFUN_FOLLOW_START_PREDICT_DISTANCE = 10.0;
    /** 跟随俯冲的最小俯仰角（follow-min-pitch-deg）。 */
    private static final double SLIMEFUN_FOLLOW_MIN_PITCH_DEG = 15.0;
    /** 跟随瞄准点的高度插值权重（mace-y-level-lerp）与近身判定距离（choose-down-target-distance）。 */
    private static final double SLIMEFUN_FOLLOW_Y_LEVEL_WEIGHT = 0.0;
    private static final double SLIMEFUN_FOLLOW_DOWN_TARGET_DISTANCE = 3.0;
    /** 方向最小长度，Slimefun 侧统一为 5。 */
    private static final double SLIMEFUN_MIN_DIRECTION_LENGTH = 5.0;
    /** 目标高度判定的迟滞带（格），避免 yLow 在目标 Y 附近来回切换机动。 */
    private static final double SLIMEFUN_Y_LOW_HYSTERESIS = 1.0;
    /**
     * 拉升阶段是否启用切线绕飞（对应 SlimefunHelper 的 combat-smooth-flight-pullup，默认关闭）。
     */
    private static final boolean SLIMEFUN_PULLUP_ORBIT = false;
    /**
     * 拉升阶段是否启用"上抬攻击"（对应 combat-smooth-flight-pullup-attack，默认关闭）。
     * 开启会使用固定 +10 上抬并忽略 Mace Height。
     */
    private static final boolean SLIMEFUN_PULLUP_ATTACK = false;
    /**
     * 拉升阶段是否启用"方向保持"（对应 pullup-persistent-direction，默认关闭）。
     */
    private static final boolean SLIMEFUN_PULLUP_PERSISTENT_DIRECTION = false;
    /** 俯冲攻击后的改出阶段：持续 tick 数、水平速度与抬升分量。 */
    private static final int RECOVERY_TICKS = 5;
    private static final double RECOVERY_HORIZONTAL_SPEED = 6.0;
    private static final double RECOVERY_CLIMB = 8.0;

    private Vec3 pullUp(ElytraCombat bot, Vec3 targetPos, TargetSnapshot target) {
        Vec3 result = bot.chaseMode.is(ChaseMode.Slimefun)
                ? slimefunPullUpDirection(bot, targetPos, target)
                : pullUpDirection(bot, targetPos, target);
        ElytraDebug.log(ElytraDebug.SLOT_MACE_MANEUVER, "maneuver.pullup", vec(result));
        return result;
    }

    private Vec3 follow(ElytraCombat bot, Vec3 targetPos, TargetSnapshot target) {
        Vec3 result = bot.chaseMode.is(ChaseMode.Slimefun)
                ? slimefunFollowDirection(bot, targetPos, target)
                : followDirection(bot, targetPos, target);
        ElytraDebug.log(ElytraDebug.SLOT_MACE_MANEUVER, "maneuver.follow", vec(result));
        return result;
    }

    /** 调试用的向量格式化。 */
    private static String vec(Vec3 value) {
        return value == null ? "null"
                : "(" + ElytraDebug.fmt(value.x) + "," + ElytraDebug.fmt(value.y) + "," + ElytraDebug.fmt(value.z) + ")";
    }

    /**
     * Slimefun 的脚部探测：向下 0.1 格被挡说明高度不够。
     *
     * @param requireLatch 为真时要求连续受阻 {@link #PROBE_LATCH_TICKS} 个 tick 才算成立
     */
    private boolean slimefunFeetBlocked(ElytraCombat bot, boolean requireLatch) {
        Vec3 position = bot.player().position();
        boolean blocked = !LocalFlightAvoidance.isSegmentClear(bot.player(), position, position.add(0.0, -0.1, 0.0));
        this.feetBlockedTicks = blocked ? this.feetBlockedTicks + 1 : 0;
        if (ElytraDebug.enabled) {
            ElytraDebug.log(ElytraDebug.SLOT_PROBE, "probe.feet",
                    blocked + " run=" + this.feetBlockedTicks);
        }
        return blocked && (!requireLatch || this.feetBlockedTicks >= PROBE_LATCH_TICKS);
    }

    /**
     * 拉升几何：切线绕飞 → 上抬攻击 → 普通拉升（瞄准目标上方），再套方向保持与最小长度。
     */
    private Vec3 slimefunPullUpDirection(ElytraCombat bot, Vec3 targetPos, TargetSnapshot target) {
        LocalPlayer player = bot.player();
        Vec3 playerPos = player.position();
        boolean onGroundSupport = target.supported();
        // 1 格迟滞带：玩家 Y 在目标 Y 附近来回穿越时，切线绕飞与拉升会逐 tick 互切。
        boolean yLow = targetPos.y >= player.getY() - SLIMEFUN_Y_LOW_HYSTERESIS;
        Vec3 movement = null;
        boolean smoothHideFlight = false;

        // 1) 切线绕飞：对应来源项目的 combat-smooth-flight-pullup，该项在 SlimefunHelper 里默认关闭。
        // 它给出的方向几乎水平（只有 1e-2 抬升），在 Epsilon 的滑翔物理下无法爬升，
        // 会把状态机锁死在 PULL_UP 并绕目标转圈，因此这里同样默认关闭。
        if (SLIMEFUN_PULLUP_ORBIT && !onGroundSupport && yLow) {
            Vec3 center = target.entity().getBoundingBox().getCenter();
            double radius = bot.maceEngageRange.getValue() + SLIMEFUN_CIRCLE_EXTRA_RANGE;
            Vec3[] orbit = SlimefunGeometry.orbitDirections(center, player.getEyePosition(), radius, SLIMEFUN_OUT_PULL_RATIO);
            if (orbit.length > 0) {
                movement = orbit[orbit.length - 1].scale(10.0);
                smoothHideFlight = player.getEyePosition().distanceToSqr(center) < radius * radius;
            }
        }

        // 2) 上抬攻击：对应来源项目的 combat-smooth-flight-pullup-attack，该项在 SlimefunHelper
        // 里默认关闭。它使用固定 +10 上抬，只要水平距离够近就持续触发，会无视 Mace Height 一路爬升，
        // 因此这里同样默认关闭，拉升只走「瞄准目标上方配置高度」这一条。
        if (SLIMEFUN_PULLUP_ATTACK && movement == null && !onGroundSupport) {
            Vec3 toTarget = targetPos.subtract(playerPos);
            if (toTarget.horizontalDistance() < SLIMEFUN_ATTACK_PULL_MAX_HORIZONTAL) {
                if (targetPos.y + SLIMEFUN_ATTACK_PULL_MIN_RELATIVE_Y > player.getY()) {
                    movement = new Vec3(-toTarget.x, SLIMEFUN_ATTACK_PULL_UP, -toTarget.z);
                } else if (targetPos.y + SLIMEFUN_ATTACK_PULL_MAX_RELATIVE_Y > player.getY()) {
                    movement = new Vec3(toTarget.x, SLIMEFUN_ATTACK_PULL_UP, toTarget.z);
                }
            }
        }

        // 3) 普通拉升：瞄准目标上方高度。
        if (movement == null) {
            double height = onGroundSupport ? bot.maceGroundHeight.getValue() : bot.maceHeight.getValue();
            movement = new Vec3(targetPos.x, targetPos.y + height, targetPos.z).subtract(playerPos);
        }

        // 4) 方向保持：水平距离较小时若与最近移动方向相反则水平取反，避免原地来回。
        // 该翻转本身会让"速度与方向相反"的判定逐 tick 成立/失效，近距离低空时表现为绕圈，
        // 因此默认关闭（对应来源项目的 pullup-persistent-direction，需要时可打开）。
        if (SLIMEFUN_PULLUP_PERSISTENT_DIRECTION && !smoothHideFlight) {
            double horizontal = movement.horizontalDistance();
            if (horizontal > 1.0E-1 && SLIMEFUN_PULLUP_PERSISTENT_DISTANCE > horizontal
                    && player.getDeltaMovement().dot(movement) < 0.0) {
                movement = new Vec3(-movement.x, movement.y, -movement.z);
            }
        }

        return ensureMinimumLength(movement, SLIMEFUN_MIN_DIRECTION_LENGTH);
    }

    /**
     * Slimefun 跟随几何：预测线 → 直接瞄准 → 压到自身高度 → 压到 yLerp - MinFollowHeight，
     * 每个候选都要通过 Slimefun 的 {@code conditionMovement}（只接受下压方向，俯冲不够陡时要求仍能攻击）。
     */
    private Vec3 slimefunFollowDirection(ElytraCombat bot, Vec3 targetPos, TargetSnapshot target) {
        LocalPlayer player = bot.player();
        Vec3 playerPos = player.position();
        double yLerp = targetPos.y * SLIMEFUN_FOLLOW_Y_LEVEL_WEIGHT + player.getY() * (1.0 - SLIMEFUN_FOLLOW_Y_LEVEL_WEIGHT);
        boolean near = playerPos.distanceToSqr(targetPos)
                < SLIMEFUN_FOLLOW_DOWN_TARGET_DISTANCE * SLIMEFUN_FOLLOW_DOWN_TARGET_DISTANCE;

        List<Vec3> candidates = new ArrayList<>(4);
        if (playerPos.distanceTo(targetPos) > SLIMEFUN_FOLLOW_START_PREDICT_DISTANCE) {
            candidates.add(target.predictedPosition().subtract(playerPos));
        }
        if (!near) {
            candidates.add(targetPos.subtract(playerPos));
        }
        candidates.add(new Vec3(targetPos.x, player.getY(), targetPos.z).subtract(playerPos));
        if (near) {
            // 与 Slimefun 一致：只有近身时才尝试「压到 yLerp - MinFollowHeight」的低位候选。
            candidates.add(new Vec3(targetPos.x, yLerp - bot.maceFollowMinHeight.getValue(), targetPos.z).subtract(playerPos));
        }

        Vec3 movement = null;
        for (Vec3 candidate : candidates) {
            if (candidate.lengthSqr() < 1.0E-8) {
                continue;
            }
            Vec3 direction = candidate.normalize();
            if (slimefunFollowAllowed(bot, target, direction)) {
                movement = direction;
                break;
            }
        }
        if (movement == null) {
            // 俯冲角判定全部否决时清掉垂直分量，保持水平追击。
            for (Vec3 candidate : candidates) {
                Vec3 flat = new Vec3(candidate.x, 0.0, candidate.z);
                if (flat.lengthSqr() > 1.0E-8) {
                    movement = flat.normalize();
                    break;
                }
            }
        }
        if (movement == null) {
            return Vec3.ZERO;
        }

        if (target.supported()) {
            // 地面目标抬高瞄准点，避免贴地追击。
            movement = movement.add(0.0, bot.followGroundHeight.getValue(), 0.0);
        } else if (yLerp - bot.maceFollowMinHeight.getValue() > player.getY()) {
            // 高度不足时不再下压（Slimefun：这里已经是「没招了」的分支）。
            movement = new Vec3(movement.x, 0.0, movement.z);
        }

        if (movement.y >= 0.0) {
            return ensureMinimumLength(movement, SLIMEFUN_MIN_DIRECTION_LENGTH);
        }
        // 俯冲角限制：垂直分量不超过水平分量的最大值。
        double horizontalMax = Math.max(Math.abs(movement.x), Math.abs(movement.z));
        if (horizontalMax > 1.0E-1 && Math.abs(movement.y) > horizontalMax) {
            movement = new Vec3(movement.x, -horizontalMax, movement.z);
        }
        return ensureMinimumLength(movement, SLIMEFUN_MIN_DIRECTION_LENGTH);
    }

    /**
     * Slimefun 的 {@code conditionMovement}：只接受向下方向；俯仰角不足时要求当前仍在攻击距离内。
     */
    private boolean slimefunFollowAllowed(ElytraCombat bot, TargetSnapshot target, Vec3 direction) {
        if (direction.y >= 0.0) {
            return false;
        }
        float pitch = (float) -Math.toDegrees(Math.atan2(
                direction.y,
                Math.max(1.0E-3, direction.horizontalDistance())
        ));
        if (pitch < SLIMEFUN_FOLLOW_MIN_PITCH_DEG) {
            return bot.player().isWithinEntityInteractionRange(target.entity(), 0.5);
        }
        return true;
    }

}
