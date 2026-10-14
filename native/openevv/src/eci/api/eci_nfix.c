/* The enye in English text.
 *
 * Underneath, the English letter rules fold n with a tilde into a plain n,
 * so a word comes out as if the tilde were not there: Pinata was said
 * pinata, and the y of Espana, Senor and Nino was lost. Spanish has its own
 * letter for it and reads it right; this is for the two Englishes only.
 *
 * An enye with a letter beside it is spelled ny, the way the English rules
 * already read that pair in the middle of a word, and Ny for a capital, or
 * NY in a word written in capitals. At the start of a word the rules would
 * read ny as the word nigh whatever follows, so the syllables a word can
 * start with, nya, nye, nyi, nyo and nyu, are entries of their own in
 * lang/enus/enus.dict (roots3).
 *
 * An enye standing alone is the letter, not a part of a word, and is left
 * as it is for the dictionary's character names to read as enye.
 *
 * It comes either as a byte of the Windows Western set, 0xF1 and 0xD1, or as
 * UTF-8, 0xC3 0xB1 and 0xC3 0x91, depending on who is asking. The Western
 * bytes are also the lead bytes of other UTF-8 letters, which are always
 * followed by a continuation byte, so they are an enye only when none
 * follows.
 *
 * Everything between a backtick and the end of its annotation is the
 * caller's own and is left exactly as it came. EVV_NFIX=off leaves all of
 * this out, for comparison. */

#include <stdlib.h>
#include <string.h>

#include "eci_nfix.h"

#define LANG_ENUS 0x10000
#define LANG_ENGB 0x10001

static int is_upper(int c)
{
    return c >= 'A' && c <= 'Z';
}

static int is_lower(int c)
{
    return c >= 'a' && c <= 'z';
}

/* Any letter at all, the accented ones included. */
static int is_letter(int c)
{
    return is_upper(c) || is_lower(c) || c >= 0x80;
}

static int continuation(int c)
{
    return c >= 0x80 && c <= 0xbf;
}

/* How many bytes of an enye start at p, and whether it is a capital. Zero
   when what is there is not one. */
static int enye_at(const char *p, int *upper)
{
    const unsigned char *u = (const unsigned char *)p;

    if (u[0] == 0xc3 && (u[1] == 0xb1 || u[1] == 0x91)) {
        *upper = u[1] == 0x91;
        return 2;
    }
    if ((u[0] == 0xf1 || u[0] == 0xd1) && !continuation(u[1])) {
        *upper = u[0] == 0xd1;
        return 1;
    }
    return 0;
}

/* The end of an annotation: a backtick and what belongs to it. A bracketed
   one runs to its closing bracket; the others are a code, a number and
   sometimes a quoted string. */
static const char *annotation_end(const char *p)
{
    const char *q = p + 1;

    if (*q == '[') {
        while (*q && *q != ']')
            q++;
        return *q ? q + 1 : q;
    }
    while ((*q >= '0' && *q <= '9') || is_lower((unsigned char)*q)
           || is_upper((unsigned char)*q) || *q == '+' || *q == '-')
        q++;
    if (*q == '"') {
        q++;
        while (*q && *q != '"')
            q++;
        if (*q)
            q++;
    }
    return q;
}

char *nfix_apply(const char *text)
{
    size_t n = strlen(text), count = 0;
    char *out, *o;
    const char *p;
    int upper;

    for (p = text; *p; p++)
        if (enye_at(p, &upper))
            count++;
    if (count == 0)
        return 0;

    /* Each is two bytes at the most, and the output is no longer than the
       input with one byte added for each. */
    out = malloc(n + count + 1);
    if (out == 0)
        return 0;
    o = out;

    for (p = text; *p;) {
        int len;

        if (*p == '`' && (p == text || p[-1] != '\\')) {
            const char *q = annotation_end(p);

            memcpy(o, p, (size_t)(q - p));
            o += q - p;
            p = q;
            continue;
        }

        len = enye_at(p, &upper);
        if (len) {
            int left = p > text && is_letter((unsigned char)p[-1]);
            int right = is_letter((unsigned char)p[len]);

            if (left || right) {
                /* A word in capitals: a letter either side is a capital. */
                int caps = upper
                    && ((left && is_upper((unsigned char)p[-1]))
                        || is_upper((unsigned char)p[len]));

                *o++ = upper ? 'N' : 'n';
                *o++ = caps ? 'Y' : 'y';
                p += len;
                continue;
            }
            /* Alone, it is the letter. */
            memcpy(o, p, (size_t)len);
            o += len;
            p += len;
            continue;
        }

        *o++ = *p++;
    }
    *o = 0;
    return out;
}

void nfix_free(char *text)
{
    free(text);
}

int nfix_wanted(int32_t language)
{
    static int decided, on = 1;

    if (language != LANG_ENUS && language != LANG_ENGB)
        return 0;
    if (!decided) {
        const char *say = getenv("EVV_NFIX");

        decided = 1;
        if (say != 0 && strcmp(say, "off") == 0)
            on = 0;
    }
    return on;
}
