package com.github.epsilon.modules.impl.player;

import com.github.epsilon.events.bus.EventHandler;
import com.github.epsilon.events.impl.PlayerTickEvent;
import com.github.epsilon.modules.Category;
import com.github.epsilon.modules.Module;
import com.github.epsilon.settings.impl.BoolSetting;
import com.github.epsilon.utils.player.FindItemResult;
import com.github.epsilon.utils.player.InvUtils;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.protocol.game.ServerboundPlayerActionPacket;
import net.minecraft.tags.ItemTags;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;

/**
 * 主手持有工具/金苹果时，把背包里的食物换到副手或快捷栏，便于边打边吃。
 */
public class SmartTweak extends Module {

    public static final SmartTweak INSTANCE = new SmartTweak();

    private SmartTweak() {
        super("Smart Tweak", Category.PLAYER);
    }

    private final BoolSetting pickaxeSwitch = boolSetting("SwitchEat", true);
    public final BoolSetting offhand = boolSetting("Offhand", true, pickaxeSwitch::getValue);

    private boolean swapped;
    private int foodSlot;
    private int lastSlot;

    @Override
    protected void onDisable() {
        if (swapped) {
            restoreFood();
        }
    }

    @EventHandler
    public void onTick(PlayerTickEvent.Pre event) {
        if (nullCheck()) return;
        if (!pickaxeSwitch.getValue()) return;

        var handItem = mc.player.getMainHandItem();
        boolean isHotbarItem = handItem.is(ItemTags.PICKAXES) || handItem.is(ItemTags.SWORDS);
        if (!isHotbarItem && !handItem.is(Items.ENCHANTED_GOLDEN_APPLE) && !handItem.is(Items.GOLDEN_APPLE)) {
            if (swapped) restoreFood();
            return;
        }

        Item foodItem;
        FindItemResult food = InvUtils.findInHotbar(Items.ENCHANTED_GOLDEN_APPLE);
        if (food.found()) {
            foodItem = Items.ENCHANTED_GOLDEN_APPLE;
        } else {
            food = InvUtils.findInHotbar(Items.GOLDEN_APPLE);
            foodItem = Items.GOLDEN_APPLE;
        }
        if (!food.found()) {
            if (swapped) restoreFood();
            return;
        }

        if (mc.options.keyUse.isDown()) {
            if (isHotbarItem && !mc.player.getOffhandItem().is(foodItem) && !swapped) {
                foodSlot = food.slot();
                lastSlot = mc.player.getInventory().getSelectedSlot();
                if (offhand.getValue()) {
                    InvUtils.invSwap(foodSlot);
                    swapOffhand();
                    InvUtils.invSwap(foodSlot);
                } else {
                    InvUtils.swap(foodSlot, false);
                }
                swapped = true;
            }
        } else if (swapped) {
            restoreFood();
        }
    }

    private void restoreFood() {
        if (offhand.getValue()) {
            InvUtils.invSwap(foodSlot);
            swapOffhand();
            InvUtils.invSwap(foodSlot);
        } else {
            InvUtils.swap(lastSlot, false);
        }
        swapped = false;
    }

    private void swapOffhand() {
        if (mc.getConnection() != null) {
            mc.getConnection().send(new ServerboundPlayerActionPacket(
                    ServerboundPlayerActionPacket.Action.SWAP_ITEM_WITH_OFFHAND,
                    BlockPos.ZERO,
                    Direction.DOWN
            ));
        }
    }

}
