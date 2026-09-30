#version 410 core

uniform sampler2D InputSampler;

layout(std140) uniform BlurUniforms {
    vec3 InputInfo;       // x/y = 源分辨率，z = 模糊半径（像素）
    vec4 Rect;            // xy = 尺寸，zw = 左下角像素坐标
    vec4 CornerRadii;     // 左上 / 右上 / 右下 / 左下（像素）
    vec4 SegmentInfo;     // x = 分段数，y = 表面不透明度，zw 保留
    vec4 GlassParams;     // x = 边缘折射，y = 色散，z = 饱和度，w = 亮度
    vec4 GlassSpecular;   // x = 内阴影，y = 高光强度，z = 高光宽度（像素）
    vec4 SegmentRects[64];
    vec4 SegmentRadii[64];
};

layout(location = 0) out vec4 fragColor;

const float GOLDEN_ANGLE = 2.399963229728653;
// 圆盘螺旋采样的样本数：48 个样本配合高斯权重足以覆盖 GUI 的模糊半径，再乘以三通道色散采样。
const int TAP_COUNT = 48;
// 固定顶部光源方向（屏幕空间，y 轴向上），与 iOS 液态玻璃的固定受光面一致。
const vec2 LIGHT_DIRECTION = vec2(-0.45, 0.893);

/**
 * 圆盘螺旋模糊。
 * <p>
 * 样本按黄金角在采样圆盘内均匀铺开，再乘高斯权重；相比同心圆环不会产生环状条纹，
 * 也能用更少的采样覆盖更大的模糊半径。透明像素没有可用的背景色，用中心像素顶替，
 * 否则会把透明区的黑色拖进结果，让玻璃边缘发灰发脏。
 *
 * @param uv               目标片元的 UV
 * @param refractionOffset 边缘折射带来的采样偏移（像素）
 * @param radiusPixels     模糊半径（像素）
 */
vec3 sampleBackdrop(vec2 uv, vec2 refractionOffset, float radiusPixels) {
    vec2 texel = 1.0 / InputInfo.xy;
    vec2 centerUv = uv + refractionOffset * texel;
    vec2 radiusUv = radiusPixels * texel;

    vec3 centerColor = textureLod(InputSampler, centerUv, 0.0).rgb;
    vec3 colorSum = centerColor;
    float weightSum = 1.0;

    for (int i = 0; i < TAP_COUNT; i++) {
        float sampleIndex = float(i) + 0.5;
        float radius = sqrt(sampleIndex / float(TAP_COUNT));
        float angle = sampleIndex * GOLDEN_ANGLE;
        float weight = exp(-radius * radius * 2.6);
        vec2 offset = vec2(cos(angle), sin(angle)) * (radius * radiusUv);
        vec4 tap = textureLod(InputSampler, centerUv + offset, 0.0);
        float signal = max(tap.a, max(tap.r, max(tap.g, tap.b)));
        colorSum += (signal > 1e-5 ? tap.rgb : centerColor) * weight;
        weightSum += weight;
    }

    return colorSum / weightSum;
}

void main() {
    vec2 uSize = Rect.xy;
    vec2 uLocation = Rect.zw;
    vec4 radii = CornerRadii;
    vec4 bounds = vec4(uLocation, uLocation + uSize);

    int segmentCount = int(SegmentInfo.x);
    int shapeCount = max(segmentCount, 1);
    float alpha = 0.0;

    // 追踪最近形状的有符号距离与局部坐标：并集的近似场用来求边缘法线、折射与高光。
    float field = 1e9;
    vec2 fieldLocal = vec2(0.0);
    vec2 fieldHalfSize = vec2(1.0);
    float fieldRadius = 0.0;

    for (int i = 0; i < 64; i++) {
        if (i >= shapeCount) break;

        vec4 shapeBounds = bounds;
        vec4 shapeRadii = radii;
        if (segmentCount > 0) {
            vec4 rect = SegmentRects[i];
            shapeBounds = vec4(rect.xy, rect.xy + rect.zw);
            shapeRadii = vec4(max(0.0, SegmentRadii[i].x));
        }

        vec2 halfSize = (shapeBounds.zw - shapeBounds.xy) * 0.5;
        vec2 center = (shapeBounds.xy + shapeBounds.zw) * 0.5;
        vec2 position = gl_FragCoord.xy - center;
        vec2 quadrant = step(0.0, position);
        float radius = mix(mix(shapeRadii.x, shapeRadii.w, quadrant.y), mix(shapeRadii.y, shapeRadii.z, quadrant.y), quadrant.x);
        vec2 distanceToEdge = abs(position) - halfSize + radius;
        float distance = length(max(distanceToEdge, 0.0)) + min(max(distanceToEdge.x, distanceToEdge.y), 0.0) - radius;
        float delta = max(fwidth(distance), 1e-4);
        alpha = min(1.0, alpha + 1.0 - smoothstep(-delta, delta, distance));

        if (distance < field) {
            field = distance;
            fieldLocal = position;
            fieldHalfSize = halfSize;
            fieldRadius = max(radius, 0.0);
        }
    }

    // SegmentInfo.y = 模糊层的表面不透明度，由 BlurShader 传入：GUI 侧来自 Background Opacity，
    // HUD / 世界侧恒为 1.0。整块模糊斑就是面板背景本身，必须随该设置一起淡出，
    // 否则把背景调透明后会残留不透明模糊斑。完全不可见时直接丢弃，省掉全部采样。
    float surfaceAlpha = alpha * SegmentInfo.y;
    if (surfaceAlpha < 0.001) discard;

    float inside = max(-field, 0.0);
    float specularWidth = max(1.0, GlassSpecular.z);
    float softWidth = max(2.0, specularWidth);

    // 折射只发生在靠近边缘的一圈内，向内迅速衰减，形成液态玻璃的透镜感。
    float lens = 1.0 - smoothstep(0.0, softWidth * 1.5, inside);
    lens = lens * lens;
    // 柔光环：向内扩散的受光面。
    float rim = 1.0 - smoothstep(0.0, softWidth * 2.5, inside);
    // 细边线：紧贴边界的一圈高光。
    float edge = 1.0 - smoothstep(0.0, 1.5, inside);

    // 有符号距离场的梯度即外法线：圆角区沿角平分线，直边区沿主轴。
    vec2 q = abs(fieldLocal) - fieldHalfSize + fieldRadius;
    vec2 normal;
    if (q.x > 0.0 && q.y > 0.0) {
        normal = normalize(q * sign(fieldLocal));
    } else if (q.x > q.y) {
        normal = vec2(sign(fieldLocal.x), 0.0);
    } else {
        normal = vec2(0.0, sign(fieldLocal.y));
    }

    float facing = clamp(dot(normal, LIGHT_DIRECTION), 0.0, 1.0);
    float backLight = clamp(-dot(normal, LIGHT_DIRECTION), 0.0, 1.0);

    float refraction = GlassParams.x;
    float dispersion = GlassParams.y;
    // 负号表示朝形状内部取样，于是边缘呈现“放大”的透镜效果。
    vec2 refractionOffset = -normal * (lens * refraction);

    vec2 uv = gl_FragCoord.xy / InputInfo.xy;
    vec3 color;
    if (refraction > 0.001 && dispersion > 0.001) {
        // 三个通道使用不同折射强度，模拟玻璃边缘的色散（蓝光偏折最多）。
        vec3 red = sampleBackdrop(uv, refractionOffset * (1.0 - dispersion), InputInfo.z);
        vec3 green = sampleBackdrop(uv, refractionOffset, InputInfo.z);
        vec3 blue = sampleBackdrop(uv, refractionOffset * (1.0 + dispersion), InputInfo.z);
        color = vec3(red.r, green.g, blue.b);
    } else {
        color = sampleBackdrop(uv, refractionOffset, InputInfo.z);
    }

    // 颜色分级：iOS 玻璃会增强背景饱和度并轻微提亮。
    float luma = dot(color, vec3(0.2126, 0.7152, 0.0722));
    color = mix(vec3(luma), color, GlassParams.z) * GlassParams.w;

    // 背光侧的内阴影：给玻璃一点厚度。
    color *= mix(1.0, 1.0 - GlassSpecular.x, rim * backLight);

    // 受光侧的高光：贴边细线 + 向内扩散的柔光，方向由法线与固定光源决定。
    float specular = GlassSpecular.y;
    color += vec3(specular) * (edge * (0.20 + 0.80 * facing) * 1.25 + rim * rim * facing * facing * 0.5);

    fragColor = vec4(clamp(color, 0.0, 1.0), surfaceAlpha);
}
