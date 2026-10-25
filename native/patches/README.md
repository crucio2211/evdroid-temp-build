# Engine patches

- `0006-hetero-plain-words.patch`: Contextual noun reading for `produce`, forced readings for persona/personas, and high-byte support for enye. Applied and checked at build time.
- Dictionaries (`enus.dict` with disputed names, smileys, numbers) are baked into the rule code at build time via `dict.py build`.
- `../patches-retired/` keeps the historical series for reference.
