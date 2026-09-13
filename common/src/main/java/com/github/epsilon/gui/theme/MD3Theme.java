package com.github.epsilon.gui.theme;

import com.github.epsilon.graphics.shaders.BlurShader;
import com.github.epsilon.gui.lib.UiTree;
import com.github.epsilon.modules.impl.ClientSetting;
import net.minecraft.util.Mth;

import java.awt.*;

public class MD3Theme {

    public static Color SHADOW = new Color(0, 0, 0, 96);

    public static Color SURFACE = new Color(20, 18, 24, 238);
    public static Color SURFACE_DIM = new Color(15, 13, 19, 232);
    public static Color SURFACE_CONTAINER_LOW = new Color(29, 27, 32, 240);
    public static Color SURFACE_CONTAINER = new Color(33, 31, 38, 244);
    public static Color SURFACE_CONTAINER_HIGH = new Color(43, 41, 48, 248);
    public static Color SURFACE_CONTAINER_HIGHEST = new Color(54, 52, 59, 252);

    public static Color OUTLINE = new Color(147, 143, 153, 180);
    public static Color OUTLINE_SOFT = new Color(147, 143, 153, 96);

    public static Color PRIMARY = new Color(208, 188, 255);
    public static Color ON_PRIMARY = new Color(56, 30, 114);
    public static Color PRIMARY_CONTAINER = new Color(79, 55, 139, 236);
    public static Color ON_PRIMARY_CONTAINER = new Color(234, 221, 255);

    public static Color SECONDARY = new Color(204, 194, 220);
    public static Color ON_SECONDARY = new Color(51, 45, 65);
    public static Color SECONDARY_CONTAINER = new Color(74, 68, 88, 236);
    public static Color ON_SECONDARY_CONTAINER = new Color(232, 222, 248);

    public static Color TERTIARY = new Color(239, 184, 200);
    public static Color ON_TERTIARY = new Color(73, 37, 50);
    public static Color TERTIARY_CONTAINER = new Color(99, 59, 72, 236);
    public static Color ON_TERTIARY_CONTAINER = new Color(255, 216, 228);
    public static Color INVERSE_SURFACE = new Color(230, 224, 233);
    public static Color INVERSE_ON_SURFACE = new Color(49, 48, 51);

    public static Color TEXT_PRIMARY = new Color(230, 224, 233);
    public static Color TEXT_SECONDARY = new Color(202, 196, 208);
    public static Color TEXT_MUTED = new Color(147, 143, 153);
    public static Color SUCCESS = new Color(204, 194, 220);
    public static Color ERROR = new Color(242, 184, 181);

    private static ClientSetting.ThemePreset appliedPreset = null;
    private static ClientSetting.ThemeMode appliedMode = null;

    public static final int PANEL_RADIUS = 17;
    public static final int SECTION_RADIUS = 13;
    public static final int CARD_RADIUS = 9;
    public static final int CHIP_RADIUS = 999;
    public static final float PANEL_SHADOW_BLUR = 24.0f;
    public static final int PANEL_SHADOW_ALPHA = 96;
    public static final float POPUP_SHADOW_BLUR = 14.0f;
    public static final int POPUP_SHADOW_ALPHA = 112;
    public static final float FLOATING_LABEL_SHADOW_BLUR = 12.0f;
    public static final int FLOATING_LABEL_SHADOW_ALPHA = 96;

    public static final float OUTER_PADDING = 5.0f;
    public static final float SECTION_GAP = 3.0f;
    public static final float INNER_PADDING = 5.0f;
    public static final float ROW_GAP = 3.0f;
    public static final float PANEL_TITLE_INSET = 6.0f;
    public static final float PANEL_VIEWPORT_INSET = 3.0f;
    public static final float ROW_CONTENT_INSET = 5.0f;
    public static final float ROW_TRAILING_INSET = 5.0f;
    public static final float RAIL_COLLAPSED_WIDTH = 42.0f;
    public static final float RAIL_EXPANDED_WIDTH = 120.0f;
    public static final float CONTROL_HEIGHT = 18.0f;
    public static final float CONTROL_RADIUS = 7.0f;
    public static final float COMPACT_CHIP_HEIGHT = 16.0f;
    public static final float SWITCH_WIDTH = 26.0f;
    public static final float SWITCH_HEIGHT = 16.0f;
    public static final float SWITCH_HANDLE_SIZE_OFF = 8.0f;
    public static final float SWITCH_HANDLE_SIZE_ON = 12.0f;
    public static final float SWITCH_HANDLE_INSET_OFF = 4.0f;
    public static final float SWITCH_HANDLE_INSET_ON = 2.0f;
    public static final float SWITCH_STATE_LAYER_SIZE = 20.0f;

    private MD3Theme() {
    }

    public static void syncFromSettings() {
        ClientSetting.ThemePreset preset = ClientSetting.INSTANCE.themePreset.getValue();
        ClientSetting.ThemeMode mode = ClientSetting.INSTANCE.themeMode.getValue();
        if (preset == appliedPreset && mode == appliedMode) {
            return;
        }
        ThemePalette palette = ThemePalette.forPreset(preset, mode);
        SHADOW = palette.shadow();
        SURFACE = palette.surface();
        SURFACE_DIM = palette.surfaceDim();
        SURFACE_CONTAINER_LOW = palette.surfaceContainerLow();
        SURFACE_CONTAINER = palette.surfaceContainer();
        SURFACE_CONTAINER_HIGH = palette.surfaceContainerHigh();
        SURFACE_CONTAINER_HIGHEST = palette.surfaceContainerHighest();
        OUTLINE = palette.outline();
        OUTLINE_SOFT = withAlpha(palette.outline(), 96);
        PRIMARY = palette.primary();
        ON_PRIMARY = palette.onPrimary();
        PRIMARY_CONTAINER = palette.primaryContainer();
        ON_PRIMARY_CONTAINER = palette.onPrimaryContainer();
        SECONDARY = palette.secondary();
        ON_SECONDARY = palette.onSecondary();
        SECONDARY_CONTAINER = palette.secondaryContainer();
        ON_SECONDARY_CONTAINER = palette.onSecondaryContainer();
        TERTIARY = palette.tertiary();
        ON_TERTIARY = palette.onTertiary();
        TERTIARY_CONTAINER = palette.tertiaryContainer();
        ON_TERTIARY_CONTAINER = palette.onTertiaryContainer();
        INVERSE_SURFACE = palette.inverseSurface();
        INVERSE_ON_SURFACE = palette.inverseOnSurface();
        TEXT_PRIMARY = palette.textPrimary();
        TEXT_SECONDARY = palette.textSecondary();
        TEXT_MUTED = palette.textMuted();
        SUCCESS = palette.secondary();
        ERROR = palette.error();
        appliedPreset = preset;
        appliedMode = mode;
    }

    public static Color withAlpha(Color color, int alpha) {
        int clampedAlpha = Mth.clamp(alpha, 0, 255);
        return new Color(color.getRed(), color.getGreen(), color.getBlue(), clampedAlpha);
    }

    // ---------- 液态玻璃材质 ----------
    // 玻璃表面在绘制前先对同一区域执行实时背景模糊（submitGlassBlur），再叠上玻璃着色与边缘高光。
    // 由 Client Setting 的 “Liquid Glass” 开关独立控制，与 Theme Mode / Theme Preset 互不绑定。
    public static final float GLASS_BLUR_STRENGTH = 12.0f;

    private static final int GLASS_PANE_ALPHA_DARK = 138;
    private static final int GLASS_PANE_ALPHA_LIGHT = 162;
    private static final int GLASS_SECTION_ALPHA_DARK = 120;
    private static final int GLASS_SECTION_ALPHA_LIGHT = 148;
    private static final int GLASS_POPUP_ALPHA_DARK = 168;
    private static final int GLASS_POPUP_ALPHA_LIGHT = 180;
    private static final int GLASS_ROW_ALPHA_DARK = 118;
    private static final int GLASS_ROW_ALPHA_LIGHT = 146;
    private static final float GLASS_LIGHT_BLEND = 0.35f;
    private static final float GLASS_DARK_LIFT = 0.10f;

    /** 液态玻璃是否启用：独立开关，不影响 Theme Mode / Theme Preset 配色。 */
    public static boolean isGlassEnabled() {
        return ClientSetting.INSTANCE.themeGlass.getValue();
    }

    /** 液态玻璃整体不透明度倍率（0.0~1.0），来自 Client Setting 的 Glass Opacity；缺省为 1.0，即保持各玻璃表面的原始 alpha。 */
    public static float glassOpacity() {
        Double value = ClientSetting.INSTANCE.themeGlassOpacity.getValue();
        return value == null ? 1.0f : Mth.clamp(value.floatValue(), 0.0f, 1.0f);
    }

    /**
     * 按 Glass Opacity 缩放 alpha。
     * <p>
     * 只用于「因为启用玻璃而降低不透明度」的颜色，例如玻璃表面、玻璃行的高亮叠加和玻璃边缘高光；
     * 普通不透明表面（如关闭玻璃后的 {@link #glassPopup}）不得经过本方法。
     */
    public static int glassAlpha(int alpha) {
        return Mth.clamp(Math.round(alpha * glassOpacity()), 0, 255);
    }

    // ---------- GUI 窗口背景 ----------
    // 与玻璃材质正交的第二个倍率：玻璃决定“背景是什么材质”，本倍率决定“背景有多不透明”。
    // 因此它不依赖 Liquid Glass 开关，关闭玻璃后仍可调节；两者相乘只影响窗口背景层。

    /** GUI 窗口背景整体不透明度倍率（0.0~1.0），来自 Client Setting 的 Background Opacity；缺省为 1.0。 */
    public static float backgroundOpacity() {
        Double value = ClientSetting.INSTANCE.guiBackgroundOpacity.getValue();
        return value == null ? 1.0f : Mth.clamp(value.floatValue(), 0.0f, 1.0f);
    }

    /**
     * 按 GUI 背景不透明度缩放 alpha。
     * <p>
     * 只用于窗口背景层，即 Panel 模式的主面板与分区卡片、Dropdown 模式的每个面板；
     * 面板之上的内容层（行、分组卡片、文本、控件、弹窗）不得经过本方法，否则会连带削弱可读性。
     */
    public static int backgroundAlpha(int alpha) {
        return Mth.clamp(Math.round(alpha * backgroundOpacity()), 0, 255);
    }

    /**
     * 对已经算好的窗口背景表面按 Background Opacity 缩放 alpha。
     * <p>
     * 调用方负责先决定材质（{@link #glassPane} / {@link #glassSection}），本方法只压缩不透明度，
     * 这样关闭玻璃时被原样返回的不透明表面同样能被调透明。
     */
    public static Color applyBackgroundOpacity(Color surface) {
        return withAlpha(surface, backgroundAlpha(surface.getAlpha()));
    }

    /** 玻璃着色：保留主题色相，按当前 Theme Mode 降低不透明度，再按 Glass Opacity 统一缩放；Light 模式向白色微调提亮，Dark 模式轻微提亮避免发闷。 */
    public static Color glassTint(Color base, int darkAlpha, int lightAlpha) {
        if (!isGlassEnabled()) {
            return base;
        }
        int alpha = isLightTheme() ? lightAlpha : darkAlpha;
        Color tinted = isLightTheme()
                ? lerp(base, Color.WHITE, GLASS_LIGHT_BLEND)
                : lerp(base, Color.WHITE, GLASS_DARK_LIFT);
        return withAlpha(tinted, glassAlpha(alpha));
    }

    public static Color glassPane(Color base) {
        return glassTint(base, GLASS_PANE_ALPHA_DARK, GLASS_PANE_ALPHA_LIGHT);
    }

    public static Color glassSection(Color base) {
        return glassTint(base, GLASS_SECTION_ALPHA_DARK, GLASS_SECTION_ALPHA_LIGHT);
    }

    public static Color glassPopup(Color base) {
        if (!isGlassEnabled()) {
            // 弹窗原本强制全不透明表面，关闭玻璃后保持原样
            return withAlpha(base, 255);
        }
        return glassTint(base, GLASS_POPUP_ALPHA_DARK, GLASS_POPUP_ALPHA_LIGHT);
    }

    public static Color glassRow(Color base) {
        return glassTint(base, GLASS_ROW_ALPHA_DARK, GLASS_ROW_ALPHA_LIGHT);
    }

    /**
     * 对圆角区域执行实时背景模糊。
     * <p>
     * {@link BlurShader} 会立即把当前帧目标（面板/下拉菜单的离屏 target 优先，否则主 target）的颜色拷入临时纹理并模糊后回写，
     * 因此必须在同一帧中先调用本方法、再记录玻璃表面的绘制命令，否则模糊会覆盖已经画好的 UI。
     */
    public static void submitGlassBlur(float x, float y, float width, float height, float radius) {
        if (!isGlassEnabled() || width <= 0.0f || height <= 0.0f) {
            return;
        }
        // 这一层模糊就是面板“背景”本身：它写入的是一块 alpha≈1 的模糊斑，不随玻璃或背景设置变化。
        // 因此必须把 Background Opacity 传给它，否则把背景调透明后仍会残留不透明模糊斑
        // （表现为背景发黑、发虚），Glass Opacity 也压不住它。完全透明时直接跳过，省掉一次全屏模糊。
        float opacity = backgroundOpacity();
        if (opacity <= 0.0f) {
            return;
        }
        BlurShader.INSTANCE.render(x, y, width, height, radius, GLASS_BLUR_STRENGTH, opacity);
    }

    /**
     * 面板装饰（玻璃边缘描边与顶部高光）的 alpha：同时受 Glass Opacity 与 Background Opacity 控制。
     * <p>
     * 它们与面板背景同属一块表面，背景调透明时必须一起消失，否则会留下悬空的边框和一条白色高光线。
     */
    private static int glassRimAlpha(int alpha) {
        return glassAlpha(backgroundAlpha(alpha));
    }

    /** 玻璃边缘：一圈细描边 + 顶部受光的高光线；随 Glass Opacity 与 Background Opacity 一起缩放。 */
    public static void glassRim(UiTree.Scope scope, float x, float y, float width, float height, float radius) {
        if (!isGlassEnabled() || scope == null || width <= 0.0f || height <= 0.0f) {
            return;
        }
        boolean light = isLightTheme();
        Color edge = light ? withAlpha(OUTLINE, glassRimAlpha(110)) : withAlpha(Color.WHITE, glassRimAlpha(32));
        int glintAlpha = glassRimAlpha(light ? 96 : 120);
        if (edge.getAlpha() <= 0 && glintAlpha <= 0) {
            return;
        }
        scope.outline(x, y, width, height, radius, 1.0f, edge);
        float inset = Math.min(Math.max(radius * 0.55f, 3.0f), 12.0f);
        Color glint = withAlpha(Color.WHITE, glintAlpha);
        scope.rect(x + inset, y + 1.1f, width - inset * 2.0f, 1.1f, glint);
    }

    public static Color lerp(Color start, Color end, float delta) {
        float t = Mth.clamp(delta, 0.0f, 1.0f);
        int r = (int) (start.getRed() + (end.getRed() - start.getRed()) * t);
        int g = (int) (start.getGreen() + (end.getGreen() - start.getGreen()) * t);
        int b = (int) (start.getBlue() + (end.getBlue() - start.getBlue()) * t);
        int a = (int) (start.getAlpha() + (end.getAlpha() - start.getAlpha()) * t);
        return new Color(r, g, b, a);
    }

    public static boolean isLightTheme() {
        return ClientSetting.INSTANCE.themeMode.is(ClientSetting.ThemeMode.Light);
    }

    public static Color stateLayer(Color color, float progress, int maxAlpha) {
        return withAlpha(color, (int) (Mth.clamp(progress, 0.0f, 1.0f) * Mth.clamp(maxAlpha, 0, 255)));
    }

    /**
     * 行/卡片背景：Panel 模式的模块行与全部 Setting 行、下拉模式的列表项都走这里。
     * <p>
     * 它和面板背景一样属于「背景块」，因此最后统一按 Background Opacity 缩放；
     * 关闭玻璃的分支原本返回不透明表面，也必须缩放，否则调低背景透明度时这些大块仍然不透明。
     */
    public static Color rowSurface(float hoverProgress) {
        Color surface;
        if (!isGlassEnabled()) {
            surface = lerp(SURFACE_CONTAINER, SURFACE_CONTAINER_HIGH, hoverProgress);
        } else {
            Color glass = glassRow(SURFACE_CONTAINER);
            Color hovered = withAlpha(SURFACE_CONTAINER_HIGHEST, glassAlpha(isLightTheme() ? 236 : 226));
            surface = lerp(glass, hovered, hoverProgress);
        }
        return applyBackgroundOpacity(surface);
    }

    public static Color filledFieldSurface(boolean focused, float hoverProgress) {
        if (focused) {
            float focusMix = isLightTheme() ? 0.58f : 0.42f;
            return lerp(SURFACE_CONTAINER_HIGH, PRIMARY_CONTAINER, focusMix);
        }
        Color base = isLightTheme() ? SURFACE_CONTAINER : SURFACE_CONTAINER_LOW;
        return lerp(base, SURFACE_CONTAINER_HIGHEST, Mth.clamp(hoverProgress * 0.85f, 0.0f, 1.0f));
    }

    public static Color filledFieldContent(boolean focused) {
        return TEXT_PRIMARY;
    }

    public static Color filledFieldCaret(boolean focused) {
        return focused ? PRIMARY : TEXT_PRIMARY;
    }

    public static Color filledFieldIndicator(boolean focused, float hoverProgress) {
        if (focused) {
            return PRIMARY;
        }
        return lerp(withAlpha(OUTLINE, 96), withAlpha(TEXT_PRIMARY, 136), Mth.clamp(hoverProgress * 0.55f, 0.0f, 1.0f));
    }

    public static Color segmentedControlSurface() {
        return isLightTheme() ? SURFACE : SURFACE_CONTAINER_HIGH;
    }

    public static Color segmentedControlIndicator() {
        return SECONDARY_CONTAINER;
    }

    public static Color segmentedControlActiveLabel() {
        return ON_SECONDARY_CONTAINER;
    }

    public static Color segmentedControlInactiveLabel() {
        return isLightTheme() ? TEXT_SECONDARY : TEXT_MUTED;
    }

    public static Color switchTrack(float toggleProgress) {
        return lerp(SURFACE_CONTAINER_HIGHEST, PRIMARY, toggleProgress);
    }

    public static Color switchKnob(float toggleProgress) {
        return lerp(OUTLINE, ON_PRIMARY, toggleProgress);
    }

    public static Color switchTrackOutline(float toggleProgress, float hoverProgress) {
        float inactive = 1.0f - Mth.clamp(toggleProgress, 0.0f, 1.0f);
        float hoverMix = Mth.clamp(hoverProgress * 0.35f, 0.0f, 1.0f);
        Color base = lerp(OUTLINE, TEXT_PRIMARY, hoverMix);
        return withAlpha(base, (int) (inactive * (isLightTheme() ? 188 : 168)));
    }

    public static float switchTrackOutlineWidth(float toggleProgress) {
        return 1.0f + (1.0f - Mth.clamp(toggleProgress, 0.0f, 1.0f)) * 0.1f;
    }

    private record ThemePalette(
            Color shadow,
            Color surface,
            Color surfaceDim,
            Color surfaceContainerLow,
            Color surfaceContainer,
            Color surfaceContainerHigh,
            Color surfaceContainerHighest,
            Color outline,
            Color primary,
            Color onPrimary,
            Color primaryContainer,
            Color onPrimaryContainer,
            Color secondary,
            Color onSecondary,
            Color secondaryContainer,
            Color onSecondaryContainer,
            Color tertiary,
            Color onTertiary,
            Color tertiaryContainer,
            Color onTertiaryContainer,
            Color inverseSurface,
            Color inverseOnSurface,
            Color textPrimary,
            Color textSecondary,
            Color textMuted,
            Color error
    ) {
        private static ThemePalette forPreset(ClientSetting.ThemePreset preset, ClientSetting.ThemeMode mode) {
            return switch (preset) {
                case TonalSpot -> mode == ClientSetting.ThemeMode.Dark
                        ? paletteDark("#141218", "#1B1820", "#211F26", "#2B2930", "#35333B", "#D0BCFF", "#381E72", "#4F378B", "#EADDFF", "#CCC2DC", "#332D41", "#4A4458", "#E8DEF8", "#EFB8C8", "#492532", "#633B48", "#FFD8E4")
                        : paletteLight("#FFFBFE", "#F7F2FA", "#F3EDF7", "#ECE6F0", "#E6E0E9", "#6750A4", "#FFFFFF", "#EADDFF", "#21005D", "#625B71", "#FFFFFF", "#E8DEF8", "#1D192B", "#7D5260", "#FFFFFF", "#FFD8E4", "#31111D");
                case Neutral -> mode == ClientSetting.ThemeMode.Dark
                        ? paletteDark("#141314", "#1C1B1C", "#211F21", "#2C2A2C", "#363436", "#CFC3D9", "#362B3E", "#4B4153", "#E9DDEC", "#CCC2CF", "#342F38", "#4B4450", "#E8DDEB", "#D8C2C7", "#3C2B2F", "#544247", "#F4DCE1")
                        : paletteLight("#FEF7FF", "#F7EEF8", "#F1E8F2", "#EBE1EB", "#E4DBE5", "#6C4F75", "#FFFFFF", "#F2DAFF", "#261430", "#665A69", "#FFFFFF", "#EBDDDF", "#201A21", "#81525D", "#FFFFFF", "#FFD9E0", "#33111A");
                case Vibrant -> mode == ClientSetting.ThemeMode.Dark
                        ? paletteDark("#16111C", "#1D1725", "#241D2D", "#2F2639", "#3A3044", "#E3B7FF", "#4A1F63", "#663282", "#F5D9FF", "#D7BEE4", "#3D2D48", "#564260", "#F2DAFF", "#FFB4AB", "#690005", "#93000A", "#FFDAD6")
                        : paletteLight("#FFF7FD", "#F8EDF8", "#F3E7F4", "#EDE0EE", "#E6D9E7", "#7A2F9A", "#FFFFFF", "#FFD7F6", "#320046", "#6A586F", "#FFFFFF", "#EEDCF4", "#231727", "#80535E", "#FFFFFF", "#FFD9E0", "#33111A");
                case Expressive -> mode == ClientSetting.ThemeMode.Dark
                        ? paletteDark("#14141B", "#1C1C24", "#22222A", "#2D2D36", "#373740", "#FFB1C8", "#561D33", "#73324B", "#FFD9E2", "#D9C2CB", "#3F2A33", "#574049", "#F4DDE6", "#C4D7FF", "#1E3A6B", "#35528A", "#DBE1FF")
                        : paletteLight("#FFF8F8", "#F8EFEF", "#F3E8E8", "#EDE1E1", "#E7DADB", "#904A61", "#FFFFFF", "#FFD9E2", "#3B071D", "#6F5862", "#FFFFFF", "#F7D9E3", "#29141D", "#48648F", "#FFFFFF", "#DBE1FF", "#001D36");
                case Fidelity -> mode == ClientSetting.ThemeMode.Dark
                        ? paletteDark("#10141A", "#161B22", "#1C2128", "#252B33", "#2E353E", "#7CC6FF", "#00344F", "#0E4A69", "#CEE5FF", "#B8CADB", "#203845", "#374E5B", "#D4E5F8", "#9AD0B8", "#103826", "#28503B", "#B6EFD0")
                        : paletteLight("#F6FAFF", "#EEF3F9", "#E8EDF3", "#E1E8EF", "#DAE2EA", "#00658A", "#FFFFFF", "#CDEFFD", "#001E2C", "#50606E", "#FFFFFF", "#D3E5F5", "#0C1D28", "#3C6651", "#FFFFFF", "#BEECD1", "#072012");
                case Content -> mode == ClientSetting.ThemeMode.Dark
                        ? paletteDark("#11151A", "#181D23", "#1E232A", "#282E36", "#313841", "#91C9FF", "#11314B", "#294964", "#D1E5FF", "#C1C9D6", "#28333D", "#3F4B56", "#DEE4F2", "#D7C29F", "#41311A", "#59472E", "#F5DEB8")
                        : paletteLight("#F8FAFC", "#F0F3F7", "#E9EDF2", "#E2E7EC", "#DBE1E7", "#355F8D", "#FFFFFF", "#D1E4FF", "#001D36", "#5A616C", "#FFFFFF", "#DEE3F2", "#171C25", "#735B3E", "#FFFFFF", "#F5DEB8", "#291805");
                case Rainbow -> mode == ClientSetting.ThemeMode.Dark
                        ? paletteDark("#141318", "#1B1A20", "#211F26", "#2B2831", "#35323B", "#C8C1FF", "#2E2A67", "#454287", "#E4DFFF", "#D7C2E6", "#3A3047", "#53485F", "#F2DBFF", "#FFB59D", "#5E2F1C", "#7D4732", "#FFDCCF")
                        : paletteLight("#FCF8FF", "#F4EEF8", "#EEE8F3", "#E7E1EC", "#E0DAE6", "#5B5BD6", "#FFFFFF", "#E0DFFF", "#191962", "#675A70", "#FFFFFF", "#ECDCF5", "#21182A", "#8A4F3A", "#FFFFFF", "#FFDCCF", "#351100");
                case FruitSalad -> mode == ClientSetting.ThemeMode.Dark
                        ? paletteDark("#121415", "#181B1C", "#1E2122", "#282C2D", "#313637", "#8FD665", "#173807", "#2D5218", "#C2F19A", "#C8D0C0", "#2F372B", "#475041", "#E4F0D9", "#FFB77B", "#5A2E00", "#7D4300", "#FFDCC2")
                        : paletteLight("#FAFFF4", "#F2F8EB", "#EAF0E3", "#E3EAD9", "#DCE3D2", "#466A1F", "#FFFFFF", "#C7F089", "#102000", "#5E6657", "#FFFFFF", "#E0E9D5", "#1B1F17", "#965100", "#FFFFFF", "#FFDCC2", "#301400");
                case Monochrome -> mode == ClientSetting.ThemeMode.Dark
                        ? paletteDark("#121212", "#1A1A1A", "#202020", "#2A2A2A", "#333333", "#E6E1E5", "#1B1B1B", "#383838", "#F3EEF2", "#D1CCD0", "#2C2C2C", "#434343", "#EFE9ED", "#CFC8CD", "#2B2B2B", "#444444", "#F0E9EE")
                        : paletteLight("#FCFCFC", "#F3F3F3", "#ECECEC", "#E5E5E5", "#DEDEDE", "#5F5E61", "#FFFFFF", "#E4E1E4", "#1C1B1E", "#605D62", "#FFFFFF", "#E5E1E6", "#1C1B1F", "#625D61", "#FFFFFF", "#E7E0E5", "#201A1E");
            };
        }

        private static ThemePalette paletteDark(String surface, String low, String container, String high, String highest,
                                                String primary, String onPrimary, String primaryContainer, String onPrimaryContainer,
                                                String secondary, String onSecondary, String secondaryContainer, String onSecondaryContainer,
                                                String tertiary, String onTertiary, String tertiaryContainer, String onTertiaryContainer) {
            Color surfaceColor = color(surface, 238);
            Color surfaceDimColor = color(low, 232);
            Color lowColor = color(low, 240);
            Color containerColor = color(container, 244);
            Color highColor = color(high, 248);
            Color highestColor = color(highest, 252);
            Color outlineColor = color("#938F99", 180);
            Color textPrimaryColor = color("#ECE6F0", 255);
            Color textSecondaryColor = color("#CAC4D0", 255);
            Color textMutedColor = color("#938F99", 255);
            return new ThemePalette(
                    new Color(0, 0, 0, 96),
                    surfaceColor,
                    surfaceDimColor,
                    lowColor,
                    containerColor,
                    highColor,
                    highestColor,
                    outlineColor,
                    color(primary, 255),
                    color(onPrimary, 255),
                    color(primaryContainer, 236),
                    color(onPrimaryContainer, 255),
                    color(secondary, 255),
                    color(onSecondary, 255),
                    color(secondaryContainer, 236),
                    color(onSecondaryContainer, 255),
                    color(tertiary, 255),
                    color(onTertiary, 255),
                    color(tertiaryContainer, 236),
                    color(onTertiaryContainer, 255),
                    color("#E6E0E9", 255),
                    color("#313033", 255),
                    textPrimaryColor,
                    textSecondaryColor,
                    textMutedColor,
                    color("#F2B8B5", 255)
            );
        }

        private static ThemePalette paletteLight(String surface, String low, String container, String high, String highest,
                                                 String primary, String onPrimary, String primaryContainer, String onPrimaryContainer,
                                                 String secondary, String onSecondary, String secondaryContainer, String onSecondaryContainer,
                                                 String tertiary, String onTertiary, String tertiaryContainer, String onTertiaryContainer) {
            Color surfaceColor = color(surface, 242);
            Color surfaceDimColor = color(low, 238);
            Color lowColor = color(low, 242);
            Color containerColor = color(container, 246);
            Color highColor = color(high, 250);
            Color highestColor = color(highest, 252);
            Color outlineColor = color("#79747E", 180);
            Color textPrimaryColor = color("#1C1B1F", 255);
            Color textSecondaryColor = color("#49454F", 255);
            Color textMutedColor = color("#79747E", 255);
            return new ThemePalette(
                    new Color(0, 0, 0, 80),
                    surfaceColor,
                    surfaceDimColor,
                    lowColor,
                    containerColor,
                    highColor,
                    highestColor,
                    outlineColor,
                    color(primary, 255),
                    color(onPrimary, 255),
                    color(primaryContainer, 236),
                    color(onPrimaryContainer, 255),
                    color(secondary, 255),
                    color(onSecondary, 255),
                    color(secondaryContainer, 236),
                    color(onSecondaryContainer, 255),
                    color(tertiary, 255),
                    color(onTertiary, 255),
                    color(tertiaryContainer, 236),
                    color(onTertiaryContainer, 255),
                    color("#313033", 255),
                    color("#F4EFF4", 255),
                    textPrimaryColor,
                    textSecondaryColor,
                    textMutedColor,
                    color("#BA1A1A", 255)
            );
        }

        private static Color color(String hex, int alpha) {
            Color base = Color.decode(hex);
            return new Color(base.getRed(), base.getGreen(), base.getBlue(), alpha);
        }
    }

}
