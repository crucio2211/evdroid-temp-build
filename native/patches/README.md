# Engine patches

- Heteronyms, including `produce`, are handled only by the contextual table in `openevv/src/eci/hetero/eci_hetero.c`; there are no forced one-way overrides or duplicate patches to reapply during builds.
- Dictionaries (`enus.dict` with disputed names, smileys, numbers) are baked into the rule code at build time via `dict.py build`.
- `../patches-retired/` keeps the historical series for reference.
