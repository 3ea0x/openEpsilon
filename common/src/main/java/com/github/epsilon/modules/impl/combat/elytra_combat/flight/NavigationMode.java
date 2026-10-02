package com.github.epsilon.modules.impl.combat.elytra_combat.flight;

/**
 * 飞行规划器使用的避障策略。
 */
public enum NavigationMode {
    /**
     * 本项目原有实现：直飞探测 → 后台 A* → 局部扇区避障，无路可走时交还手动控制。
     */
    AStar,
    /**
     * 移植自 SlimefunHelper ElytraBot 的反应式机动：不做图搜索，用原版单步碰撞预演筛选
     * 「直冲 / 切线绕飞 / 拉升 / 侧移」几个几何方向，全部被挡时仍然照直冲。
     */
    Slimefun
}
