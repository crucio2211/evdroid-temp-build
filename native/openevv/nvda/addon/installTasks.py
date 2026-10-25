# Keeps the reader's dictionary files across an update of the add-on.
#
# NVDA replaces an add-on's folder whole when a new version is installed, and a
# dictionary dropped beside the driver (enumain.dic, enuroot.dic, ...) would go
# with it. This runs while the new version is being installed, when the old
# folder is still there, and copies those files into the new one. Nothing is
# moved or overwritten: a file the new version already has is left alone, and
# the old folder is left for NVDA to remove as usual.
#
# Dictionaries kept in the reader's own settings (openevv\dictionaries in the
# NVDA configuration) are not in the add-on's folder and need none of this.

import os
import re
import shutil

import addonHandler
import globalVars
from logHandler import log

NAME = "openevv"

#: The files the driver reads: the language's three letters, then the volume.
_DICTIONARY = re.compile(
	r"^(enu|eng|esp|esm|fra|frc|deu|ita|ptb|jpn|fin|pol)(main|root|abbr|mainext)\.dic$",
	re.IGNORECASE,
)


def preserveDictionaries(old, new):
	"""Copy the dictionary files in the folder `old` into the folder `new`,
	leaving alone any that `new` already has. Returns the names copied."""
	copied = []
	if not os.path.isdir(old) or not os.path.isdir(new):
		return copied
	if os.path.abspath(old) == os.path.abspath(new):
		return copied
	present = {n.lower() for n in os.listdir(new)}
	for leaf in os.listdir(old):
		if not _DICTIONARY.match(leaf) or leaf.lower() in present:
			continue
		source = os.path.join(old, leaf)
		if not os.path.isfile(source):
			continue
		try:
			shutil.copy2(source, os.path.join(new, leaf))
			copied.append(leaf)
		except OSError:
			log.warning("openevv: could not keep dictionary %s" % leaf, exc_info=True)
	return copied


def onInstall():
	try:
		base = os.path.join(globalVars.appArgs.configPath, "addons")
		old = os.path.join(base, NAME, "synthDrivers")
		new = os.path.join(base, NAME + addonHandler.ADDON_PENDINGINSTALL_SUFFIX, "synthDrivers")
		kept = preserveDictionaries(old, new)
		if kept:
			log.info("openevv: kept dictionaries across the update: %s" % ", ".join(kept))
	except Exception:  # noqa: BLE001
		# A failure here must never stop the add-on installing.
		log.warning("openevv: could not keep the dictionaries", exc_info=True)
