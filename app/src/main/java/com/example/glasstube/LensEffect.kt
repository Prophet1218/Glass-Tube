package com.example.glasstube

import android.graphics.BlendMode
import android.graphics.Color
import android.graphics.Shader
import android.graphics.RenderEffect
import android.graphics.RuntimeShader
import android.view.View
import androidx.annotation.RequiresApi

/**
 * Valós idejű üveg (Android 13+).
 *
 * Nem készít másolatot a képről: a célnézetre a videokártyán futó árnyalóprogramot (AGSL shader)
 * tesz, amely
 *  - a megadott üvegfelületek ("slotok": keresősáv részei, menü, javaslatok, csúszka) területén
 *    elmossa, a szélek felé pedig megtöri a képet,
 *  - a képernyő tetején (állapotsor) elmosást rajzol, kétféle stílusban,
 *  - máshol változatlanul hagyja a képet.
 * A tartalmat így csak egyszer kell kirajzolni: nincs villogás, nincs késés.
 */
@RequiresApi(33)
class LensEffect(private val target: View) {

    private val shader = RuntimeShader(SRC)
    private val rects = Array(SLOTS) { FloatArray(4) }
    private val params = Array(SLOTS) { FloatArray(4) }   // sugár, elmosás, erősség (0..1), nagyító lencse (0..1)
    private val topTint = FloatArray(4)
    private val rim = FloatArray(4)
    private var rimWidth = 1f
    private var refrHeight = 1f
    private var refrAmount = 0f
    private var dispersion = 0f
    private var topH = 0f
    private var topExt = 0f
    private var topBlur = 0f
    private var topMode = 0f
    private var contentTop = 0f
    /** Domború (3D) üveg: a fény- és árnyékhatás erőssége (0 = ki), és a peremi nagyítás (0 = nincs). */
    private var convex = 0f
    private var convexRefr = 0f
    /** Slot 0 as a thick magnifying lens (the pressed tab capsule), 0..1. */
    private var lens0 = 0f

    fun setSlot0Lens(amount: Float) {
        if (lens0 != amount) {
            lens0 = amount
            dirty = true
        }
    }
    /** Az állapotsor mögötti térköz egyszínű kitöltése (alfa 0 = az első sor nyújtása). */
    private val padColor = floatArrayOf(0f, 0f, 0f, 0f)

    fun setPadColor(color: Int) {
        val a = Color.alpha(color) / 255f
        if (set(padColor, Color.red(color) / 255f, Color.green(color) / 255f, Color.blue(color) / 255f, a)) {
            dirty = true
        }
    }
    private var dirty = true
    private var attached = false

    fun setParams(refractionHeightPx: Float, refractionAmountPx: Float, dispersion: Float) {
        if (refrHeight != refractionHeightPx || refrAmount != refractionAmountPx ||
            this.dispersion != dispersion
        ) {
            refrHeight = refractionHeightPx
            refrAmount = refractionAmountPx
            this.dispersion = dispersion
            dirty = true
        }
    }

    /** Egy üvegfelület helye a célnézet koordinátáiban; [radius] a sarok sugara, [blurPx] az elmosás. */
    fun setSlot(
        index: Int, l: Float, t: Float, r: Float, b: Float, radius: Float, blurPx: Float,
        strength: Float = 1f, lens: Float = 0f
    ) {
        if (index < 0 || index >= SLOTS) return
        val p = params[index]
        val st = strength.coerceIn(0f, 1f)
        if (set(rects[index], l, t, r, b) || p[0] != radius || p[1] != blurPx || p[2] != st || p[3] != lens) {
            p[0] = radius
            p[1] = blurPx
            p[2] = st
            p[3] = lens
            dirty = true
        }
    }

    // ---- "átúszó" felső elmosás: valódi (Gauss) elmosás, amely lefelé fokozatosan eltűnik
    private var flowMask: RuntimeShader? = null
    private val flowTint = FloatArray(4)
    private var flowH = 0f
    private var flowExt = 0f
    private var flowBlur = 0f
    private var flowPad = 0f
    private var padShader: RuntimeShader? = null

    /**
     * Az oldal az állapotsor mögött úszik át; ott a rendszer saját, sima elmosását használjuk
     * (nem a mintavételes közelítést), mert mozgó tartalmon az szemcsésen vibrálna.
     * [height] = 0 vagy [blurPx] = 0: kikapcsolva.
     */
    fun setFlow(height: Float, extent: Float, blurPx: Float, tint: Int, padPx: Float = 0f) {
        if (flowPad != padPx) {
            flowPad = padPx
            dirty = true
        }
        val changed = set(
            flowTint,
            Color.red(tint) / 255f, Color.green(tint) / 255f, Color.blue(tint) / 255f, Color.alpha(tint) / 255f
        )
        if (changed || flowH != height || flowExt != extent || flowBlur != blurPx) {
            flowH = height
            flowExt = extent
            flowBlur = blurPx
            dirty = true
        }
    }

    fun clearSlot(index: Int) = setSlot(index, 0f, 0f, 0f, 0f, 0f, 0f)

    /**
     * Domború, vastag üveg minden felületen: [amount] a fénycsillanás / peremárnyék erőssége
     * (0 = kikapcsolva, 1 = teljes), [refraction] a szélek felé erősödő nagyítás (0..1; matt
     * üvegnél 0, mert ott nincs fénytörés).
     */
    fun setConvex(amount: Float, refraction: Float) {
        val a = amount.coerceIn(0f, 1f)
        val r = refraction.coerceIn(0f, 1f)
        if (convex != a || convexRefr != r) {
            convex = a
            convexRefr = r
            dirty = true
        }
    }

    /**
     * Felső elmosás. [height] az állapotsor magassága (0 = kikapcsolva), alatta [extent] pixelen
     * át halványul el. [tint] a ráfestett szín (az alfa az erőssége). [contentTopPx] fölött nincs
     * tartalom, onnan nem veszünk mintát. [flow] = igaz: a tartalom valóban átúszik az állapotsor
     * mögött, és felfelé egyre jobban elmosódik; hamis: elmosott színsáv.
     */
    fun setTop(
        height: Float, extent: Float, blurPx: Float, tint: Int, contentTopPx: Float, flow: Boolean,
        hardEdge: Boolean = false
    ) {
        val changed = set(
            topTint,
            Color.red(tint) / 255f, Color.green(tint) / 255f, Color.blue(tint) / 255f, Color.alpha(tint) / 255f
        )
        // 2 = teljes erővel [height]-ig, onnan [extent] alatt halványul el (a kereső felülethez)
        val mode = if (hardEdge) 2f else if (flow) 1f else 0f
        if (changed || topH != height || topExt != extent || topBlur != blurPx ||
            contentTop != contentTopPx || topMode != mode
        ) {
            topH = height
            topExt = extent
            topBlur = blurPx
            contentTop = contentTopPx
            topMode = mode
            dirty = true
        }
    }

    /** Az üveg pereme és enyhe fehér fátyla, ha a shader rajzolja (0 alfa = nincs). */
    fun setRim(color: Int, widthPx: Float) {
        val changed = set(
            rim,
            Color.red(color) / 255f, Color.green(color) / 255f, Color.blue(color) / 255f, Color.alpha(color) / 255f
        )
        if (changed || rimWidth != widthPx) {
            rimWidth = widthPx
            dirty = true
        }
    }

    private fun set(a: FloatArray, l: Float, t: Float, r: Float, b: Float): Boolean {
        if (a[0] == l && a[1] == t && a[2] == r && a[3] == b) return false
        a[0] = l
        a[1] = t
        a[2] = r
        a[3] = b
        return true
    }

    private var appliedDisp: Boolean? = null
    private var appliedRefr = -1f

    fun apply() {
        if (appliedDisp != Glass.dispersion || appliedRefr != Glass.refraction) {
            appliedDisp = Glass.dispersion
            appliedRefr = Glass.refraction
            dirty = true
        }
        if (!dirty && attached) return
        dirty = false
        attached = true
        for (i in 0 until SLOTS) {
            shader.setFloatUniform("g$i", rects[i])
            shader.setFloatUniform("p$i", params[i])
        }
        shader.setFloatUniform("refrHeight", refrHeight)
        shader.setFloatUniform("refrAmount", refrAmount)
        shader.setFloatUniform("dispersion", dispersion)
        shader.setFloatUniform("topH", topH)
        shader.setFloatUniform("topExt", topExt)
        shader.setFloatUniform("topBlur", topBlur)
        shader.setFloatUniform("topMode", topMode)
        shader.setFloatUniform("topTint", topTint)
        shader.setFloatUniform("contentTop", contentTop)
        shader.setFloatUniform("padColor", padColor)
        shader.setFloatUniform("rimColor", rim)
        shader.setFloatUniform("rimWidth", rimWidth)
        shader.setFloatUniform("convex", convex)
        shader.setFloatUniform("convexRefr", convexRefr)
        shader.setFloatUniform("lens0", lens0)
        shader.setFloatUniform("dispScale", if (Glass.dispersion) 1f else 0f)
        shader.setFloatUniform("refrScale", Glass.refraction)
        val main = RenderEffect.createRuntimeShaderEffect(shader, "content")
        if (flowH > 0f && flowBlur >= 1f) {
            val mask = flowMask ?: RuntimeShader(MASK_SRC).also { flowMask = it }
            mask.setFloatUniform("flowH", flowH)
            mask.setFloatUniform("flowExt", flowExt)
            mask.setFloatUniform("flowTint", flowTint)
            // Az oldal fölötti üres sávot (a térközt, amíg az oldal a tetején van) az oldal első
            // sorának színével töltjük ki, így nem sötét sáv mosódik el az állapotsor mögött.
            val pad = padShader ?: RuntimeShader(PAD_SRC).also { padShader = it }
            pad.setFloatUniform("padH", flowPad)
            // térköz kitöltése -> elmosás -> maszk (lefelé átlátszó) -> ráfektetve az üveges képre
            val blurred = RenderEffect.createChainEffect(
                RenderEffect.createRuntimeShaderEffect(mask, "content"),
                RenderEffect.createChainEffect(
                    RenderEffect.createBlurEffect(flowBlur, flowBlur, Shader.TileMode.CLAMP),
                    RenderEffect.createRuntimeShaderEffect(pad, "content")
                )
            )
            target.setRenderEffect(RenderEffect.createBlendModeEffect(main, blurred, BlendMode.SRC_OVER))
        } else {
            target.setRenderEffect(main)
        }
    }

    fun clear() {
        if (attached) {
            attached = false
            target.setRenderEffect(null)
        }
    }

    companion object {
        /** Ennyi üvegfelületet tud egyszerre rajzolni; a kisebb sorszám van felül. */
        const val SLOTS = 8

        private const val MASK_SRC = """
uniform shader content;
uniform float flowH;
uniform float flowExt;
uniform vec4 flowTint;

half4 main(vec2 c) {
    // az állapotsor mögött teljesen elmosott, alatta [flowExt] képponton át fokozatosan eltűnik
    float w = clamp(1.0 - (c.y - flowH) / max(flowExt, 1.0), 0.0, 1.0);
    w = w * w * (3.0 - 2.0 * w);
    if (w <= 0.0) {
        return half4(0.0);
    }
    vec4 col = vec4(content.eval(c));
    float ta = flowTint.a;
    col.rgb = col.rgb * (1.0 - ta) + flowTint.rgb * ta * col.a;
    return half4(col * w);
}
"""

        private const val PAD_SRC = """
uniform shader content;
uniform float padH;

half4 main(vec2 c) {
    if (c.y < padH) {
        return content.eval(vec2(c.x, padH + 1.5));
    }
    return content.eval(c);
}

"""

        private const val SRC = """
uniform shader content;
uniform vec4 g0;
uniform vec4 p0;
uniform vec4 g1;
uniform vec4 p1;
uniform vec4 g2;
uniform vec4 p2;
uniform vec4 g3;
uniform vec4 p3;
uniform vec4 g4;
uniform vec4 p4;
uniform vec4 g5;
uniform vec4 p5;
uniform vec4 g6;
uniform vec4 p6;
uniform vec4 g7;
uniform vec4 p7;
uniform float refrHeight;
uniform float refrAmount;
uniform float dispersion;
uniform float topH;
uniform float topExt;
uniform float topBlur;
uniform float topMode;
uniform vec4 topTint;
uniform float contentTop;
uniform vec4 padColor;
uniform vec4 rimColor;
uniform float rimWidth;
uniform float convex;
uniform float convexRefr;
uniform float lens0;
uniform float dispScale;
uniform float refrScale;

float sdRound(vec2 p, vec2 hs, float r) {
    vec2 q = abs(p) - hs + vec2(r);
    return length(max(q, vec2(0.0))) + min(max(q.x, q.y), 0.0) - r;
}

float sdRect(vec2 c, vec4 rect, float r) {
    return sdRound(c - (rect.xy + rect.zw) * 0.5, (rect.zw - rect.xy) * 0.5, r);
}

vec4 tap(vec2 p) {
    // a térköz az oldal fejlécének színével töltődik ki (ikonok nélkül); ha az nem ismert,
    // az első sor nyúlik fel
    if (p.y < contentTop && padColor.a > 0.0) {
        // (partly transparent while the search panel fades in over it)
        if (padColor.a > 0.995) return vec4(padColor.rgb, 1.0);
        return mix(content.eval(p), vec4(padColor.rgb, 1.0), padColor.a);
    }
    return vec4(content.eval(vec2(p.x, max(p.y, contentTop + 0.5))));
}

float hash(vec2 p) {
    return fract(sin(dot(p, vec2(12.9898, 78.233))) * 43758.5453);
}

vec4 blurAt(vec2 c, float r) {
    if (r < 0.75) {
        return tap(c);
    }
    float rot = hash(c) * 6.2832;
    vec4 s = vec4(0.0);
    float wsum = 0.0;
    for (int i = 0; i < 24; i++) {
        float fi = float(i);
        float u = (fi + 0.5) / 24.0;
        float ang = fi * 2.39996 + rot;
        float w = 1.0 - 0.75 * u;
        s += tap(c + vec2(cos(ang), sin(ang)) * (sqrt(u) * r)) * w;
        wsum += w;
    }
    return s / wsum;
}

vec4 glass(vec2 c, vec4 rect, float radius, float d, float blur, float strength, float lensIn) {
    // a negative lens value means: no magnifier, edge refraction scaled down (small buttons)
    float lensAmt = max(lensIn, 0.0);
    float rs = lensIn < 0.0 ? -lensIn : 1.0;
    vec2 center = (rect.xy + rect.zw) * 0.5;
    vec2 hs = (rect.zw - rect.xy) * 0.5;
    vec2 p = c - center;
    vec2 q = abs(p) - hs + vec2(radius);
    vec2 n = vec2(0.0, 1.0);
    if (q.x > 0.0 && q.y > 0.0) {
        n = normalize(q);
    } else if (q.x > q.y) {
        n = vec2(1.0, 0.0);
    }
    n = n * sign(p);
    float rh = max(min(refrHeight, radius), 1.0);
    // small buttons with an icon inside (back buttons): the refraction stays in a thin band at
    // the edge, so the icon in the middle is not bent along with the picture
    // icon buttons and the pressed tab capsules: the bending stays in a band at the rim, the
    // middle (where the icon is) is only gently magnified, not stretched
    if (lensIn != 0.0) {
        rh = max(radius * 0.36, 1.0);
    }
    float t = clamp(1.0 + d / rh, 0.0, 1.0);
    float ra = lensIn != 0.0 ? min(refrAmount, rh * 1.1) : refrAmount;
    float k = (1.0 - sqrt(1.0 - t * t)) * ra * rs * refrScale;
    vec2 off = n * k;
    // Domború üveg: a felület egy lekerekített "párna"; a pereme felé egyre meredekebb.
    // u = 0 a lapos közepén, 1 a peremen; tilt = a felület dőlése (a normális vízszintes része).
    float bev = max(min(radius, min(hs.x, hs.y)), 1.0);
    float u = clamp(1.0 + d / bev, 0.0, 1.0);
    float tilt = u * u;
    float cv = max(convex, lensAmt);
    float cr = max(convexRefr, lensAmt * 0.2);
    if (cr > 0.0) {
        // vastag lencse: a szélek felé erősebben húzza befelé a képet, középen enyhén nagyít
        off += (n * (tilt * u) * (bev * 0.32) + p * 0.045) * cr;
    }
    if (lensAmt > 0.0) {
        // magnifying drop: the middle is enlarged, the rim bends the picture strongly
        off += (p * 0.05 + n * (tilt * tilt * tilt) * (bev * 0.1)) * lensAmt * refrScale;
    }
    float disp = max(dispersion, 0.08 * lensAmt) * dispScale;
    vec4 col = blurAt(c - off, blur);
    if (disp > 0.0 && dot(off, off) > 0.0) {
        col.r = blurAt(c - off * (1.0 + disp), blur).r;
        col.b = blurAt(c - off * (1.0 - disp), blur).b;
    }
    if (rimColor.a > 0.0) {
        col.rgb = col.rgb + (vec3(1.0) - col.rgb) * (0.07 * rimColor.a);
        float e = clamp(1.0 + d / max(rimWidth, 0.5), 0.0, 1.0);
        float a = e * e * rimColor.a;
        col.rgb = col.rgb * (1.0 - a) + rimColor.rgb * a;
    }
    if (cv > 0.0) {
        // fény balról fölülről: a bal felső belső peremen csillanás, a jobb alsón enyhe árnyék
        float facing = dot(n, vec2(-0.55, -0.835));
        float lit = max(facing, 0.0);
        float away = max(-facing, 0.0);
        float u2 = u * u;
        float edge6 = u2 * u2 * u2;
        // lágy fénycsík a megvilágított belső peremen
        float spec = edge6 * lit * lit * 0.42;
        // halvány fényfolt a felület felső részén (a "kupola" teteje veri vissza a fényt)
        float yN = clamp((c.y - rect.y) / max(rect.w - rect.y, 1.0), 0.0, 1.0);
        float sheen = (1.0 - smoothstep(0.0, 0.55, yN)) * (1.0 - u) * 0.07;
        // belső árnyék a jobb alsó perem felé (az üveg vastagsága)
        float shade = u2 * u * away * 0.16;
        col.rgb = col.rgb * (1.0 - shade * cv);
        col.rgb = col.rgb + (vec3(1.0) - col.rgb) * ((spec + sheen) * cv);
        // vékony, fényes perem (a szemközti, jobb alsó oldalon is átüt a fény)
        float rw = clamp(bev * 0.07, 1.0, 3.2);
        float rimE = 1.0 - smoothstep(0.0, rw, -d);
        float rimA = rimE * (0.16 + 0.42 * lit + 0.20 * away) * cv;
        col.rgb = col.rgb + (vec3(1.0) - col.rgb) * rimA;
        col.rgb = clamp(col.rgb, 0.0, 1.0);
    }
    col.a = 1.0;
    if (strength < 1.0) {
        col = mix(vec4(content.eval(c)), col, strength);
    }
    return col;
}

// everything except the two press capsules (they fade in over this, not over the bare content)
half4 rest(vec2 c) {
    if (g2.z > g2.x) {
        float d2 = sdRect(c, g2, p2.x);
        if (d2 < 0.0) {
            return half4(glass(c, g2, p2.x, d2, p2.y, p2.z, p2.w));
        }
    }
    if (g3.z > g3.x) {
        float d3 = sdRect(c, g3, p3.x);
        if (d3 < 0.0) {
            return half4(glass(c, g3, p3.x, d3, p3.y, p3.z, p3.w));
        }
    }
    if (g4.z > g4.x) {
        float d4 = sdRect(c, g4, p4.x);
        if (d4 < 0.0) {
            return half4(glass(c, g4, p4.x, d4, p4.y, p4.z, p4.w));
        }
    }
    if (g5.z > g5.x) {
        float d5 = sdRect(c, g5, p5.x);
        if (d5 < 0.0) {
            return half4(glass(c, g5, p5.x, d5, p5.y, p5.z, p5.w));
        }
    }
    if (g6.z > g6.x) {
        float d6 = sdRect(c, g6, p6.x);
        if (d6 < 0.0) {
            return half4(glass(c, g6, p6.x, d6, p6.y, p6.z, p6.w));
        }
    }
    if (g7.z > g7.x) {
        float d7 = sdRect(c, g7, p7.x);
        if (d7 < 0.0) {
            return half4(glass(c, g7, p7.x, d7, p7.y, p7.z, p7.w));
        }
    }
    if (topH > 0.0 && c.y < topH + topExt) {
        float w = 1.0;
        vec2 s = c;
        if (topMode < 0.5) {
            if (c.y > topH) {
                w = 1.0 - (c.y - topH) / max(topExt, 1.0);
            }
            s = vec2(c.x, max(c.y, topH) + topBlur * w);
        } else {
            // átúszó: az állapotsor felső részén teljes erővel, onnan lefelé egyenletesen,
            // fokozatosan gyengül; a teljes szélességben
            // lefelé lágyan, folyamatosan halványul (az eddigi kiterjedésen belül marad)
            if (topMode > 1.5) {
                w = 1.0 - smoothstep(topH, topH + topExt, c.y);
            } else {
                float t = clamp(c.y / max(topH + topExt, 1.0), 0.0, 1.0);
                w = 1.0 - smoothstep(0.2, 1.0, t);
            }
        }
        vec4 col = blurAt(s, topBlur * w);
        if (topMode > 0.5) {
            // az elmosott és az éles kép között átúsztatunk, így nincs látható határ
            col = mix(vec4(tap(c)), col, w);
        }
        float ta = topTint.a * w;
        col.rgb = col.rgb * (1.0 - ta) + topTint.rgb * ta;
        col.a = 1.0;
        return half4(col);
    }
    return content.eval(c);
}

half4 main(vec2 c) {
    // the press capsules come first and hand over smoothly to the surface underneath
    if (g0.z > g0.x) {
        float d0 = sdRect(c, g0, p0.x);
        if (d0 < 0.0) {
            half4 g = half4(glass(c, g0, p0.x, d0, p0.y, 1.0, max(p0.w, lens0)));
            if (p0.z < 0.999) {
                return mix(rest(c), g, half(p0.z));
            }
            return g;
        }
    }
    if (g1.z > g1.x) {
        float d1 = sdRect(c, g1, p1.x);
        if (d1 < 0.0) {
            half4 g = half4(glass(c, g1, p1.x, d1, p1.y, 1.0, p1.w));
            if (p1.z < 0.999) {
                return mix(rest(c), g, half(p1.z));
            }
            return g;
        }
    }
    return rest(c);
}
"""
    }
}
