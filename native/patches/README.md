# Engine patches

- `produce` uses a forced ETI spelling without explicit stress digits, preserving the `x` schwa. The existing `persona` and `personas` forced readings are preserved; there is no duplicate patch to reapply during builds.
- Dictionaries (`enus.dict` with disputed names, smileys, numbers) are baked into the rule code at build time via `dict.py build`.
- `../patches-retired/` keeps the historical series for reference.
