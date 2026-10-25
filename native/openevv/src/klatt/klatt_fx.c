#include <math.h>
#include <stdlib.h>
#include <string.h>

#include "klatt_fx.h"
#include "klatt_tables.h"

/* The original relies on >> sign-extending negative operands, which C leaves
   implementation-defined. Every compiler we target does this; fail the build
   rather than produce silently wrong audio on one that does not. */
typedef char kfx_needs_arithmetic_shift[((int32_t)-8 >> 1) == -4 ? 1 : -1];

void clr_vector(int32_t *v, int32_t n)
{
    memset(v, 0, (size_t)n * 4u);
}

uint32_t klatt_rand(int16_t *out, int32_t n, uint32_t seed)
{
    int32_t i;

    for (i = 0; i < n; i++) {
        seed = seed * 0x19660du + 0x3c6ef35fu;
        *out++ = (int16_t)(seed & 0xffffu);
    }
    return seed;
}

int16_t fxdivl(int32_t num, int32_t den)
{
    int      positive = 1;
    int16_t  result;
    uint32_t n, q;
    int32_t  shift;

    if (den < 0) {
        den = -den;
        num = -num;
    }
    if (num < 0) {
        positive = 0;
        num = -num;
    }

    /* Saturation is not an early exit in the original: it still runs the sign
       fixup below, so a saturated negative quotient comes back as -32767. */
    if (den == 0 || num >= den) {
        result = 0x7fff;
    } else if (num == 0) {
        result = 0;
    } else {
        /* Normalising stalls forever when num's low 16 bits are all zero,
           because num << 16 is then zero and no shift ever sets bit 31. The
           original has the same hole; the engine only feeds it small
           magnitudes. */
        n = (uint32_t)num << 16;
        shift = 16;
        while ((n & 0x80000000u) == 0) {
            n <<= 1;
            shift++;
        }

        q = n / (uint32_t)den;
        q <<= (31 - shift);

        result = (int16_t)(q >> 16);
        if (q & 0x8000u)
            result = (int16_t)(result + 1);
    }

    if (!positive && result != 0)
        result = (int16_t)(-result);

    return result;
}


void fxmul_vector(const int32_t *src, int16_t coef, int32_t *acc, int32_t n)
{
    int32_t i;

    for (i = 0; i < n; i++)
        acc[i] += fxmul_scaled(coef, src[i]);
}

void fxmul1_vector(const int16_t *src, int16_t coef, int32_t *acc, int32_t n)
{
    int32_t i;

    for (i = 0; i < n; i++)
        acc[i] += fxmul_scaled(coef, (int32_t)src[i] << 4);
}

int32_t db2lin(int32_t db)
{
    int32_t t, quot, rem;

    if (db <= 0)
        return 0;

    /* 299/90 is log2(10) to four places, so this is dB expressed in
       twentieths of an octave, clamped at 20 doublings. */
    t = mul32(db, 299) / 90;
    if (t >= 400)
        t = 400;

    quot = t / 20;
    rem = t % 20;

    return fxmul_scaled(klatt_fxl2[rem], 2 << quot);
}

const char KlattVersionString[] =
    "\r\nKlattID version 4.0 \xa9 International Business Machines, Inc. "
    "1996, 1997 \r\n";

int verifyKlattHandle(void *handle)
{
    return strcmp(*(char **)handle, KlattVersionString) == 0;
}

typedef char filter_parms_is_84_bytes[sizeof(filter_parms) == 84 ? 1 : -1];

/* A two-pole resonator. It keeps its history in the sample buffer itself,
   two slots ahead of the pointer it was handed, rather than in locals.
   The three products are weighted 1, 2 and 4 on the way out, so the three
   coefficients are held at three different fixed-point scales. */
static void pole_filter_wide(filter_parms *fp, int32_t *buf, int32_t n);

void pole_filter(filter_parms *fp, int32_t *buf, int32_t n)
{
    int32_t i, count, k, t1, t2, t3;

    if (fp->enabled == 0)
        return;

    buf[-2] = fp->d2;
    buf[-1] = fp->d1;
    i = 0;

    /* After the two above, not before them: whatever runs next over this
       buffer reads them, and leaving them as the last filter set them puts
       a step at the head of every block. */
    if (klatt_wide_on()) {
        pole_filter_wide(fp, buf, n);
        return;
    }

    if (fp->ramp != 0) {
        count = fp->ramp < n ? fp->ramp : n;
        k = 3 - fp->ramp;

        for (; i < count; i++) {
            t1 = fxmul_scaled(fp->c[k], buf[i - 2]);
            t2 = fxmul_scaled(fp->b[k], buf[i - 1]);
            t3 = fxmul_scaled(fp->a[k], buf[i]);
            buf[i] = t1 + t2 * 2 + t3 * 4;
            k++;
        }
        fp->ramp -= count;
    }

    for (; i < n; i++) {
        t1 = fxmul_scaled(fp->sc, buf[i - 2]);
        t2 = fxmul_scaled(fp->sb, buf[i - 1]);
        t3 = fxmul_scaled(fp->sa, buf[i]);
        buf[i] = t1 + t2 * 2 + t3 * 4;
    }

    if (n > 1) {
        fp->d2 = buf[i - 2];
        fp->d1 = buf[i - 1];
    } else {
        fp->d2 = fp->d1;
        fp->d1 = buf[i - 1];
    }
}

/* The same resonator for a synthesiser whose top is heard: the three
   products summed whole and rounded once, to nearest. IBM's rounds each
   down on its own, which biases every sample the same way, so in silence a
   resonator sits off nought rather than settling there; when the cascade is
   switched off after speech the output steps back to nought, which goes
   unheard in the voice IBM made and in the wideband voice's raised top is a
   click. */
void pole_filter_exact(filter_parms *fp, int32_t *buf, int32_t n)
{
    int32_t i, count, k;

    if (fp->enabled == 0)
        return;

    buf[-2] = fp->d2;
    buf[-1] = fp->d1;
    i = 0;

    if (fp->ramp != 0) {
        count = fp->ramp < n ? fp->ramp : n;
        k = 3 - fp->ramp;

        for (; i < count; i++) {
            int64_t v = (int64_t)fp->c[k] * buf[i - 2]
                      + (int64_t)fp->b[k] * buf[i - 1] * 2
                      + (int64_t)fp->a[k] * buf[i] * 4;

            buf[i] = (int32_t)fx_shift(v, 15);
            k++;
        }
        fp->ramp -= count;
    }

    for (; i < n; i++) {
        int64_t v = (int64_t)fp->sc * buf[i - 2]
                  + (int64_t)fp->sb * buf[i - 1] * 2
                  + (int64_t)fp->sa * buf[i] * 4;

        buf[i] = (int32_t)fx_shift(v, 15);
    }

    if (n > 1) {
        fp->d2 = buf[i - 2];
        fp->d1 = buf[i - 1];
    } else {
        fp->d2 = fp->d1;
        fp->d1 = buf[i - 1];
    }
}

/* The same resonator with no input term and no ramp: it runs purely on its
   own history, which is what the parallel branch wants when the excitation is
   summed in somewhere else. */
void parallel0_filter(filter_parms *fp, int32_t *buf, int32_t n)
{
    int32_t i, t1, t2;

    buf[-2] = fp->d2;
    buf[-1] = fp->d1;

    for (i = 0; i < n; i++) {
        t1 = fxmul_scaled(fp->sc, buf[i - 2]);
        t2 = fxmul_scaled(fp->sb, buf[i - 1]);
        buf[i] = t1 + t2 * 2;
    }

    if (n > 1) {
        fp->d2 = buf[i - 2];
        fp->d1 = buf[i - 1];
    } else {
        fp->d2 = fp->d1;
        fp->d1 = buf[i - 1];
    }
}

void zero_filter(filter_parms *fp, const zero_ABCs *z, int32_t *buf, int32_t n)
{
    int32_t p1, p2, x, i, count, k;

    if (fp->enabled == 0)
        return;

    p2 = fp->d2;
    p1 = fp->d1;
    i = 0;

    /* While the ramp is live the coefficients come from the three-entry
       tables, one entry per sample, so a parameter change slides in instead
       of stepping. ramp above 3 would index off the front of them. */
    if (fp->ramp != 0) {
        count = fp->ramp < n ? fp->ramp : n;
        k = 3 - fp->ramp;

        for (; i < count; i++) {
            x = buf[i];
            buf[i] = (mul32(fp->a[k], x) >> 4)
                   + (mul32(fp->b[k], p1) >> 4)
                   + (mul32(fp->c[k], p2) >> 4);
            k++;
            p2 = p1;
            p1 = x;
        }
        fp->ramp -= count;
    }

    for (; i < n; i++) {
        x = buf[i];
        buf[i] = (mul32(z->a, x) >> 4)
               + (mul32(z->b, p1) >> 4)
               + (mul32(z->c, p2) >> 4);
        p2 = p1;
        p1 = x;
    }

    /* With n of zero the original saves the untouched d1 into d2 rather than
       the real d2, so a zero-length call is not a no-op. */
    if (n > 1) {
        fp->d2 = p2;
        fp->d1 = p1;
    } else {
        fp->d2 = fp->d1;
        fp->d1 = p1;
    }
}

/* ---- trigonometry and roots in whole numbers -------------------------- */

/* The cosine and sine of num/den of a turn, in units of 2^-30. Folded into
   the first eighth of a turn, where a Taylor series of seven terms is good
   to well under one of those units, and every step rounded the one way. */
#define FX_PI4  843314857        /* pi / 4, in units of 2^-30 */

static int64_t fx_sin8(int64_t x)
{
    static const int32_t d[6] = { 156, 110, 72, 42, 20, 6 };
    int64_t x2 = fx_shift(x * x, 30), t = FX_ONE;
    int     i;

    for (i = 0; i < 6; i++)
        t = FX_ONE - fx_div(fx_shift(x2 * t, 30), d[i]);
    return fx_shift(x * t, 30);
}

static int64_t fx_cos8(int64_t x)
{
    static const int32_t d[7] = { 182, 132, 90, 56, 30, 12, 2 };
    int64_t x2 = fx_shift(x * x, 30), t = FX_ONE;
    int     i;

    for (i = 0; i < 7; i++)
        t = FX_ONE - fx_div(fx_shift(x2 * t, 30), d[i]);
    return t;
}

void fx_cos_sin(int64_t num, int64_t den, int32_t *c, int32_t *s)
{
    int64_t eighths, part, x, sx, cx;
    int     octant;

    num %= den;
    if (num < 0)
        num += den;
    eighths = 8 * num;
    octant = (int)(eighths / den);
    part = eighths - (int64_t)octant * den;
    /* An odd octant runs back from its far end, so the series is always
       asked about the near side of an eighth. */
    if (octant & 1)
        part = den - part;
    x = fx_div(FX_PI4 * part, den);
    sx = fx_sin8(x);
    cx = fx_cos8(x);

    switch (octant) {
    case 0:  *c = (int32_t)cx;  *s = (int32_t)sx;  break;
    case 1:  *c = (int32_t)sx;  *s = (int32_t)cx;  break;
    case 2:  *c = (int32_t)-sx; *s = (int32_t)cx;  break;
    case 3:  *c = (int32_t)-cx; *s = (int32_t)sx;  break;
    case 4:  *c = (int32_t)-cx; *s = (int32_t)-sx; break;
    case 5:  *c = (int32_t)-sx; *s = (int32_t)-cx; break;
    case 6:  *c = (int32_t)sx;  *s = (int32_t)-cx; break;
    default: *c = (int32_t)cx;  *s = (int32_t)-sx; break;
    }
}

/* The whole part of a square root, a bit at a time. */
uint64_t fx_isqrt(uint64_t v)
{
    uint64_t root = 0, bit = (uint64_t)1 << 62;

    while (bit > v)
        bit >>= 2;
    while (bit != 0) {
        if (v >= root + bit) {
            v -= root + bit;
            root = (root >> 1) + bit;
        } else {
            root >>= 1;
        }
        bit >>= 2;
    }
    return root;
}

/* ---- shaping the noise for a rate above the engine's own ---------------
 *
 * The frication and aspiration source is white: klatt_rand puts one value
 * per output sample in the buffer and nothing bounds it, so the noise
 * occupies whatever band the rate gives it. At 11,025 that is right by
 * accident -- Nyquist is 5,512 and the source is meant to stop there. Ask
 * the synthesiser for 22,050 and the same code spreads the same energy over
 * twice the band; at 44,100 there is as much of it above 11 kHz as between
 * 5.5 and 11, which is why sibilants synthesised outright go thin and hissy
 * and why raising the rate afterwards has been the only way up.
 *
 * So bound it where the engine's own rate bounds it. Four poles at 5,512
 * hertz, and a gain of sqrt(rate / 11025) to put back the power the band
 * limit takes out: white noise at rate R carries its variance over R/2 of
 * spectrum, so confining it to a fixed 5,512 without that gain would leave
 * the band quieter than 11,025 leaves it rather than the same.
 *
 * At 11,025 and below this does nothing at all, by the test below rather
 * than by the arithmetic coming out to unity. That is deliberate: it is what
 * keeps every recorded case byte for byte what it was.
 *
 * Off unless asked for: the wide rates keep their noise open to the whole
 * band, which is the sound from before the shaping was put back.
 * EVV_NOISE=shaped brings it back for comparison. The wideband companion
 * sets its own edge explicitly and is unaffected by this default. */

#define NOISE_BAND 5512

static int noise_shaping(void)
{
    static int decided, on = 0;

    if (!decided) {
        const char *say = getenv("EVV_NOISE");

        decided = 1;
        if (say != 0 && strcmp(say, "shaped") == 0)
            on = 1;
    }
    return on;
}

/* Fourth-order Butterworth, as two biquads. A gentler filter is no use
   here: the whole transition band is the one octave between 5,512 and the
   new Nyquist at 22,050, so twelve decibels an octave arrives at Nyquist
   having taken almost nothing off. Twenty-four is the least that bites.

   Designed at the rate rather than stored, the way klatt_rates.c builds the
   resonator tables, because the rate is not known until the caller asks;
   and designed when the rate is set rather than on every buffer, with the
   state beside the coefficients in the synthesiser's own block, so that one
   synthesiser's noise history can never be another's. The gain goes into
   the second section's numerator, where it costs nothing. */
static void noise_design(klatt_noise_filter *f, int32_t rate, int32_t band,
                         int64_t gain)
{
    /* One over twice each section's Q, in units of 2^-30: the cosine and
       sine of an eighth of a half turn, as a fourth-order Butterworth's two
       Qs always come to. */
    static const int64_t inv_2q[2] = { 992008095, 410903208 };
    int32_t c, s;
    int     k;

    memset(f, 0, sizeof *f);
    fx_cos_sin(band, rate, &c, &s);
    for (k = 0; k < 2; k++) {
        int64_t alpha = fx_shift((int64_t)s * inv_2q[k], 30);
        int64_t a0 = FX_ONE + alpha;

        f->b0[k] = (int32_t)fx_div((FX_ONE - c) << 23, a0);
        f->a1[k] = (int32_t)fx_div(-((int64_t)c << 25), a0);
        f->a2[k] = (int32_t)fx_div((FX_ONE - alpha) << 24, a0);
    }
    f->b0[1] = (int32_t)fx_shift((int64_t)f->b0[1] * gain, 16);
}

void klatt_noise_shape(klatt_noise_filter *f, int32_t rate)
{
    if (rate <= 11025 || !noise_shaping()) {
        memset(f, 0, sizeof *f);
        return;
    }

    /* White noise carries its variance over the whole band it is given, so
       confining it to a fixed 5,512 leaves less power in that band than
       11,025 leaves there. sqrt(rate / 11025) puts it back. */
    noise_design(f, rate, NOISE_BAND,
                 (int64_t)fx_isqrt(((uint64_t)rate << 32) / 11025));
}

/* The same filter at an edge the caller names, and no gain. The wideband
   companion keeps only what lies above its partner's band, so the noise
   there is the band it is given to be heard in, and where that stops is a
   choice made by ear rather than the partner's Nyquist. No edge, or one at
   or past Nyquist, leaves the noise white. */
void klatt_noise_edge(klatt_noise_filter *f, int32_t rate, int32_t edge)
{
    if (edge <= 0 || 2 * edge >= rate) {
        memset(f, 0, sizeof *f);
        return;
    }
    noise_design(f, rate, edge, (int64_t)1 << 16);
}

void klatt_noise_run(klatt_noise_filter *f, int stream, int16_t *buf,
                     int32_t n)
{
    int32_t i;
    int     k;

    if (f->b0[0] == 0)
        return;

    for (i = 0; i < n; i++) {
        int64_t v = (int64_t)buf[i] << 12;

        for (k = 0; k < 2; k++) {
            int32_t *z = f->z[stream][k];
            int64_t  b0 = f->b0[k];
            int64_t  y = fx_shift(b0 * v, 24) + z[0];

            z[0] = (int32_t)(fx_shift(2 * b0 * v - f->a1[k] * y, 24) + z[1]);
            z[1] = (int32_t)fx_shift(b0 * v - f->a2[k] * y, 24);
            v = y;
        }
        /* Towards nought, as the cast from a double was. */
        v = v < 0 ? -(-v >> 12) : v >> 12;
        if (v > 32767)
            v = 32767;
        else if (v < -32768)
            v = -32768;
        buf[i] = (int16_t)v;
    }
}

/* ---- the resonators, with a fraction to their name ---------------------
 *
 * pole_filter keeps its state in the output buffer as whole numbers, and
 * shifts each of the three products down by fifteen before summing them. So
 * every sample of the recursion is truncated to an integer and that error is
 * fed straight back. What a resonator does with a feedback error depends on
 * how close its poles sit to the unit circle, and raising the sample rate is
 * precisely what pushes them there: a pole at a 60 hertz bandwidth has a
 * radius of 0.983 at 11,025 and 0.996 at 44,100, and the round-off noise a
 * direct form throws off grows with the square of the distance closing.
 *
 * That is why the fixed point engine upsamples worse than a floating point
 * one while sounding the same at 11,025 -- the design is tuned where the
 * arithmetic is comfortable, and nothing about the tuning says so.
 *
 * So above the engine's own rate, keep the state in a double beside the
 * block rather than truncated in the buffer. The coefficients stay exactly
 * the ones the fixed point path built, so this separates the two questions:
 * what the state's precision costs, which is this, from what the
 * coefficients' precision costs, which is a further change if this is not
 * enough.
 *
 * The state cannot go in filter_parms: that block is IBM's field for field
 * and the thirty-two bit build checks every offset. It is keyed by the
 * resonator's own address instead, which is stable and unique because each
 * one lives in the array inside the synthesiser's block.
 *
 * UNFINISHED, and off unless EVV_WIDE=on asks for it. Where it has got to,
 * on 6 September 2026:
 *
 * EVV_WIDE=emulate runs this same shadow carrying the narrow path's own
 * arithmetic, and requires the two to come out bit for bit identical. That
 * is the check that says whether a difference is precision or structure, and
 * it has already earned itself. It said structure, twice.
 *
 * The first was the whole of the spikes: KlattSynth zeroes d1 and d2 itself
 * when a filter resets between frames, and a shadow that did not follow went
 * on ringing from state the engine had silenced. Peak 32316 against the
 * narrow path's 5027, clipping at ordinary volume, and the clipping was what
 * filled the band above 5,512 with 54 dB of nonsense. Resyncing whenever the
 * two disagree brings it to peak 4884 against 5027 and RMS 875 against 989 --
 * the same signal, near enough to argue about.
 *
 * The second is still open. Emulate and narrow first differ at sample 16 and
 * then almost everywhere, by up to 1483 counts. Sixteen is early enough to be
 * a block boundary rather than anything in the recursion, so the state handed
 * from one call to the next is the place to look, and the `n > 1' arm at the
 * end of pole_filter -- which does something odd when n is nought or one --
 * is the first suspect. As it stands the wide
 * path is wrong rather than merely different: at a quarter volume, where
 * nothing clips, it comes out 10.8 dB quieter in RMS than the narrow one and
 * with a peak three times higher, which is spikes rather than speech, and at
 * full volume those spikes clip and fill the band above 5,512 with the
 * distortion. Precision alone cannot do that -- fxmul_scaled is a block
 * floating point multiply rather than a plain shift, so the narrow path is
 * far less lossy than it looks -- so there is a structural mistake in here
 * and not just a difference of arithmetic.
 *
 * The check that would find it: make this path truncate each of the three
 * products separately, exactly as the narrow one does, and require the two
 * to agree bit for bit. If they do not, the recursion is wrong; if they do,
 * the difference really is precision and the spikes are something the
 * truncation was holding down. */

static int32_t wide_round(double y);
#define WIDES 64

static struct {
    const filter_parms *fp;
    double              d1, d2;
} wide[WIDES];

static int wide_on;

void klatt_wide_enable(int32_t rate)
{
    static int decided, allowed;

    if (!decided) {
        const char *say = getenv("EVV_WIDE");

        decided = 1;
        allowed = 0;
        if (say != 0 && strcmp(say, "on") == 0)
            allowed = 1;
        /* The same structure carrying the narrow path's own arithmetic. If
           this does not come out bit for bit identical to it, the recursion
           here is wrong and precision was never the question. */
        else if (say != 0 && strcmp(say, "emulate") == 0)
            allowed = 2;
    }
    wide_on = allowed && rate > 11025;
}

/* The wide state shadows d1 and d2 and has to follow them. KlattSynth zeroes
   the pair itself when a filter resets between frames, and a shadow that
   missed that went on ringing from state the engine had silenced -- which is
   where the spikes came from, and it was structure rather than precision:
   the same shadow carrying the narrow path's own arithmetic reproduced them
   exactly.

   So resync whenever the two disagree. In the ordinary case this path wrote
   d1 and d2 itself at the end of the last call and they still match, so the
   test costs a compare and says nothing; when anything else has written
   them, it is the only warning there is. */
static double *wide_state(filter_parms *fp)
{
    int i, spare = -1;

    for (i = 0; i < WIDES; i++) {
        if (wide[i].fp == fp) {
            if (wide_round(wide[i].d1) != fp->d1
                || wide_round(wide[i].d2) != fp->d2) {
                wide[i].d1 = (double)fp->d1;
                wide[i].d2 = (double)fp->d2;
            }
            return &wide[i].d1;
        }
        if (wide[i].fp == 0 && spare < 0)
            spare = i;
    }
    if (spare < 0)
        spare = 0;
    wide[spare].fp = fp;
    wide[spare].d1 = (double)fp->d1;
    wide[spare].d2 = (double)fp->d2;
    return &wide[spare].d1;
}

static int32_t wide_round(double y)
{
    return (int32_t)(y >= 0.0 ? y + 0.5 : y - 0.5);
}

/* The same recursion pole_filter runs, in doubles. The three weights carry
   the doubling and quadrupling that one applies on the way out. */
static void pole_filter_wide(filter_parms *fp, int32_t *buf, int32_t n)
{
    double *z = wide_state(fp);
    double y1 = z[0], y2 = z[1];
    int32_t i = 0, count, k;

    if (klatt_wide_on() == 2) {
        int32_t v1 = (int32_t)z[0], v2 = (int32_t)z[1], t1, t2, t3;

        if (fp->ramp != 0) {
            count = fp->ramp < n ? fp->ramp : n;
            k = 3 - fp->ramp;

            for (; i < count; i++) {
                t1 = fxmul_scaled(fp->c[k], v2);
                t2 = fxmul_scaled(fp->b[k], v1);
                t3 = fxmul_scaled(fp->a[k], buf[i]);
                v2 = v1;
                v1 = t1 + t2 * 2 + t3 * 4;
                buf[i] = v1;
                k++;
            }
            fp->ramp -= count;
        }
        for (; i < n; i++) {
            t1 = fxmul_scaled(fp->sc, v2);
            t2 = fxmul_scaled(fp->sb, v1);
            t3 = fxmul_scaled(fp->sa, buf[i]);
            v2 = v1;
            v1 = t1 + t2 * 2 + t3 * 4;
            buf[i] = v1;
        }
        z[0] = v1;
        z[1] = v2;
        fp->d1 = v1;
        fp->d2 = v2;
        return;
    }

    if (fp->ramp != 0) {
        count = fp->ramp < n ? fp->ramp : n;
        k = 3 - fp->ramp;

        for (; i < count; i++) {
            double y = fp->a[k] * (4.0 / 32768.0) * buf[i]
                     + fp->b[k] * (2.0 / 32768.0) * y1
                     + fp->c[k] * (1.0 / 32768.0) * y2;

            y2 = y1;
            y1 = y;
            buf[i] = wide_round(y);
            k++;
        }
        fp->ramp -= count;
    }

    for (; i < n; i++) {
        double y = fp->sa * (4.0 / 32768.0) * buf[i]
                 + fp->sb * (2.0 / 32768.0) * y1
                 + fp->sc * (1.0 / 32768.0) * y2;

        y2 = y1;
        y1 = y;
        buf[i] = wide_round(y);
    }

    z[0] = y1;
    z[1] = y2;
    fp->d1 = wide_round(y1);
    fp->d2 = wide_round(y2);
}

int klatt_wide_on(void)
{
    return wide_on;
}
