#version 150

#moj_import <mre:common.glsl>

in vec2 FragCoord;
in vec2 TexCoord;
in vec4 FragColor;

uniform sampler2D Sampler0;
uniform vec2 Size;
uniform vec4 Radius;
uniform float Smoothness;
uniform float CornerSmoothness;
uniform float GlobalAlpha;

uniform float FresnelPower;
uniform vec3 FresnelColor;
uniform float FresnelAlpha;
uniform float BaseAlpha;
uniform bool FresnelInvert;
uniform float FresnelMix;
uniform float DistortStrength;
uniform float DistortUniformity;

uniform vec2 ScreenLocation;
uniform vec2 ScreenSize;

out vec4 OutColor;

float roundedBoxSDF(vec2 pos, vec2 size, vec4 radius, float smoothness) {
    radius.xy = (pos.x > 0.0) ? radius.xy : radius.wz;
    radius.x  = (pos.y > 0.0) ? radius.x : radius.y;
    
    vec2 v = abs(pos) - size + radius.x;
    vec2 v_clamped = max(v, 0.0);
    float len = pow(pow(v_clamped.x, smoothness) + pow(v_clamped.y, smoothness), 1.0/smoothness);
    return min(max(v.x, v.y), 0.0) + len - radius.x;
}

void main() {
    vec2 center = Size * 0.5;
    vec2 box_half_size = center - 1.0;
    vec2 pos = (FragCoord * Size) - center;

    float distance = roundedBoxSDF(-pos, box_half_size, Radius, CornerSmoothness);
    float alpha = 1.0 - smoothstep(1.0 - Smoothness, 1.0, distance);

    float distToEdge = abs(roundedBoxSDF(pos, box_half_size, Radius, CornerSmoothness));

    float max_dist_norm = min(box_half_size.x, box_half_size.y);
    float edge_gradient = 1.0 - clamp(distToEdge / max_dist_norm, 0.0, 1.0);

    float fresnel;
    float base = FresnelInvert ? edge_gradient : (1.0 - edge_gradient);

    if (FresnelPower > 20.0) {
        fresnel = exp(FresnelPower * log(clamp(base, 0.001, 1.0)));
    } else {
        fresnel = pow(base, FresnelPower);
    }
    fresnel = clamp(fresnel, 0.0, 1.0);

    // Направление искажения от центра
    vec2 dir = normalize(pos);
    
    // Правильный маппинг текстуры экрана на позицию элемента
    vec2 screenTexCoord = (gl_FragCoord.xy) / ScreenSize;
    
    // Искажение применяется по всей поверхности
    // DistortUniformity контролирует насколько равномерно (0 = только края, 1 = везде одинаково)
    float distortAmount = DistortStrength * mix(fresnel, 1.0, DistortUniformity);
    vec2 distortedTexCoord = screenTexCoord + dir * distortAmount;

    vec4 texColor = texture(Sampler0, distortedTexCoord) * FragColor;

    vec3 finalColor = mix(texColor.rgb, FresnelColor, fresnel * FresnelMix);
    float finalAlpha = mix(BaseAlpha, FresnelAlpha, fresnel) * alpha;

    if (finalAlpha < 0.001) {
        discard;
    }

    OutColor = vec4(finalColor, finalAlpha * GlobalAlpha);
}
