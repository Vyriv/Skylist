plugins {
    id("dev.kikugie.stonecutter")
}

stonecutter active "26.1.2" /* [SC] DO NOT EDIT */

// See https://stonecutter.kikugie.dev/wiki/config/params
stonecutter parameters {
    swaps["mod_version"] = "\"${property("mod.version")}\";"
    swaps["minecraft"] = "\"${node.metadata.version}\";"

    // Extend this block with `replacements { string(...) { replace(...) } }` whenever a future
    // Minecraft version renames an API Skylist depends on. Nothing needed yet: 26.1.2 and 26.2
    // share the same GuiGraphics/rendering surface Skylist already targets.
}
