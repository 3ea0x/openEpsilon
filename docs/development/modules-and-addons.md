# 模块与 Addon

## Module 基本模式

本体功能模块继承 `Module`。现有模块通常使用单例和私有构造函数：

```java
public class MyModule extends Module {

    public static final MyModule INSTANCE = new MyModule();

    private final SettingGroup sgGeneral = settingGroup("General");
    private final BoolSetting enabledOption = boolSetting("Enabled Option", true).group(sgGeneral);
    private final DoubleSetting range = doubleSetting(
            "Range", 3.0, 1.0, 6.0, 0.1,
            enabledOption::getValue
    ).group(sgGeneral);

    private MyModule() {
        super("My Module", Category.COMBAT);
    }

    @Override
    protected void onEnable() {
    }

    @Override
    protected void onDisable() {
    }

    @EventHandler
    private void onTick(PlayerTickEvent.Pre event) {
        if (nullCheck()) return;
    }
}
```

`Module.setEnabled(true)` 会先订阅事件、发送通知，再调用 `onEnable()`；禁用时先取消订阅、发送通知，
再调用 `onDisable()`。

`Module.resetCustomState()`、`saveCustomState()`、`loadCustomState(JsonObject)` 用于 Setting 之外的持久化
状态。`setDefaultEnabled()` 和 `setDefaultHidden()` 同时影响 `reset()` 行为；普通模块默认 disabled、hidden。

键位默认值为 `-1`。`Module.BindMode.Toggle` 在按下时切换，`Hold` 在按下时启用、松开时禁用。鼠标键由
`KeybindUtils` 编码：26.3 起键盘保存 SDL 扫描码，鼠标键保存 SDL 编号（左 1、中 2、右 3）。

## 自动使用物品（代替玩家按住右键）

原版 `Minecraft#handleKeybinds` 每 tick 都用 `options.keyUse.isDown()` 判断还要不要继续使用物品，键不是
按下状态就立刻 `releaseUsingItem`；而客户端 `isUsingItem()` 读的是实体数据标记，`LivingEntity.startUsingItem`
只在服务端写入该标记再同步回来。所以直接调用 `gameMode.useItem(...)` 起手，等服务端把标记同步回来之后
就会被原版松开，蓄力/进食保持不住。

需要自动蓄力、自动进食这类“替玩家按住右键”的行为时，改为把 `mc.options.keyUse` 保持按下（`setDown(true)`），
让原版自己完成起手与保持；不需要时再松开，由原版释放。触发条件只应看“手上拿着对应物品”这类由本模块直接
控制的状态，不要绑定到目标锁定、瞄准阶段等无关条件上；`SpearAura` 的 Auto Charge 就是手持长矛即蓄力，
与有没有锁定目标无关。`NoSlowdown` 的 GrimC0F 也按这个思路驱动进食。

释放要区分所有权：`SpearAura` 只在 `keyUse` 原本没按下时才接管，并用 `autoChargeKeyHeld` 标记这次按下是
本模块做的，释放时只松自己的键；`NoSlowdown` 的 `stop()`/超时路径直接 `setDown(false)`，没有归属标记，
会连带松开玩家自己按住的右键。模块禁用、不再手持对应物品或让出控制权时要及时还原按键。

按住右键的副作用是左键平A失效：蓄力期间原版 `handleKeybinds` 走 `isUsingItem()` 分支，只会把 `keyAttack`
的点击 `consumeClick` 掉而不结算。需要保留平A的模块要在检测到左键按下时先松开右键，并在 `isUsingItem()`
变回 false 之后用 `KeyMapping.click(mc.options.keyAttack.key)` 把这次被丢掉的点击补发一次（`SpearAura` 的
`pendingAttackClick` 就是这个用途，等待期间不能重新按住右键，并带 tick 超时避免补发落到很久以后）。

## Setting DSL

`Module` 与 `EpsilonAddon` 都实现 `SettingHost`，共享同一套 DSL，也都支持适用类型的 `onChanged` 重载。

可用设置：

- `boolSetting`、`intSetting`、`doubleSetting`、`enumSetting`、`colorSetting`
- `stringSetting`、`stringListSetting`、`keybindSetting`、`buttonSetting`
- `blockListSetting`、`itemListSetting`、`entityTypeListSetting`
- `enchantmentListSetting`、`soundEventListSetting`

完整重载以 `SettingHost.java` 为准。依赖类型为 `Setting.Dependency`；返回 `false` 时设置不可用且不可见：

```java
private final BoolSetting advanced = boolSetting("Advanced", false);
private final IntSetting threshold = intSetting(
        "Threshold", 50, 0, 100, 1,
        advanced::getValue,
        value -> refresh(value)
);
```

相关能力：

- `settingGroup(name)` 在顶层按名称忽略大小写复用分组。
- `SettingGroup.child(name)` 在父分组下按名称忽略大小写复用子分组，可以继续嵌套；子分组只属于创建它的
  父分组，父子关系决定 GUI 缩进和翻译 key 层级。
- `.group(group)` 仅指定 GUI 分组（可以是任意层级的子分组），不负责注册 Setting。
- `.rootSetting()` 表示值由根配置单独持久化；当前 `ClientSetting.showWelcomeScreen`、`WorldTweaks`
  的雾与时间设置使用它。
- `.applyWhenRelease()` 表示滑动或编辑结束后再应用昂贵更新。
- `Setting.isAvailable()` 的语义由 dependency 决定；DSL 默认传入恒真的 dependency。

嵌套分组示例；父分组内直接 Setting 与首次出现的子分组按声明顺序交错渲染：

```java
private final SettingGroup sgWeapon = settingGroup("Weapon");
private final SettingGroup sgEnchants = sgWeapon.child("Enchants");
private final SettingGroup sgSword = sgEnchants.child("Sword");

private final BoolSetting autoSwitch = boolSetting("Auto Switch", true).group(sgWeapon);
private final IntSetting minLevel = intSetting("Min Level", 1, 1, 5, 1).group(sgSword);
```

## Addon

`EpsilonAddon` 提供元信息、Addon 自身设置和模块注册能力：

- 必须重写 `onSetup()`。
- 可选重写 `getDisplayName()`、`getDescription()`、`getVersion()`、`getAuthors()`。
- 在 `onSetup()` 中通过受保护的 `registerModule(module)` 注册 Addon 模块；注册会把模块交给
  `ModuleManager.registerAddonModule(...)` 并绑定 Addon 的翻译前缀。

`AddonManager` 按 ID 去重并只执行一次 setup，空 ID 和重复 ID 的注册会被忽略并记录警告；晚注册对象不会
自动初始化。`AddonManager.setupAddons()` 逐个隔离异常，单个 Addon 失败不会阻断其他 Addon。

平台收集方式：

- Fabric 使用自定义 entrypoint key `epsilon:addon`，入口实现 `FabricEpsilonAddonEntrypoint`。
- NeoForge 通过 `NeoForge.EVENT_BUS` 发布平台 `EpsilonAddonSetupEvent` 收集 Addon。

接入细节见 [Addon 开发](../addon-development.md)。强制注册、状态恢复和事件包前缀约束见
[`AGENTS.md`](../../AGENTS.md)。
