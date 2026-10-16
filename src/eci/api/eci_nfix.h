#ifndef ECI_NFIX_H
#define ECI_NFIX_H

#include <stdint.h>

/* Say the Spanish enye the way it sounds. The English letter rules fold n
   with a tilde into a plain n, so Nina with a tilde was read nina and a
   word like Espana lost the y. Answers a string for the caller to give
   back with nfix_free, or 0 when the text has no enye in it (or there was
   no room to change it in). */
char *nfix_apply(const char *text);

/* The C library's own allocator made it, which the engine's sources do not
   share, so it goes back through here and not through their free. */
void nfix_free(char *text);

/* Whether it applies to text in this language: the two Englishes. */
int nfix_wanted(int32_t language);

#endif
