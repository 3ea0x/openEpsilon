# 配置与旋转

## 配置目录

`ConfigManager` 的根目录位于用户目录下的 `.epsilon/`：

```text
~/.epsilon/
├── active-config.txt
├── client-settings.json
├── accounts.json
├── configs/<name>/
│   ├── <addonId>/<moduleName>.json
│   ├── <addonId>/addon-settings.json
│   └── friends.json
├── imports/
└── exports/
```

- 模块、HUD 和 Addon Setting 按当前活动配置保存；`epsilon` 是本体模块与 HUD 的 `<addonId>`。
- `client-settings.json` 保存标记为 root 的客户端设置，`accounts.json` 保存账号列表。
- 配置支持新建、切换、删除、另存、重载、Zip 导入和导出；导出时写入 `config-info.json` 元数据。
- `saveNow()` 已由 JVM shutdown hook 调用，账号增删也会主动保存。
- `CONFIG_VERSION` 当前为 4；旧版 `config.json` 布局由 `LegacyConfigMigrator` 迁移到新目录结构。
- 配置名会做非法字符与 `..` 校验；Zip 导入通过 `unzipSecurely` 拒绝越界条目，不得绕过该校验。

## RotationManager

`RotationManager` 是抽象基类，实例为 `SilentRotationManager`（静默发包）与 `SnapRotationManager`
（直接发包旋转并恢复）。当前实例通过可变的 `RotationManager.INSTANCE` 获取，调用方不得缓存。

转头模式有两层范围，由 `ClientSetting.rotationScope`（Anti Cheat → Rotation 分组下的 `Rotation Scope`）决定：

- `RotationScope.Global`（默认）：所有模块共用 `ClientSetting.rotationMode`，模块自身的 `Rotation Type` 被忽略。
- `RotationScope.Custom`：按每个模块自己的 `Rotation Type` 分别选择：

| `RotationOption` | 行为 |
|---|---|
| `Silent` | 该模块的请求走 `SilentRotationManager` |
| `Snap` | 该模块的请求走 `SnapRotationManager` |
| `None` | 该模块不请求托管旋转，使用玩家真实视角 |

模块必须通过 `RotationManager.request(...)` 提交请求，不得直接调用 `RotationManager.INSTANCE.setRotations(...)`：
只有 `request` 会按上面的范围解析模式，并在需要时切换实例。`None` 或全局范围下被忽略的模块继续运行，
但所有读取都退化为玩家真实视角，需要瞄准才能生效的动作（例如依赖 `getRotation()` 命中判定的放置）不会执行。

```java
RotationManager.request(rotationType.getValue(), rotation, speed);
RotationManager.request(rotationType.getValue(), rotation, speed, Priority.High);
RotationManager.request(rotationType.getValue(), rotation, speed, raytrace, Priority.High);

boolean managed = RotationManager.isRotationManaged(rotationType.getValue());
```

`rotationType` 是模块自己的 `EnumSetting<RotationManager.RotationOption>`；`request(...)` 只接受
`RotationOption` 取值，没有接受 `Setting` 的重载。

`setRotations(...)` 仍然公开，供全局模式代码与 Addon 直接使用；它不参与范围解析。

实例在 `RotationManager.switchRotationManager(mode)` 中按模式缓存复用，切换时先让旧模式收尾
（`SnapRotationManager` 会把快照角度发回服务端），再清空新模式遗留状态并通过 `copyStateFrom()` 迁移共享状态。
`RotationManager.INSTANCE` 在 `ClientSetting` 构造时就会初始化，不依赖配置里是否存在该键。

主要读取 API：

```java
Rot2f current = RotationManager.INSTANCE.getRotation();
Rot2f previous = RotationManager.INSTANCE.getLastRotation();
HitResult hitResult = RotationManager.INSTANCE.getHitResult();
boolean active = RotationManager.INSTANCE.isActive();
```

旋转值类型为 `com.github.epsilon.utils.rotation.Rot2f`。`getHitResult()` 返回按当前托管旋转计算的逻辑
命中结果；没有活动旋转时返回原版 `mc.hitResult`。

Rotation priority 与 EventBus priority 是两套系统：

| Priority | 数值 |
|---|---:|
| `Lowest` | 0 |
| `Low` | 10 |
| `Medium` | 50 |
| `High` | 100 |
| `Highest` | 1000 |

仅当新 priority 不低于当前活动 priority 时才覆盖请求。

运行时行为：

- `Function<Rot2f, Boolean>` raytrace 会在平滑随机偏移校验中调用，且可能在一帧内被多次调用，必须无副作用。
- 平滑后通过 `LocalPlayer.raycastHitResult(1.0f, mc.player)` 更新逻辑命中结果。
- `shouldModifyCrosshair()` 决定是否把托管旋转应用到视觉准星；`SilentRotationManager` 在
  `ClientSetting.modifyCrosshair` 关闭或 FreeCamera 启用时不修改准星。
- `SilentRotationManager` 在 `SendPositionEvent` 与 `UseItemRaytraceEvent` 中写入托管旋转，并让物品
  使用包与移动包保持同一服务端旋转；`SnapRotationManager` 直接发送 `ServerboundMovePlayerPacket.PosRot`
  并在玩家 tick 后恢复真实旋转。
- 服务端位置/旋转包会重置平滑状态，下一次请求重新同步真实视角。
- 同一 tick 内多个模块请求不同模式时，`request()` 只在请求不会被优先级拒绝时才切换实例，避免反复切换。
- 需要等待命中后攻击/放置时，模块保存 pending 状态，每 tick 继续请求旋转，并用当前 `getRotation()`
  做 raytrace 后执行一次性动作。
- 旋转接近玩家真实角度时自动结束，没有 callback 或 `isDone()`。

## 静默旋转与移动结算

`MovementFix` 是默认启用的 MOVEMENT 模块，`SilentRotationManager` 通过它决定静默旋转是否改变移动方向：

- `onStrafe` 在 `MovementFix` 启用、存在活动旋转且玩家未在鞘翅飞行时，把 `StrafeEvent`（即 `Entity.moveRelative`
  使用的 yaw）换成托管旋转，移动方向因此按静默朝向结算。
- `onMoveInput`（priority `HIGH`）调用 `MovementFix.fixMovement`，把 `KeyboardInputEvent` 的 `forward`/`strafe`
  从镜头系换算到静默系，与上一条相抵，最终效果是静默旋转不改变移动方向（移动跟随镜头）。
- `onJump`、`onFallFlying` 按同样的条件把 `JumpEvent` / `FallFlyingEvent` 的 yaw（鞘翅还有 pitch）换成托管
  旋转；`MovementFix` 关闭或玩家正在鞘翅飞行时不做这些替换，静默朝向会直接改变移动方向。

要让移动跟随瞄准方向的模块，在 `KeyboardInputEvent` 上以低于 `HIGH` 的 priority（例如 `EventPriority.LOWEST`）
监听即可：此时 `MovementFix` 已经换算完毕，把玩家原始 WASD（`keyUp`/`keyDown`/`keyLeft`/`keyRight`）原样写回
`setForward`/`setStrafe`，原始输入按静默朝向解释就等于“沿瞄准方向移动”。不要在此基础上再按瞄准角度旋转一次
输入，否则实际方向会偏成 `2 * 瞄准角度 - 镜头角度`。不接管移动时不要改写该事件，否则会连带取消 `MovementFix`
的镜头系换算。
