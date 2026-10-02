package com.github.epsilon.modules.impl.combat.elytra_combat.behavior;

/**
 * Mace 与 Spear 行为层「追人机动」的来源选择。
 */
public enum ChaseMode {
    /**
     * 本项目原有实现：拉升瞄准目标上方、跟随带俯冲角限制与地面落点搜索。
     */
    Epsilon,
    /**
     * 移植自 SlimefunHelper ElytraBot 的 MaceArua / SpearAura 追击几何：
     * 拉升带切线绕飞与上抬攻击、跟随带预测线与俯冲角判定，长矛侧按目标动作选择脱战时机。
     */
    Slimefun
}
