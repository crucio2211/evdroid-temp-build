# Engine patches

None are applied now. Everything the patches here used to lay on the engine
(0006, 0007, 0009, 0010, 0013, 0015, 0019 and 0028, and the names, smileys and
number rules of 0011, 0012, 0014 and 0023 to 0027) is part of the engine tree
the `native/openevv` submodule points at: the openevv-16k merge of upstream
openevv with those changes, which already carries the baked dictionaries.

`../patches-retired/` keeps the old series for reference. It was written for
the older `eng61` engine and does not apply to the merged tree. To build
against `eng61` again, point the submodule back at it and move the files back.
