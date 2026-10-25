# Engine patches

- `produce` uses only the contextual heteronym table in `openevv/src/eci/hetero/eci_hetero.c`. The existing fixed readings for `persona` and `personas` are preserved; there is no duplicate patch to reapply during builds.
- Dictionaries (`enus.dict` with disputed names, smileys, numbers) are baked into the rule code at build time via `dict.py build`.
- `../patches-retired/` keeps the historical series for reference.
