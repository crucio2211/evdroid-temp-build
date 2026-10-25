# Engine patches

- The heteronym filter changes are part of the checked-in engine source at `openevv/src/eci/hetero/eci_hetero.c`. The `produce` noun form uses the contextual heteronym, and the verb fallback follows the `/du/` phones in the US root entry for `producer`; there is no duplicate patch to reapply during builds.
- Dictionaries (`enus.dict` with disputed names, smileys, numbers) are baked into the rule code at build time via `dict.py build`.
- `../patches-retired/` keeps the historical series for reference.
