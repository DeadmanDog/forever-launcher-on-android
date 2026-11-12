// SPDX-License-Identifier: GPL-3.0-or-later
// ReShade FX adaptation of DroidDeck's CAS and color shaders at aba55031c331.
// https://github.com/Droid-Deck/DroidDeck/tree/aba55031c331cd0991c3d36788fb903a3ca30e78/app/src/main/cpp/shaders
// CAS samples the game buffer before Gamescope scaling. Color grading is fused
// after CAS to avoid a second render pass.
texture2D Scene : COLOR;
sampler2D SceneSampler {
    Texture = Scene;
    AddressU = CLAMP;
    AddressV = CLAMP;
    MinFilter = POINT;
    MagFilter = POINT;
    MipFilter = POINT;
};

void FullscreenVS(uint vertex : SV_VertexID, out float4 position : SV_Position, out float2 uv : TEXCOORD0) {
    uv = float2((vertex << 1) & 2, vertex & 2);
    position = float4(uv * float2(2.0, -2.0) + float2(-1.0, 1.0), 0.0, 1.0);
}

float4 PicturePS(float4 position : SV_Position, float2 uv : TEXCOORD0) : SV_Target {
    const float2 dx = float2(BUFFER_RCP_WIDTH, 0.0);
    const float2 dy = float2(0.0, BUFFER_RCP_HEIGHT);
    const float4 center = tex2D(SceneSampler, uv);
    const float3 north = tex2D(SceneSampler, uv - dy).rgb;
    const float3 south = tex2D(SceneSampler, uv + dy).rgb;
    const float3 west = tex2D(SceneSampler, uv - dx).rgb;
    const float3 east = tex2D(SceneSampler, uv + dx).rgb;
    const float3 nw = tex2D(SceneSampler, uv - dx - dy).rgb;
    const float3 ne = tex2D(SceneSampler, uv + dx - dy).rgb;
    const float3 sw = tex2D(SceneSampler, uv - dx + dy).rgb;
    const float3 se = tex2D(SceneSampler, uv + dx + dy).rgb;
    const float3 lo = min(center.rgb, min(min(north, south), min(west, east)));
    const float3 hi = max(center.rgb, max(max(north, south), max(west, east)));
    const float3 softMin = lo + min(lo, min(min(nw, ne), min(sw, se)));
    const float3 softMax = hi + max(hi, max(max(nw, ne), max(sw, se)));
    const float3 amplitude = sqrt(saturate(min(softMin, 2.0 - softMax) / max(softMax, 0.00001)));
    const float3 weight = -amplitude / (8.0 - 3.0 * WOW_CAS);
    float3 color = saturate((center.rgb + (north + south + west + east) * weight) / (1.0 + 4.0 * weight));
#if WOW_HAS_COLOR
    color = (saturate(color + WOW_BRIGHTNESS) - 0.5) * WOW_CONTRAST + 0.5;
    const float luma = dot(color, float3(0.2126, 0.7152, 0.0722));
    color = pow(saturate(lerp(luma.xxx, color, WOW_SATURATION)), 1.0 / WOW_GAMMA);
#endif
    return float4(color, center.a);
}

technique DroidDeckPicture {
    pass {
        VertexShader = FullscreenVS;
        PixelShader = PicturePS;
    }
}
