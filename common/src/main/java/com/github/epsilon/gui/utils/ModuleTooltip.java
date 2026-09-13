package com.github.epsilon.gui.utils;

import com.github.epsilon.graphics.text.StaticFontLoader;
import com.github.epsilon.gui.lib.UiTextMetrics;
import com.github.epsilon.gui.lib.UiTree;
import com.github.epsilon.gui.theme.MD3Theme;
import com.github.epsilon.modules.Module;

import java.util.ArrayList;
import java.util.List;

/**
 * 模块悬停描述提示。
 * <p>
 * 每帧由 GUI 宿主先调用 {@link #clear()}，模块行在鼠标悬停时调用 {@link #request(Module, float, float)}，
 * 帧末由宿主在最高层调用 {@link #render(UiTree.Scope, UiTextMetrics, float, float)} 绘制并自动清除本帧请求。
 * 描述文案来自模块 i18n owner 下的 {@code description} 键，未填写时不显示提示。
 */
public final class ModuleTooltip {

    private static final float TEXT_SCALE = 0.56f;
    private static final float MAX_WIDTH = 240.0f;
    private static final float PADDING_X = 8.0f;
    private static final float PADDING_Y = 6.0f;
    private static final float RADIUS = 8.0f;
    private static final float OFFSET_X = 12.0f;
    private static final float OFFSET_Y = 14.0f;
    private static final float LINE_EXTRA_GAP = 2.5f;
    private static final float SCREEN_MARGIN = 4.0f;

    private static Module hoveredModule;
    private static float hoverX;
    private static float hoverY;

    private ModuleTooltip() {
    }

    /** 清除上一帧残留的悬停请求；GUI 宿主应在每帧绘制开始前调用。 */
    public static void clear() {
        hoveredModule = null;
    }

    /** 模块行悬停时登记提示请求；坐标为 GUI 投影坐标。 */
    public static void request(Module module, float mouseX, float mouseY) {
        hoveredModule = module;
        hoverX = mouseX;
        hoverY = mouseY;
    }

    /**
     * 在当前 scope 中绘制悬停模块的描述提示卡。
     * 无悬停模块或模块没有填写描述时不绘制，并清空本帧请求。
     */
    public static void render(UiTree.Scope scope, UiTextMetrics metrics, float screenWidth, float screenHeight) {
        Module module = hoveredModule;
        hoveredModule = null;
        if (module == null || scope == null || metrics == null) {
            return;
        }
        String description = module.getDescription();
        if (description == null) {
            return;
        }

        List<String> lines = new ArrayList<>();
        for (String paragraph : description.split("\\R")) {
            wrapParagraph(lines, paragraph, metrics);
        }
        if (lines.isEmpty()) {
            return;
        }

        float lineHeight = metrics.textHeight(TEXT_SCALE, StaticFontLoader.defaultFont());
        float maxLineWidth = 0.0f;
        for (String line : lines) {
            maxLineWidth = Math.max(maxLineWidth, metrics.textWidth(line, TEXT_SCALE, StaticFontLoader.defaultFont()));
        }
        float width = PADDING_X * 2.0f + maxLineWidth;
        float height = PADDING_Y * 2.0f + lineHeight * lines.size() + LINE_EXTRA_GAP * (lines.size() - 1);

        float x = hoverX + OFFSET_X;
        float y = hoverY + OFFSET_Y;
        if (x + width > screenWidth - SCREEN_MARGIN) {
            x = hoverX - width - OFFSET_X;
        }
        if (y + height > screenHeight - SCREEN_MARGIN) {
            y = hoverY - height - OFFSET_Y;
        }
        x = Math.max(SCREEN_MARGIN, x);
        y = Math.max(SCREEN_MARGIN, y);

        scope.shadow(x, y, width, height, RADIUS, 10.0f,
                MD3Theme.withAlpha(MD3Theme.SHADOW, 110));
        scope.roundRect(x, y, width, height, RADIUS,
                MD3Theme.withAlpha(MD3Theme.SURFACE_CONTAINER_HIGHEST, 246));
        scope.outline(x, y, width, height, RADIUS, 1.0f,
                MD3Theme.withAlpha(MD3Theme.OUTLINE, 110));

        float textY = y + PADDING_Y;
        for (String line : lines) {
            scope.text(line, x + PADDING_X, textY, TEXT_SCALE, MD3Theme.TEXT_PRIMARY, StaticFontLoader.defaultFont());
            textY += lineHeight + LINE_EXTRA_GAP;
        }
    }

    /** 单词优先换行；遇到超长词或 CJK 连续文本时退化为按字符填充。 */
    private static void wrapParagraph(List<String> lines, String paragraph, UiTextMetrics metrics) {
        String trimmed = paragraph.trim();
        if (trimmed.isEmpty()) {
            return;
        }
        StringBuilder line = new StringBuilder();
        for (String word : trimmed.split(" ")) {
            if (word.isEmpty()) {
                continue;
            }
            String candidate = line.isEmpty() ? word : line + " " + word;
            if (metrics.textWidth(candidate, TEXT_SCALE, StaticFontLoader.defaultFont()) <= MAX_WIDTH) {
                line.setLength(0);
                line.append(candidate);
                continue;
            }
            if (!line.isEmpty()) {
                lines.add(line.toString());
                line.setLength(0);
            }
            if (metrics.textWidth(word, TEXT_SCALE, StaticFontLoader.defaultFont()) <= MAX_WIDTH) {
                line.append(word);
            } else {
                appendCharacterSplit(lines, word, metrics);
            }
        }
        if (!line.isEmpty()) {
            lines.add(line.toString());
        }
    }

    private static void appendCharacterSplit(List<String> lines, String word, UiTextMetrics metrics) {
        StringBuilder line = new StringBuilder();
        for (int index = 0; index < word.length(); index++) {
            char character = word.charAt(index);
            if (!line.isEmpty()) {
                String candidate = line.toString() + character;
                if (metrics.textWidth(candidate, TEXT_SCALE, StaticFontLoader.defaultFont()) > MAX_WIDTH) {
                    lines.add(line.toString());
                    line.setLength(0);
                }
            }
            line.append(character);
        }
        if (!line.isEmpty()) {
            lines.add(line.toString());
        }
    }

}
