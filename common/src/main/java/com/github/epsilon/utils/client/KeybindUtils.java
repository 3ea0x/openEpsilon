package com.github.epsilon.utils.client;

import com.github.epsilon.assets.i18n.EpsilonTranslations;
import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.KeyMapping;
import org.lwjgl.sdl.SDLMouse;

public class KeybindUtils {

    public static final int NONE = -1;
    public static final int MOUSE_OFFSET = -2;

    private KeybindUtils() {
    }

    /**
     * 判断编码后的绑定值是否表示鼠标按键。
     *
     * @param keyBind Epsilon 键位编码值
     * @return 判断结果
     */
    public static boolean isMouseButton(int keyBind) {
        return keyBind <= MOUSE_OFFSET;
    }

    /**
     * 将 SDL 鼠标按键编号编码为 Epsilon 键位值。
     *
     * @param button 点击按钮编号
     * @return 操作结果
     */
    public static int encodeMouseButton(int button) {
        return MOUSE_OFFSET - button;
    }

    /**
     * 从 Epsilon 键位值解码 SDL 鼠标按键编号。
     *
     * @param keyBind Epsilon 键位编码值
     * @return 操作结果
     */
    public static int decodeMouseButton(int keyBind) {
        return MOUSE_OFFSET - keyBind;
    }

    /**
     * 获取按键映射当前绑定的原始键值。
     *
     * @param keyMapping Minecraft 按键映射
     * @return 获取或计算得到的结果
     */
    public static int getKey(KeyMapping keyMapping) {
        return keyMapping.key.getValue();
    }

    /**
     * 判断指定按键绑定当前是否按下。
     *
     * @param keyMapping Minecraft 按键映射
     * @return 判断结果
     */
    public static boolean isPressed(KeyMapping keyMapping) {
        return isPressed(getKey(keyMapping));
    }

    /**
     * 判断指定按键绑定当前是否按下。
     *
     * @param keyBind Epsilon 键位编码值
     * @return 判断结果
     */
    public static boolean isPressed(int keyBind) {
        if (keyBind == NONE) {
            return false;
        }
        if (isMouseButton(keyBind)) {
            return isMouseButtonDown(decodeMouseButton(keyBind));
        }
        return InputConstants.isKeyDown(keyBind);
    }

    /**
     * 判断指定 SDL 鼠标按键当前是否按下。
     *
     * @param button SDL 鼠标按键编号，1 为左键
     * @return 判断结果
     */
    private static boolean isMouseButtonDown(int button) {
        if (button < InputConstants.MOUSE_BUTTON_LEFT || button > InputConstants.MOUSE_BUTTON_8) {
            return false;
        }
        int state = SDLMouse.SDL_GetMouseState(null, null);
        return (state & (1 << (button - 1))) != 0;
    }

    /**
     * 将键位值格式化为面向用户的名称。
     *
     * @param keyBind Epsilon 键位编码值
     * @return 操作结果
     */
    public static String format(int keyBind) {
        if (keyBind == NONE) {
            return EpsilonTranslations.Keybind.NONE.getTranslatedName();
        }
        if (isMouseButton(keyBind)) {
            return "Mouse " + (decodeMouseButton(keyBind) + 1);
        }
        return InputConstants.Type.KEYBOARD.getOrCreate(keyBind).getDisplayName().getString();
    }

}
