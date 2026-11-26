# PLBuildings plugin
[Czytaj po polsku](README.md).

## Description
PLBuildings is a [JOSM](https://josm.openstreetmap.de/) plugin
easily allows users to import buildings from Polish public datasets to add it to the OpenStreetMap.
It communicates with the [PLBuildings server](https://github.com/praszuk/josm-plbuildings-server) that obtains this data.

## Features
- Easy import of building at cursor position.
- Replacing geometry if old building is selected.
- Updating tags with the possibility of skipping most common replacement conflicts like from `building=yes` to `building=house` without showing the conflict tag dialog.
- Merging a building with an existing building. Adjacent nodes will be shared between the two buildings, meaning no overlapping or duplicated nodes.
- Avoiding duplicated imports.
- Simple statistics (counting imported/replaced buildings).
- Multilingual with i18n (English and Polish).
- Pre-checking buildings before replacing:
  - Looking for "survey" value.
  - Preventing tags simplification (e.g. `detached` -/> `house`)
  - Avoiding breaking `building:levels` if selected budilings has `roof:levels` tag.
- Post-checking imported building to notify about uncommon tags.
- Post-checking imported building to notify if object contains tags with lifecycle prefixes.
- Handling `construction=*` tag.
- Statuses (current state of import action).
- Import modes:
  - Full – default (full import of new building or full replace)
  - Geometry – geometry only update of selected building
  - Tags – tags only update of selected building
- Settings:
  - Multiple servers configurations
  - Multiple data sources including combined data sources (1st for geometry, 2nd for tags) – it also contains resolver if one source is missing
  - Notifications
  - Uncommon tags
  - Autoremove source
  - ...more settings in JOSM advanced preferences with searching by key `plbuildings.`
- GUI Sidebar (Toggle Dialog) with:
  - Current status (of import operation)
  - Import mode
  - Data source
  - Latest tags (with Uncommon tags indicator)
- Auto-inject `source:building` and `source:geometry` tags
- Autoremove specific values from the `source` tag (e.g. `geoportal.gov.pl`)
- Tag conflict dialog with a "Remember" option – when updating a building, each conflicting tag pair (e.g. `building=manufacture` vs `building=outbuilding`, different `building:levels` values) can be resolved once, and the choice (OSM or import value) is remembered for that specific tag pair until JOSM is closed.
- Frequent-conflict warning – when a remembered tag pair conflicts for the 10th time, the plugin shows a one-time warning about a possible low-quality import with a link to the OSM Poland community Discord (https://discord.gg/96y4wSxG5).
- Importing several selected building parts as one building – addresses of the parts are extracted as separate address nodes, and services (e.g. `shop=*`, `amenity=*`) as separate points; the whole operation is a single undo step.
- Keeping objects attached to the building – doors (`entrance=*`), fences (`barrier=*`), cameras and power lines attached to the outline are moved onto the closest points of the new outline.
- Cutting fences which cross the new building outline, merging duplicated nodes and removing fences lying along the building walls.
- Protecting `landuse` areas – areas attached to the building are never moved or merged during import.
- Safe rollback – when an import fails, the original building geometry is restored and no dangling node references are left in the data layer.

## Tag conflicts with the remember option
When replacing a building with import data, if the tags differ from those in OSM (e.g. OSM: `building=manufacture`, import: `building=outbuilding`; different `building:levels` values; uncommon tags with `building=yes` from the import), a single dialog is shown with the list of all conflicts. Each conflict has a "Remember" checkbox – checking it remembers the chosen value (from OSM or from the import) for that specific tag pair until JOSM is closed. Further conflicts of the same pair are then resolved automatically, without a dialog.

When a remembered pair conflicts for the 10th time and the chosen value is changed, the plugin shows a one-time message that the conflict occurs many times and that a low-quality import may have been performed in the area – with a clickable link to the OSM Poland community Discord: https://discord.gg/96y4wSxG5.

## Importing several selected building parts
Selecting several parts of one block (e.g. three segments with separate addresses) and triggering the import replaces them with one building from the import. The addresses (`addr:*`) of the parts are extracted as separate address nodes placed at the part centres. The services of the parts (e.g. `shop=*`, `amenity=*`, `office=*`) are extracted to separate points – one point per service; a single service shares the node with the part's address (a shop with its address is one point), and with several services each gets its own node. Doors and other objects attached to the parts are moved onto the new outline. The whole operation is a single undo step (Ctrl+Z). The import is canceled if any of the parts is a member of a relation.

## Objects attached to the building and fences
When replacing the geometry, objects attached to the outline at its points – doors (`entrance=*`), fences (`barrier=*`), cameras, power lines etc. – are moved onto the closest points of the new outline and stay attached to it. Fences crossing the new outline are cut at the crossing points (reusing existing nodes), nodes lying at the same position are merged, and fences lying along the building walls are removed. `landuse` areas attached to the building stay untouched.

## How to use it
1. Install plugin in JOSM (same as other plugins via JOSM plugin settings).
2. Open ToggleDialog: On the left sidebar should be visible icon with houses (logo of this plugin) – click it. On the right side should appear toggle dialog same as below on the GIF.
3. Select Import mode and Data source – it will be remembered, but I recommend to keep dialog always open at importing to easily switching and keep looking on latest import.
4. Move the mouse cursor on a visible building and press `CTRL + SHIFT + 1` – it will trigger building import. If you select other building before pressing shortcut combination, then it will use _Replace Geometry_ feature (from [utilsplugin2](https://wiki.openstreetmap.org/wiki/JOSM/Plugins/utilsplugin2))
   to replace old shape and tags with the new one. If the tags differ, the plugin shows its own conflict dialog with an option to remember the choice (see above).

Note: If shortcut doesn't work (it may happen) or you want to just change it, go to the JOSM Keyboard Shortcut Settings and type `plbuildings`,
then change it for `Download building`.

**Important! Every import should be verified!** There is no such thing as perfect data, so occasional minor errors may occur, such as in the building type or the number of levels. Additionally, a building might serve a different [functional purpose](https://wiki.openstreetmap.org/wiki/Key:building:use).


Simple building import:
![Simple building import](media/simple_import.gif)

Import with replacing old building geometry:
![Import with replacing old building geometry](media/import_with_replace.gif)

To show statistics, go to:`Menu->Data->Buildings import stats`

Example stats:

![Buildings import statistics](media/import_stats2.jpg)

Toggle dialog (GUI Sidebar) with a status changing at import:

![Toggle dialog at import action](media/toggledialog2.gif)

Plugin settings `Menu->Data->PlBuildings: Settings`:

![Plugin settings: data sources](media/settings_data_sources.png)
![Plugin settings: notifications](media/settings_notifications.png)
![Plugin settings: uncommon tags](media/settings_uncommon_tags.png)
![Plugin settings: autoremove source](media/settings_autoremove_source.png)

## License
[GPLv3](LICENSE)