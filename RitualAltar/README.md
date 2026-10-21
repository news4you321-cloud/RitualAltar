# RitualAltar (Paper 1.21.11, Java 21)

Altar with a floating, spinning item, a hologram (ALTAR / item name / RITUAL mm:ss)
and a live countdown. When it hits 00:00, right-click the centre block to claim the item.

## Build the jar
    mvn package
The jar is created at target/RitualAltar-1.0.0.jar. Put it in your server's plugins/ folder.
(No Maven? Push this folder to a GitHub repo; the included Action builds the jar for you.)

## Commands (permission: ritualaltar.admin)
    /altar create <id> [mm:ss]   hold the item to show, stand where the altar should be
    /altar remove <id>
    /altar list
    /altar start <id>            restart the ritual
    /altar time <id> <mm:ss>     set remaining time
    /altar item <id>             change the item (hold the new one)
    /altar reload