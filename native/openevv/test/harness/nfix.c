/* Checks the enye spelling, apart from the engine.
   gcc -I src/eci/api test/harness/nfix.c src/eci/api/eci_nfix.c -o nfix && ./nfix */
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include "eci_nfix.h"
static int bad;
static void t(const char *in, const char *want) {
    char *o = nfix_apply(in); const char *got = o ? o : in;
    if (strcmp(got, want)) { printf("FAIL  %s -> %s   (wanted %s)\n", in, got, want); bad++; }
    else printf("ok    %s -> %s\n", in, got);
    free(o);
}
int main(void) {
    /* The Windows Western bytes, as the add-on sends them. */
    t("pi\xf1" "ata", "pinyata");
    t("a\xf1" "o", "anyo");
    t("ni\xf1" "o", "ninyo");
    t("ca\xf1" "on", "canyon");
    t("se\xf1" "or", "senyor");
    t("\xf1" "a", "nya");
    t("\xd1" "a", "Nya");
    t("\xd1" "andu", "Nyandu");
    t("Mu\xd1" "oz", "MuNyoz");
    t("NI\xd1" "A", "NINYA");
    t("ESPA\xd1" "A", "ESPANYA");
    /* Alone, it stays the letter. */
    t("\xf1", "\xf1");
    t("a \xd1 b", "a \xd1 b");
    t("(\xf1)", "(\xf1)");
    t("\xf1.", "\xf1.");
    /* UTF-8. */
    t("pi\xc3\xb1" "ata", "pinyata");
    t("A\xc3\x91" "O", "ANYO");
    t("\xc3\xb1", "\xc3\xb1");
    t("\xc3\xb1" "a", "nya");
    /* Other letters that start with the same byte are not an enye. */
    t("\xd1\x80", "\xd1\x80");
    t("\xf1\x80\x80\x80", "\xf1\x80\x80\x80");
    /* The caller's own annotations are left alone. */
    t("`vs50 pi\xf1" "ata `pp5", "`vs50 pinyata `pp5");
    t("`[pi\xf1" "ata] pi\xf1" "ata", "`[pi\xf1" "ata] pinyata");
    t("no change here", "no change here");
    t("", "");
    printf(bad ? "FAILED %d\n" : "all passed\n", bad);
    return bad != 0;
}
